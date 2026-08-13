package com.horizonradio.server;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.horizonradio.HorizonRadio;
import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.core.server.PlaylistState;
import com.horizonradio.network.HorizonRadioNetwork;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.ChartAddCompletionPacket;
import com.horizonradio.network.packets.LoopStatePacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SelectRadioStationPacket;
import com.horizonradio.network.packets.ShuffleStatePacket;
import com.horizonradio.network.packets.TrackSyncPacket;
import com.horizonradio.server.media.MediaException;
import com.horizonradio.server.media.YouTubeUrlParser;

import cpw.mods.fml.common.network.simpleimpl.IMessage;

/** Server-authoritative ID-only queue and playback timing coordinator. */
public final class PlaylistManager {

    private static final long CLIENT_TRACK_START_DELAY_MS = 3000L;
    private static final Logger LOGGER = Logger.getLogger(PlaylistManager.class.getName());

    interface PacketBroadcaster {

        void broadcast(IMessage packet, List<EntityPlayerMP> recipients);
    }

    private static final PacketBroadcaster NETWORK_BROADCASTER = new PacketBroadcaster() {

        @Override
        public void broadcast(IMessage packet, List<EntityPlayerMP> recipients) {
            for (EntityPlayerMP player : recipients) {
                HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
            }
        }
    };

    private final MinecraftServer server;
    private final PlaylistState state;
    private final int maxPlaylistSize;
    private final long maxTrackDurationMs;
    private final boolean serverDebugChat;
    private final ScheduledExecutorService scheduler;
    private final PacketBroadcaster packetBroadcaster;

    private ScheduledFuture<?> advanceFuture;
    private long playbackGeneration;
    private boolean shuttingDown;

    public PlaylistManager(MinecraftServer server, File configDirectory) {
        this(server, configDirectory, NETWORK_BROADCASTER);
    }

    PlaylistManager(MinecraftServer server, File configDirectory, PacketBroadcaster packetBroadcaster) {
        this.server = server;
        this.packetBroadcaster = packetBroadcaster == null ? NETWORK_BROADCASTER : packetBroadcaster;
        HorizonRadioConfig config = HorizonRadio.getConfig();
        if (config == null) {
            config = HorizonRadioConfig.load(configDirectory);
        }
        maxPlaylistSize = config.getMaxPlaylistSize();
        maxTrackDurationMs = config.getMaxTrackDurationMinutes() * 60L * 1000L;
        serverDebugChat = config.isServerDebugChat();
        state = new PlaylistState(maxPlaylistSize);
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "HorizonRadio-Queue");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public void handleAddToPlaylist(EntityPlayerMP player, String videoId, long durationMs) {
        if (!acceptsPlayer(player) || !isValidFiniteEntry(videoId, durationMs)) {
            sendChat(player, EnumChatFormatting.RED, "Invalid playlist entry.");
            return;
        }

        PlaylistEntry entry = PlaylistEntry.youtube(videoId, durationMs, playerName(player));
        if (!state.add(entry)) {
            sendChat(player, EnumChatFormatting.YELLOW, "The queue is full.");
            return;
        }
        int addedIndex = state.size() - 1;
        broadcastDelta(PlaylistDeltaPacket.add(state.getQueueRevision(), toDeltaEntry(entry), addedIndex));
        if (!state.isPlaying()) {
            startNextFinite();
        }
    }

    public void handlePlayNow(EntityPlayerMP player, String videoId, long durationMs) {
        if (!acceptsPlayer(player) || !isValidFiniteEntry(videoId, durationMs)) {
            sendChat(player, EnumChatFormatting.RED, "Invalid playlist entry.");
            return;
        }

        int existingIndex = state.findIndex(MediaSourceType.YOUTUBE, videoId);
        boolean replacesCurrent = state.getCurrentIndex() >= 0 && state.getCurrentIndex() < state.size();
        if (existingIndex < 0 && state.size() >= maxPlaylistSize && !replacesCurrent) {
            sendChat(player, EnumChatFormatting.YELLOW, "The queue is full.");
            return;
        }

        boolean replacedRadio = state.getCurrentSourceType() == MediaSourceType.RADIO;
        cancelAdvancement();
        PlaylistEntry requested = existingIndex >= 0 ? state.get(existingIndex)
            : PlaylistEntry.youtube(videoId, durationMs, playerName(player));
        PlaylistEntry selected = state.prepareImmediatePlayback(requested);
        if (selected == null) {
            return;
        }
        if (replacedRadio) {
            state.takeLastTrack();
        }
        broadcastReplace();
        startFiniteTrack(0, selected);
    }

    public void handleAddChartsToPlaylist(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries,
        boolean remove) {
        if (!acceptsPlayer(player) || entries == null || entries.isEmpty()) {
            return;
        }
        if (remove) {
            removeChartEntries(entries);
        } else {
            addChartEntries(player, entries);
        }
        sendChartAddCompletion(player, entries);
    }

    public void handleSelectRadio(EntityPlayerMP player, String stationUuid) {
        if (!acceptsPlayer(player) || !isValidStationId(stationUuid)) {
            sendChat(player, EnumChatFormatting.RED, "Invalid radio station.");
            return;
        }

        PlaylistEntry station = PlaylistEntry.radio(stationUuid, playerName(player));
        if (!state.canSelectRadioAtFront(station)) {
            sendChat(player, EnumChatFormatting.YELLOW, "The queue is full.");
            return;
        }
        cancelAdvancement();
        if (!state.selectRadioAtFront(station)) {
            return;
        }
        long generation = nextPlaybackGeneration();
        broadcastReplace();
        broadcastTrackSync(TrackSyncPacket.radio(generation, stationUuid));
    }

    public void handleStopRadio(EntityPlayerMP player) {
        if (!acceptsPlayer(player) || state.getCurrentSourceType() != MediaSourceType.RADIO
            || state.getCurrentIndex() != 0) {
            return;
        }

        cancelAdvancement();
        state.removeCurrent();
        state.takeLastTrack();
        broadcastDelta(PlaylistDeltaPacket.remove(state.getQueueRevision(), 0));
        startNextFinite();
    }

    public void handleRemoveFromPlaylist(EntityPlayerMP player, String videoId) {
        if (!acceptsPlayer(player) || videoId == null || videoId.trim().isEmpty()) {
            return;
        }
        removeFiniteById(videoId, true);
    }

    public void handleClearPlaylist(EntityPlayerMP player) {
        if (!acceptsPlayer(player) || state.size() == 0) {
            return;
        }
        boolean wasLooping = state.isLooping();
        boolean wasShuffling = state.isShuffling();
        cancelAdvancement();
        state.clear();
        long generation = nextPlaybackGeneration();
        broadcastDelta(PlaylistDeltaPacket.clear(state.getQueueRevision()));
        broadcastTrackSync(TrackSyncPacket.stop(generation));
        if (wasLooping) {
            broadcast(new LoopStatePacket(false));
        }
        if (wasShuffling) {
            broadcast(new ShuffleStatePacket(false));
        }
    }

    public void handleReorder(EntityPlayerMP player, int fromIndex, int targetIndex) {
        if (!acceptsPlayer(player) || !state.moveQueued(fromIndex, targetIndex)) {
            return;
        }
        broadcastDelta(PlaylistDeltaPacket.move(state.getQueueRevision(), fromIndex, targetIndex));
    }

    public void handleSeek(EntityPlayerMP player, float progress) {
        if (!acceptsPlayer(player) || Float.isNaN(progress) || Float.isInfinite(progress)
            || state.getCurrentSourceType() != MediaSourceType.YOUTUBE || !state.isPlaying()) {
            return;
        }

        long durationMs = state.getCurrentTrackDurationMs();
        float safeProgress = Math.max(0.0F, Math.min(1.0F, progress));
        long requestedPositionMs = (long) (durationMs * safeProgress);
        long nowMs = System.currentTimeMillis();
        boolean paused = state.isPaused();
        long resumeAtMs = paused ? nowMs : nowMs + CLIENT_TRACK_START_DELAY_MS;
        long positionMs = state.seek(requestedPositionMs, resumeAtMs);
        if (positionMs < 0L) {
            return;
        }

        cancelAdvancement();
        broadcast(new PausePacket(positionMs));
        if (!paused) {
            broadcast(new ResumePacket(positionMs, resumeAtMs));
            scheduleAdvancement(playbackGeneration, positionMs, durationMs, resumeAtMs);
        }
    }

    public void handleTogglePlayback(EntityPlayerMP player) {
        if (!acceptsPlayer(player) || state.getCurrentSourceType() != MediaSourceType.YOUTUBE || !state.isPlaying()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        long durationMs = state.getCurrentTrackDurationMs();
        if (state.isPaused()) {
            long resumeAtMs = nowMs + CLIENT_TRACK_START_DELAY_MS;
            long positionMs = state.resumePlayback(resumeAtMs);
            if (positionMs >= 0L) {
                broadcast(new ResumePacket(positionMs, resumeAtMs));
                scheduleAdvancement(playbackGeneration, positionMs, durationMs, resumeAtMs);
            }
        } else {
            long positionMs = state.pausePlayback(currentPositionMs(nowMs), nowMs);
            if (positionMs >= 0L) {
                cancelAdvancement();
                broadcast(new PausePacket(positionMs));
            }
        }
    }

    public void handleSkipTrack(EntityPlayerMP player) {
        if (!acceptsPlayer(player) || !state.isPlaying()) {
            return;
        }
        if (state.getCurrentSourceType() == MediaSourceType.RADIO) {
            handleStopRadio(player);
            return;
        }
        if (state.getCurrentSourceType() != MediaSourceType.YOUTUBE) {
            return;
        }

        cancelAdvancement();
        removeCurrentFinite();
        startNextFinite();
    }

    public void handlePreviousTrack(EntityPlayerMP player) {
        if (!acceptsPlayer(player) || state.getCurrentSourceType() != MediaSourceType.YOUTUBE || !state.isPlaying()) {
            return;
        }
        long positionMs = state.isPaused() ? state.getPausedPositionMs() : currentPositionMs(System.currentTimeMillis());
        if (!state.wasPreviousRestarted() || positionMs > 10_000L) {
            state.markPreviousRestarted();
            handleSeek(player, 0.0F);
            return;
        }

        PlaylistEntry previous = state.takeLastTrack();
        if (previous == null || !previous.isFinite()) {
            handleSeek(player, 0.0F);
            return;
        }
        cancelAdvancement();
        PlaylistEntry selected = state.prepareImmediatePlayback(previous);
        broadcastReplace();
        startFiniteTrack(0, selected);
    }

    public void handleToggleLoop(EntityPlayerMP player) {
        if (!acceptsPlayer(player)) {
            return;
        }
        broadcast(new LoopStatePacket(state.toggleLooping()));
    }

    public void handleToggleShuffle(EntityPlayerMP player) {
        if (!acceptsPlayer(player)) {
            return;
        }
        boolean shuffling = state.toggleShuffling();
        long revisionBeforeShuffle = state.getQueueRevision();
        if (shuffling) {
            state.shuffleQueued();
        }
        broadcast(new ShuffleStatePacket(shuffling));
        if (state.getQueueRevision() != revisionBeforeShuffle) {
            broadcastReplace();
        }
    }

    public void syncToPlayer(EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        sendTo(
            new PlaylistSyncPacket(
                state.getQueueRevision(),
                state.isShuffling(),
                state.isLooping(),
                toSnapshotEntries(state.snapshot())),
            player);

        int currentIndex = state.getCurrentIndex();
        if (!state.isPlaying() || currentIndex < 0 || currentIndex >= state.size()) {
            sendTo(TrackSyncPacket.stop(playbackGeneration), player);
            return;
        }
        PlaylistEntry current = state.get(currentIndex);
        if (current.isRadio()) {
            sendTo(TrackSyncPacket.radio(playbackGeneration, current.getSourceId()), player);
        } else if (state.isPaused()) {
            sendTo(
                TrackSyncPacket.youtube(
                    playbackGeneration,
                    current.getSourceId(),
                    state.getPausedPositionMs(),
                    0L,
                    true),
                player);
        } else {
            sendTo(
                TrackSyncPacket.youtube(
                    playbackGeneration,
                    current.getSourceId(),
                    0L,
                    state.getPlaybackStartTime(),
                    false),
                player);
        }
    }

    public void shutdown() {
        shuttingDown = true;
        cancelAdvancement();
        scheduler.shutdownNow();
    }

    private void addChartEntries(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries) {
        Set<String> knownIds = new HashSet<String>();
        for (PlaylistEntry existing : state.snapshot()) {
            if (existing.isFinite()) {
                knownIds.add(existing.getSourceId());
            }
        }

        boolean wasPlaying = state.isPlaying();
        for (AddChartsToPlaylistPacket.Entry chart : entries) {
            if (chart == null || !isValidFiniteEntry(chart.getVideoId(), chart.getDurationMs())
                || !knownIds.add(chart.getVideoId())) {
                continue;
            }
            PlaylistEntry entry = PlaylistEntry.youtube(chart.getVideoId(), chart.getDurationMs(), playerName(player));
            if (!state.add(entry)) {
                break;
            }
            broadcastDelta(
                PlaylistDeltaPacket.add(state.getQueueRevision(), toDeltaEntry(entry), state.size() - 1));
        }
        if (!wasPlaying && state.size() > 0) {
            startNextFinite();
        }
    }

    private void removeChartEntries(List<AddChartsToPlaylistPacket.Entry> entries) {
        boolean removedCurrent = false;
        for (AddChartsToPlaylistPacket.Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            int index = state.findIndex(MediaSourceType.YOUTUBE, entry.getVideoId());
            if (index < 0) {
                continue;
            }
            removedCurrent |= index == state.getCurrentIndex();
            state.remove(entry.getVideoId());
            broadcastDelta(PlaylistDeltaPacket.remove(state.getQueueRevision(), index));
        }
        if (removedCurrent) {
            cancelAdvancement();
            startNextFinite();
        }
    }

    private void sendChartAddCompletion(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries) {
        List<String> completedIds = new ArrayList<String>();
        for (AddChartsToPlaylistPacket.Entry entry : entries) {
            if (entry != null && entry.getVideoId() != null && !entry.getVideoId().trim().isEmpty()) {
                completedIds.add(entry.getVideoId());
            }
        }
        sendTo(new ChartAddCompletionPacket(completedIds), player);
    }

    private void removeFiniteById(String videoId, boolean startReplacement) {
        int index = state.findIndex(MediaSourceType.YOUTUBE, videoId);
        if (index < 0) {
            return;
        }
        boolean removedCurrent = index == state.getCurrentIndex();
        if (removedCurrent) {
            cancelAdvancement();
        }
        state.remove(videoId);
        broadcastDelta(PlaylistDeltaPacket.remove(state.getQueueRevision(), index));
        if (removedCurrent && startReplacement) {
            startNextFinite();
        }
    }

    private void removeCurrentFinite() {
        int index = state.getCurrentIndex();
        PlaylistEntry removed = state.removeCurrent();
        if (removed != null) {
            broadcastDelta(PlaylistDeltaPacket.remove(state.getQueueRevision(), index));
        }
    }

    private void startNextFinite() {
        int nextIndex = state.getCurrentIndex() + 1;
        if (nextIndex < 0 || nextIndex >= state.size()) {
            stopPlayback();
            return;
        }
        PlaylistEntry next = state.get(nextIndex);
        if (!next.isFinite() || next.getDurationMs() <= 0L) {
            stopPlayback();
            return;
        }
        startFiniteTrack(nextIndex, next);
    }

    private void startFiniteTrack(int index, PlaylistEntry entry) {
        cancelAdvancement();
        long generation = nextPlaybackGeneration();
        long startAtMs = System.currentTimeMillis() + CLIENT_TRACK_START_DELAY_MS;
        state.startFiniteTrack(index, entry.getSourceId(), entry.getDurationMs(), startAtMs);
        broadcastTrackSync(
            TrackSyncPacket.youtube(generation, entry.getSourceId(), 0L, startAtMs, false));
        scheduleAdvancement(generation, 0L, entry.getDurationMs(), startAtMs);
    }

    private void stopPlayback() {
        state.resetPlayback();
        broadcastTrackSync(TrackSyncPacket.stop(nextPlaybackGeneration()));
    }

    private void scheduleAdvancement(final long generation, long positionMs, long durationMs, long startAtMs) {
        cancelAdvancement();
        long remainingMs = Math.max(1L, durationMs - positionMs);
        long delayMs = Math.max(1L, startAtMs - System.currentTimeMillis() + remainingMs);
        advanceFuture = scheduler.schedule(new Runnable() {

            @Override
            public void run() {
                enqueueServerTask(new Runnable() {

                    @Override
                    public void run() {
                        advanceAfterCompletion(generation);
                    }
                });
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void advanceAfterCompletion(long generation) {
        if (shuttingDown || generation != playbackGeneration
            || state.getCurrentSourceType() != MediaSourceType.YOUTUBE || !state.isPlaying()) {
            return;
        }
        advanceFuture = null;
        if (state.isLooping()) {
            int currentIndex = state.getCurrentIndex();
            startFiniteTrack(currentIndex, state.get(currentIndex));
            return;
        }
        removeCurrentFinite();
        if (state.isShuffling()) {
            long revisionBeforeShuffle = state.getQueueRevision();
            state.shuffleQueued();
            if (state.getQueueRevision() != revisionBeforeShuffle) {
                broadcastReplace();
            }
        }
        startNextFinite();
    }

    private long currentPositionMs(long nowMs) {
        long durationMs = state.getCurrentTrackDurationMs();
        return Math.max(0L, Math.min(Math.max(0L, durationMs - 1L), nowMs - state.getPlaybackStartTime()));
    }

    private void broadcastReplace() {
        broadcastDelta(
            PlaylistDeltaPacket.replace(state.getQueueRevision(), toDeltaEntries(state.snapshot())));
    }

    private void broadcastDelta(PlaylistDeltaPacket packet) {
        broadcast(packet);
    }

    private void broadcastTrackSync(TrackSyncPacket packet) {
        broadcast(packet);
    }

    private void broadcast(IMessage packet) {
        packetBroadcaster.broadcast(packet, onlinePlayersSnapshot());
    }

    private void sendTo(IMessage packet, EntityPlayerMP player) {
        if (server != null && player != null) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private List<EntityPlayerMP> onlinePlayersSnapshot() {
        List<EntityPlayerMP> players = new ArrayList<EntityPlayerMP>();
        if (server == null || server.getConfigurationManager() == null) {
            return players;
        }
        for (Object candidate : server.getConfigurationManager().playerEntityList) {
            if (candidate instanceof EntityPlayerMP) {
                players.add((EntityPlayerMP) candidate);
            }
        }
        return players;
    }

    private List<PlaylistSyncPacket.Entry> toSnapshotEntries(List<PlaylistEntry> entries) {
        List<PlaylistSyncPacket.Entry> packetEntries = new ArrayList<PlaylistSyncPacket.Entry>();
        for (PlaylistEntry entry : entries) {
            packetEntries.add(
                new PlaylistSyncPacket.Entry(entry.getSourceType(), entry.getSourceId(), safe(entry.getAddedBy())));
        }
        return packetEntries;
    }

    private List<PlaylistDeltaPacket.Entry> toDeltaEntries(List<PlaylistEntry> entries) {
        List<PlaylistDeltaPacket.Entry> packetEntries = new ArrayList<PlaylistDeltaPacket.Entry>();
        for (PlaylistEntry entry : entries) {
            packetEntries.add(toDeltaEntry(entry));
        }
        return packetEntries;
    }

    private PlaylistDeltaPacket.Entry toDeltaEntry(PlaylistEntry entry) {
        return new PlaylistDeltaPacket.Entry(entry.getSourceType(), entry.getSourceId(), safe(entry.getAddedBy()));
    }

    private boolean isValidFiniteEntry(String videoId, long durationMs) {
        if (durationMs <= 0L || durationMs >= maxTrackDurationMs) {
            return false;
        }
        try {
            YouTubeUrlParser.requireVideoId(videoId);
            return true;
        } catch (MediaException exception) {
            return false;
        }
    }

    private static boolean isValidStationId(String stationUuid) {
        if (stationUuid == null || stationUuid.trim().isEmpty()) {
            return false;
        }
        try {
            new SelectRadioStationPacket(stationUuid);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void enqueueServerTask(final Runnable task) {
        if (shuttingDown) {
            return;
        }
        if (server == null) {
            task.run();
            return;
        }
        ServerThreadExecutor.execute(server, new Runnable() {

            @Override
            public void run() {
                if (!shuttingDown) {
                    task.run();
                }
            }
        });
    }

    private void cancelAdvancement() {
        if (advanceFuture != null) {
            advanceFuture.cancel(false);
            advanceFuture = null;
        }
    }

    private long nextPlaybackGeneration() {
        if (playbackGeneration < Long.MAX_VALUE) {
            playbackGeneration++;
        }
        return playbackGeneration;
    }

    private void sendChat(EntityPlayerMP player, EnumChatFormatting color, String message) {
        LOGGER.info("HorizonRadio: " + message);
        if (serverDebugChat && server != null && player != null) {
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "[HorizonRadio] " + color + message));
        }
    }

    private static boolean acceptsPlayer(EntityPlayerMP player) {
        return player != null;
    }

    private static String playerName(EntityPlayerMP player) {
        return player == null ? "" : safe(player.getCommandSenderName());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
