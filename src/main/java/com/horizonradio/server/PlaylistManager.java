package com.horizonradio.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.horizonradio.HorizonRadio;
import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.core.model.DurationParser;
import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartCache;
import com.horizonradio.core.server.PlaylistImportService;
import com.horizonradio.core.server.PlaylistState;
import com.horizonradio.network.HorizonRadioNetwork;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.LoopStatePacket;
import com.horizonradio.network.packets.NowPlayingPacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SearchResultsPacket;
import com.horizonradio.network.packets.ShuffleStatePacket;

/** Server-authoritative playlist, playback, and late-join synchronization. */
public final class PlaylistManager {

    private static final Logger LOGGER = Logger.getLogger(PlaylistManager.class.getName());
    private static final long DEFAULT_TRACK_DURATION_MS = 3L * 60L * 1000L;
    private static final long NEXT_TRACK_DELAY_MS = 2000L;
    private static final long LATE_JOIN_TIMEOUT_MS = 10000L;

    private final MinecraftServer server;
    private final YouTubeService youTubeService;
    private final AudioDownloadService audioDownloadService;
    private final PlaylistState state;
    private final ScheduledExecutorService scheduler;
    private final ChartCache chartCache;
    private final List<EntityPlayerMP> chartRefreshWaiters = new ArrayList<EntityPlayerMP>();
    private final Set<String> preloadingAudio = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private volatile boolean shuttingDown;
    private CompletableFuture<Path> activeAudioDownload;
    private String activeAudioVideoId;
    private boolean chartRefreshInProgress;
    private ScheduledFuture<?> advanceFuture;
    private ScheduledFuture<?> progressFuture;
    private ScheduledFuture<?> syncTimeoutFuture;

    public PlaylistManager(MinecraftServer server, YouTubeService youTubeService,
        AudioDownloadService audioDownloadService) {
        this(server, youTubeService, audioDownloadService, null);
    }

    public PlaylistManager(MinecraftServer server, YouTubeService youTubeService,
        AudioDownloadService audioDownloadService, File configDirectory) {
        if (server == null || youTubeService == null || audioDownloadService == null) {
            throw new IllegalArgumentException("server and services must not be null");
        }
        this.server = server;
        this.youTubeService = youTubeService;
        this.audioDownloadService = audioDownloadService;
        this.state = new PlaylistState(configuredMaxPlaylistSize());
        this.chartCache = new ChartCache(configDirectory);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "HorizonRadio-Scheduler");
                thread.setDaemon(true);
                return thread;
            }
        });

        progressFuture = scheduler.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                enqueueServerTask(new Runnable() {

                    @Override
                    public void run() {
                        broadcastProgress();
                    }
                });
            }
        }, 1L, 1L, TimeUnit.SECONDS);
    }

    public void handleSearch(final EntityPlayerMP player, String query) {
        if (player == null) {
            return;
        }
        LOGGER.info("Player " + player.getCommandSenderName() + " searching for: " + query);
        final long maxTrackDurationMs = configuredMaxTrackDurationMs();
        CompletableFuture<List<SearchResult>> searchFuture = youTubeService.search(query);
        searchFuture.thenAccept(new Consumer<List<SearchResult>>() {

            @Override
            public void accept(List<SearchResult> results) {
                final List<SearchResultsPacket.Entry> entries = new ArrayList<SearchResultsPacket.Entry>();
                if (results != null) {
                    for (SearchResult result : results) {
                        if (result != null && isSearchDurationAllowed(result.getDuration(), maxTrackDurationMs)) {
                            entries.add(
                                new SearchResultsPacket.Entry(
                                    safe(result.getVideoId()),
                                    safe(result.getTitle()),
                                    safe(result.getChannel()),
                                    safe(result.getDuration()),
                                    safe(result.getThumbnail())));
                        }
                    }
                }
                enqueueServerTask(new Runnable() {

                    @Override
                    public void run() {
                        HorizonRadioNetwork.CHANNEL.sendTo(new SearchResultsPacket(entries), player);
                    }
                });
            }
        });
    }

    public void handleRequestCharts(final EntityPlayerMP player, boolean forceRefresh) {
        if (player == null) {
            return;
        }

        final List<SearchResult> cached = chartCache.getResults();
        boolean operator = server.getConfigurationManager()
            .func_152596_g(player.getGameProfile());
        processChartRequest(
            forceRefresh,
            operator,
            !cached.isEmpty(),
            !cached.isEmpty() && chartCache.isFresh(),
            new ChartRequestActions() {

                @Override
                public void sendChartResults() {
                    PlaylistManager.this.sendChartResults(player, cached, false);
                }

                @Override
                public void sendChat(EnumChatFormatting color, String message) {
                    PlaylistManager.this.sendChat(player, color, message);
                }

                @Override
                public void registerWaiter() {
                    if (!chartRefreshWaiters.contains(player)) {
                        chartRefreshWaiters.add(player);
                    }
                }

                @Override
                public void refresh() {
                    refreshChartsIfNeeded();
                }
            });
    }

    interface ChartRequestActions {

        void sendChartResults();

        void sendChat(EnumChatFormatting color, String message);

        void registerWaiter();

        void refresh();
    }

    static void processChartRequest(boolean forceRefresh, boolean operator, boolean hasCachedCharts, boolean cacheFresh,
        ChartRequestActions actions) {
        if (!canRefreshCharts(forceRefresh, operator)) {
            actions.sendChartResults();
            actions.sendChat(EnumChatFormatting.RED, "Only server operators can refresh the charts.");
            return;
        }
        if (shouldServeCachedCharts(hasCachedCharts, forceRefresh, cacheFresh)) {
            actions.sendChartResults();
            return;
        }
        actions.sendChat(
            EnumChatFormatting.YELLOW,
            forceRefresh ? "Refreshing German YouTube Music Top 50..." : "Loading German YouTube Music Top 50...");
        actions.registerWaiter();
        actions.refresh();
    }

    static boolean canRefreshCharts(boolean forceRefresh, boolean operator) {
        return !forceRefresh || operator;
    }

    static boolean shouldServeCachedCharts(boolean hasCachedCharts, boolean forceRefresh, boolean cacheFresh) {
        return hasCachedCharts && !forceRefresh && cacheFresh;
    }

    private void refreshChartsIfNeeded() {
        if (chartRefreshInProgress) {
            return;
        }
        chartRefreshInProgress = true;
        youTubeService.fetchGermanTopCharts()
            .thenAccept(new Consumer<List<SearchResult>>() {

                @Override
                public void accept(final List<SearchResult> charts) {
                    if (charts == null || charts.isEmpty()) {
                        enqueueServerTask(new Runnable() {

                            @Override
                            public void run() {
                                finishChartRefresh(new ArrayList<SearchResult>());
                            }
                        });
                        return;
                    }
                    final List<String> videoIds = new ArrayList<String>();
                    for (SearchResult chartEntry : charts) {
                        videoIds.add(chartEntry.getVideoId());
                    }
                    audioDownloadService.extractVideoDurationOutput(videoIds)
                        .whenComplete(new BiConsumer<String, Throwable>() {

                            @Override
                            public void accept(final String durationOutput, Throwable failure) {
                                enqueueServerTask(new Runnable() {

                                    @Override
                                    public void run() {
                                        finishChartRefresh(withDurations(charts, durationOutput));
                                    }
                                });
                            }
                        });
                }
            });
    }

    private List<SearchResult> withDurations(List<SearchResult> charts, String durationOutput) {
        Map<String, String> durations = PlaylistImportService.parseDurationOutput(durationOutput);
        List<SearchResult> completed = new ArrayList<SearchResult>();
        for (SearchResult chartEntry : charts) {
            String duration = durations.get(chartEntry.getVideoId());
            if (duration != null && duration.length() > 0) {
                completed.add(
                    new SearchResult(
                        chartEntry.getVideoId(),
                        chartEntry.getTitle(),
                        chartEntry.getChannel(),
                        duration,
                        chartEntry.getThumbnail()));
            }
        }
        return completed;
    }

    private void finishChartRefresh(List<SearchResult> refreshed) {
        if (refreshed != null && !refreshed.isEmpty()) {
            chartCache.store(refreshed);
        }
        List<SearchResult> results = chartCache.getResults();
        List<EntityPlayerMP> waiters = new ArrayList<EntityPlayerMP>(chartRefreshWaiters);
        chartRefreshWaiters.clear();
        chartRefreshInProgress = false;
        for (EntityPlayerMP player : waiters) {
            sendChartResults(player, results, true);
        }
    }

    private void sendChartResults(EntityPlayerMP player, List<SearchResult> charts, boolean announce) {
        List<SearchResultsPacket.Entry> entries = new ArrayList<SearchResultsPacket.Entry>();
        for (SearchResult chartEntry : charts) {
            if (!isSearchDurationAllowed(chartEntry.getDuration(), configuredMaxTrackDurationMs())) {
                continue;
            }
            entries.add(
                new SearchResultsPacket.Entry(
                    chartEntry.getVideoId(),
                    chartEntry.getTitle(),
                    chartEntry.getChannel(),
                    chartEntry.getDuration(),
                    chartEntry.getThumbnail()));
        }
        HorizonRadioNetwork.CHANNEL.sendTo(new SearchResultsPacket(entries, true), player);
        if (announce) {
            sendChat(
                player,
                entries.isEmpty() ? EnumChatFormatting.YELLOW : EnumChatFormatting.GREEN,
                "Loaded " + entries.size() + " German chart songs.");
        }
    }

    public void handleImportPlaylist(final EntityPlayerMP player, String playlistUrl) {
        if (player == null || !PlaylistImportService.isPlaylistUrl(playlistUrl)) {
            if (player != null) {
                clearSearchResults(player);
                sendChat(player, EnumChatFormatting.RED, "Invalid YouTube playlist URL.");
            }
            return;
        }
        sendChat(player, EnumChatFormatting.YELLOW, "Importing YouTube playlist...");
        audioDownloadService.extractPlaylistJson(playlistUrl)
            .whenComplete(new BiConsumer<String, Throwable>() {

                @Override
                public void accept(final String json, Throwable failure) {
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            finishPlaylistImport(player, json);
                        }
                    });
                }
            });
    }

    public void handleImportVideo(final EntityPlayerMP player, String videoUrl) {
        if (player == null || !PlaylistImportService.isVideoUrl(videoUrl)) {
            if (player != null) {
                clearSearchResults(player);
                sendChat(player, EnumChatFormatting.RED, "Invalid YouTube video URL.");
            }
            return;
        }
        sendChat(player, EnumChatFormatting.YELLOW, "Reading YouTube video metadata...");
        audioDownloadService.extractVideoJson(videoUrl)
            .whenComplete(new BiConsumer<String, Throwable>() {

                @Override
                public void accept(final String json, Throwable failure) {
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            finishVideoImport(player, json);
                        }
                    });
                }
            });
    }

    private void finishVideoImport(EntityPlayerMP player, String json) {
        SearchResult result = PlaylistImportService.parseVideo(json);
        if (result == null) {
            clearSearchResults(player);
            sendChat(player, EnumChatFormatting.RED, "Could not read that YouTube video.");
            return;
        }
        if (!isSearchDurationAllowed(result.getDuration(), configuredMaxTrackDurationMs())) {
            clearSearchResults(player);
            sendChat(player, EnumChatFormatting.YELLOW, "This video is too long for the server search limit.");
            return;
        }
        for (PlaylistEntry entry : state.snapshot()) {
            if (result.getVideoId()
                .equals(entry.getVideoId())) {
                clearSearchResults(player);
                sendChat(player, EnumChatFormatting.YELLOW, "This video is already in the queue.");
                return;
            }
        }

        state.add(
            new PlaylistEntry(
                result.getVideoId(),
                result.getTitle(),
                result.getDuration(),
                player.getCommandSenderName()));
        if (state.isShuffling()) {
            state.shuffleQueued();
        }
        syncToAll();
        if (!state.isPlaying()) {
            playNext();
        }
        clearSearchResults(player);
        sendChat(player, EnumChatFormatting.GREEN, "Added video to the playlist.");
    }

    private void finishPlaylistImport(EntityPlayerMP player, String json) {
        if (json == null) {
            clearSearchResults(player);
            sendChat(player, EnumChatFormatting.RED, "Could not read the YouTube playlist.");
            return;
        }

        List<SearchResult> imported = PlaylistImportService.parse(json);
        Set<String> knownVideoIds = new HashSet<String>();
        for (PlaylistEntry entry : state.snapshot()) {
            knownVideoIds.add(entry.getVideoId());
        }

        int added = 0;
        for (SearchResult result : imported) {
            if (!isSearchDurationAllowed(result.getDuration(), configuredMaxTrackDurationMs())
                || !knownVideoIds.add(result.getVideoId())) {
                continue;
            }
            state.add(
                new PlaylistEntry(
                    result.getVideoId(),
                    result.getTitle(),
                    result.getDuration(),
                    player.getCommandSenderName()));
            added++;
        }
        if (state.isShuffling()) {
            state.shuffleQueued();
        }
        if (added > 0) {
            syncToAll();
            if (!state.isPlaying()) {
                playNext();
            }
        }
        clearSearchResults(player);
        sendChat(
            player,
            added > 0 ? EnumChatFormatting.GREEN : EnumChatFormatting.YELLOW,
            "Imported " + added + " playlist entr" + (added == 1 ? "y" : "ies") + ".");
    }

    private void clearSearchResults(EntityPlayerMP player) {
        HorizonRadioNetwork.CHANNEL.sendTo(new SearchResultsPacket(new ArrayList<SearchResultsPacket.Entry>()), player);
    }

    public void handleAddToPlaylist(EntityPlayerMP player, String videoId, String title, String duration) {
        if (player == null) {
            return;
        }
        if (isEmpty(videoId) || isEmpty(title)) {
            sendChat(player, EnumChatFormatting.RED, "Invalid playlist entry.");
            return;
        }
        if (!isSearchDurationAllowed(duration, configuredMaxTrackDurationMs())) {
            sendChat(
                player,
                EnumChatFormatting.YELLOW,
                "This song is too long for the server search limit and cannot be added.");
            return;
        }
        if (!state.add(new PlaylistEntry(videoId, title, safe(duration), player.getCommandSenderName()))) {
            return;
        }
        if (state.isShuffling()) {
            state.shuffleQueued();
        }

        if (!audioDownloadService.isDependenciesAvailable()) {
            sendChat(
                player,
                EnumChatFormatting.YELLOW,
                "Warning: yt-dlp or ffmpeg may not be installed on the server. Downloads may fail.");
        }
        LOGGER.info(player.getCommandSenderName() + " added " + title + " to the playlist");
        syncToAll();
        if (!state.isPlaying()) {
            playNext();
        }
    }

    public void handlePlayNow(EntityPlayerMP player, String videoId, String title, String duration) {
        if (player == null) {
            return;
        }
        if (isEmpty(videoId) || isEmpty(title)) {
            sendChat(player, EnumChatFormatting.RED, "Invalid playlist entry.");
            return;
        }
        if (!isSearchDurationAllowed(duration, configuredMaxTrackDurationMs())) {
            sendChat(
                player,
                EnumChatFormatting.YELLOW,
                "This song is too long for the server search limit and cannot be played now.");
            return;
        }

        int existingIndex = state.findIndex(videoId);
        PlaylistEntry requested = existingIndex >= 0 ? state.get(existingIndex)
            : new PlaylistEntry(videoId, title, safe(duration), player.getCommandSenderName());
        if (state.isSyncing()) {
            resumePausedClientsBeforeCurrentRemoval();
        }
        cancelFuture(advanceFuture);
        advanceFuture = null;
        state.prepareImmediatePlayback(requested);
        broadcastNowPlaying("", 0.0f);
        syncToAll();
        if (!audioDownloadService.isDependenciesAvailable()) {
            sendChat(
                player,
                EnumChatFormatting.YELLOW,
                "Warning: yt-dlp or ffmpeg may not be installed on the server. Downloads may fail.");
        }
        LOGGER.info(player.getCommandSenderName() + " selected " + requested.getTitle() + " for immediate playback");
        playNext(false, false);
    }

    public void handleAddChartsToPlaylist(final EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries,
        boolean remove) {
        if (player == null) {
            return;
        }
        if (entries == null || entries.isEmpty()) {
            sendChat(player, EnumChatFormatting.YELLOW, "No chart songs are available.");
            return;
        }
        if (remove) {
            removeChartEntries(player, entries);
            return;
        }
        final List<AddChartsToPlaylistPacket.Entry> requested = new ArrayList<AddChartsToPlaylistPacket.Entry>(entries);
        List<String> videoIds = new ArrayList<String>();
        for (AddChartsToPlaylistPacket.Entry entry : requested) {
            if (!isEmpty(entry.getVideoId()) && DurationParser.parseMillisStrict(entry.getDuration()) < 0L) {
                videoIds.add(entry.getVideoId());
            }
        }
        if (videoIds.isEmpty()) {
            finishAddChartsToPlaylist(player, requested, null);
            return;
        }
        audioDownloadService.extractVideoDurationOutput(videoIds)
            .whenComplete(new BiConsumer<String, Throwable>() {

                @Override
                public void accept(final String durationOutput, Throwable failure) {
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            finishAddChartsToPlaylist(player, requested, durationOutput);
                        }
                    });
                }
            });
    }

    private void finishAddChartsToPlaylist(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries,
        String durationOutput) {
        Map<String, String> durations = PlaylistImportService.parseDurationOutput(durationOutput);
        Set<String> knownVideoIds = new HashSet<String>();
        for (PlaylistEntry entry : state.snapshot()) {
            knownVideoIds.add(entry.getVideoId());
        }

        int added = 0;
        for (AddChartsToPlaylistPacket.Entry chart : entries) {
            String duration = isEmpty(chart.getDuration()) ? durations.get(chart.getVideoId()) : chart.getDuration();
            if (isEmpty(chart.getVideoId()) || isEmpty(chart.getTitle())
                || !isSearchDurationAllowed(duration, configuredMaxTrackDurationMs())
                || !knownVideoIds.add(chart.getVideoId())) {
                continue;
            }
            state.add(new PlaylistEntry(chart.getVideoId(), chart.getTitle(), duration, player.getCommandSenderName()));
            added++;
        }
        if (state.isShuffling() && added > 0) {
            state.shuffleQueued();
        }
        if (added > 0) {
            if (!audioDownloadService.isDependenciesAvailable()) {
                sendChat(
                    player,
                    EnumChatFormatting.YELLOW,
                    "Warning: yt-dlp or ffmpeg may not be installed on the server. Downloads may fail.");
            }
            syncToAll();
            if (!state.isPlaying()) {
                playNext();
            }
        }
        sendChat(
            player,
            added > 0 ? EnumChatFormatting.GREEN : EnumChatFormatting.YELLOW,
            "Added " + added + " chart songs to the playlist.");
    }

    public void handleRemoveFromPlaylist(EntityPlayerMP player, String videoId) {
        if (player == null || isEmpty(videoId)) {
            if (player != null) {
                sendChat(player, EnumChatFormatting.RED, "Invalid playlist entry.");
            }
            return;
        }

        String playerName = player.getCommandSenderName();
        int oldCurrentIndex = state.getCurrentIndex();
        int entryIndex = state.findIndex(videoId);
        if (entryIndex < 0) {
            LOGGER.warning(playerName + " tried to remove absent " + videoId);
            sendChat(player, EnumChatFormatting.YELLOW, "That song is no longer in the queue.");
            return;
        }
        if (entryIndex == oldCurrentIndex && state.isSyncing()) {
            resumePausedClientsBeforeCurrentRemoval();
        }
        int removeIndex = state.remove(videoId);
        if (removeIndex < 0) {
            LOGGER.warning(playerName + " tried to remove absent " + videoId);
            sendChat(player, EnumChatFormatting.YELLOW, "That song is no longer in the queue.");
            return;
        }

        audioDownloadService.delete(videoId);
        LOGGER.info(playerName + " removed " + videoId + " from the playlist");
        if (removeIndex == oldCurrentIndex) {
            cancelFuture(advanceFuture);
            broadcastNowPlaying("", 0.0f);
            playNext(false);
        }
        syncToAll();
    }

    public void handleClearPlaylist(EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        List<PlaylistEntry> entries = state.snapshot();
        if (entries.isEmpty()) {
            sendChat(player, EnumChatFormatting.YELLOW, "The queue is already empty.");
            return;
        }
        if (state.isSyncing()) {
            resumePausedClientsBeforeCurrentRemoval();
        }
        cancelFuture(advanceFuture);
        cancelFuture(syncTimeoutFuture);
        advanceFuture = null;
        syncTimeoutFuture = null;
        cancelActiveAudioDownload();
        for (String videoId : new HashSet<String>(preloadingAudio)) {
            audioDownloadService.cancelDownload(videoId);
        }
        preloadingAudio.clear();
        state.clear();
        for (PlaylistEntry entry : entries) {
            audioDownloadService.delete(entry.getVideoId());
        }
        broadcastNowPlaying("", 0.0f);
        broadcastLoopState(false);
        broadcastShuffleState(false);
        syncToAll();
        sendChat(player, EnumChatFormatting.GREEN, "Cleared " + entries.size() + " songs from the queue.");
    }

    public void handleReorder(EntityPlayerMP player, int fromIndex, int targetIndex) {
        if (player == null || !state.moveQueued(fromIndex, targetIndex)) {
            return;
        }
        LOGGER.info(player.getCommandSenderName() + " moved playlist entry from " + fromIndex + " to " + targetIndex);
        syncToAll();
    }

    public void handleSeek(EntityPlayerMP player, float progress) {
        if (player == null || Float.isNaN(progress)
            || Float.isInfinite(progress)
            || state.isSyncing()
            || !state.isPlaying()) {
            return;
        }

        int currentIndex = state.getCurrentIndex();
        if (currentIndex < 0 || currentIndex >= state.size()) {
            return;
        }

        float safeProgress = Math.max(0.0f, Math.min(1.0f, progress));
        long durationMs = state.getCurrentTrackDurationMs();
        long requestedPositionMs = (long) (durationMs * safeProgress);
        long now = System.currentTimeMillis();
        long positionMs = state.seek(requestedPositionMs, now);
        if (positionMs < 0L) {
            return;
        }

        boolean wasPaused = state.isPaused();
        cancelFuture(advanceFuture);
        PausePacket pausePacket = new PausePacket(positionMs);
        for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(pausePacket, onlinePlayer);
        }
        if (!wasPaused) {
            ResumePacket resumePacket = new ResumePacket(positionMs);
            for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
                HorizonRadioNetwork.CHANNEL.sendTo(resumePacket, onlinePlayer);
            }
        }

        PlaylistEntry entry = state.get(currentIndex);
        broadcastNowPlaying(entry.getTitle(), progressFor(positionMs, durationMs));
        if (!wasPaused) {
            scheduleNextTrack(positionMs, durationMs);
        }
    }

    public void handleTogglePlayback(EntityPlayerMP player) {
        if (player == null || state.isSyncing() || !state.isPlaying()) {
            return;
        }

        int currentIndex = state.getCurrentIndex();
        if (currentIndex < 0 || currentIndex >= state.size()) {
            return;
        }

        long now = System.currentTimeMillis();
        long durationMs = state.getCurrentTrackDurationMs();
        long positionMs;
        if (state.isPaused()) {
            positionMs = state.resumePlayback(now);
            if (positionMs < 0L) {
                return;
            }
            ResumePacket resumePacket = new ResumePacket(positionMs);
            for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
                HorizonRadioNetwork.CHANNEL.sendTo(resumePacket, onlinePlayer);
            }
            scheduleNextTrack(positionMs, durationMs);
        } else {
            positionMs = currentPositionMs(now, durationMs);
            positionMs = state.pausePlayback(positionMs, now);
            if (positionMs < 0L) {
                return;
            }
            cancelFuture(advanceFuture);
            PausePacket pausePacket = new PausePacket(positionMs);
            for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
                HorizonRadioNetwork.CHANNEL.sendTo(pausePacket, onlinePlayer);
            }
        }

        PlaylistEntry entry = state.get(currentIndex);
        broadcastNowPlaying(entry.getTitle(), progressFor(positionMs, durationMs));
    }

    public void handleSkipTrack(EntityPlayerMP player) {
        if (player == null || state.isSyncing() || !state.isPlaying()) {
            return;
        }
        cancelFuture(advanceFuture);
        advanceFuture = null;
        LOGGER.info(player.getCommandSenderName() + " skipped the current track");
        playNext(true);
    }

    public void handleToggleLoop(EntityPlayerMP player) {
        if (player == null || state.isSyncing()) {
            return;
        }
        boolean looping = state.toggleLooping();
        broadcastLoopState(looping);
        LOGGER.info(player.getCommandSenderName() + " " + (looping ? "enabled" : "disabled") + " repeat-one mode");
    }

    public void handleToggleShuffle(EntityPlayerMP player) {
        if (player == null || state.isSyncing()) {
            return;
        }
        boolean shuffling = state.toggleShuffling();
        if (shuffling) {
            state.shuffleQueued();
            preloadAdjacentTracks();
        }
        broadcastShuffleState(shuffling);
        syncToAll();
        LOGGER.info(player.getCommandSenderName() + " " + (shuffling ? "enabled" : "disabled") + " shuffle mode");
    }

    public void handlePreviousTrack(EntityPlayerMP player) {
        if (player == null || state.isSyncing() || !state.isPlaying()) {
            return;
        }

        long now = System.currentTimeMillis();
        long durationMs = state.getCurrentTrackDurationMs();
        long positionMs = state.isPaused() ? state.getPausedPositionMs() : currentPositionMs(now, durationMs);
        if (!state.wasPreviousRestarted() || positionMs > 10000L) {
            state.markPreviousRestarted();
            handleSeek(player, 0.0f);
            return;
        }

        PlaylistEntry previous = state.takeLastTrack();
        if (previous == null) {
            handleSeek(player, 0.0f);
            return;
        }

        cancelFuture(advanceFuture);
        advanceFuture = null;
        broadcastNowPlaying("", 0.0f);
        state.addAtFront(previous);
        state.resetPlayback();
        syncToAll();
        LOGGER.info(player.getCommandSenderName() + " returned to the previous track");
        playNext(false, false);
    }

    public void syncToPlayer(final EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        HorizonRadioNetwork.CHANNEL.sendTo(new ShuffleStatePacket(state.isShuffling()), player);
        HorizonRadioNetwork.CHANNEL.sendTo(new LoopStatePacket(state.isLooping()), player);
        HorizonRadioNetwork.CHANNEL.sendTo(new PlaylistSyncPacket(toPacketEntries(state.snapshot())), player);

        int currentIndex = state.getCurrentIndex();
        if (!state.isPlaying() || currentIndex < 0 || currentIndex >= state.size()) {
            return;
        }

        final PlaylistEntry entry = state.get(currentIndex);
        long now = System.currentTimeMillis();
        long elapsed = state.isSyncing() || state.isPaused() ? state.getPausedPositionMs()
            : Math.max(0L, now - state.getPlaybackStartTime());
        float progress = progressFor(elapsed, state.getCurrentTrackDurationMs());
        HorizonRadioNetwork.CHANNEL.sendTo(new NowPlayingPacket(entry.getTitle(), progress), player);

        if (state.isPaused()) {
            HorizonRadioNetwork.CHANNEL.sendTo(new PausePacket(state.getPausedPositionMs()), player);
            return;
        }

        final String videoId = state.getCurrentVideoId();
        if (isEmpty(videoId)) {
            return;
        }

        final UUID playerUuid = player.getUniqueID();
        boolean firstJoiner = state.beginLateJoin(playerUuid, elapsed, now);
        if (firstJoiner) {
            cancelFuture(advanceFuture);
            PausePacket pausePacket = new PausePacket(state.getPausedPositionMs());
            for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
                HorizonRadioNetwork.CHANNEL.sendTo(pausePacket, onlinePlayer);
            }
            LOGGER.info("Paused all clients at " + state.getPausedPositionMs() + "ms for late-join synchronization");
        } else if (state.containsPending(playerUuid)) {
            HorizonRadioNetwork.CHANNEL.sendTo(new PausePacket(state.getPausedPositionMs()), player);
        }

        scheduleSyncTimeout();
        requestLateJoinAudio(player, playerUuid, entry, videoId);
    }

    public void onPlayerReady(EntityPlayerMP player, String videoId) {
        if (player == null || isEmpty(videoId)) {
            return;
        }
        if (state.ready(player.getUniqueID(), videoId)) {
            LOGGER.info("Player " + player.getCommandSenderName() + " is ready for " + videoId);
            doResume();
        }
    }

    public void handleDisconnect(EntityPlayerMP player) {
        if (player == null || !state.isSyncing()) {
            return;
        }
        if (state.disconnect(player.getUniqueID())) {
            LOGGER.info(
                "Syncing player " + player.getCommandSenderName()
                    + " disconnected; "
                    + state.getPendingPlayerCount()
                    + " pending");
            if (!state.hasPendingPlayers()) {
                doResume();
            }
        }
    }

    private void resumePausedClientsBeforeCurrentRemoval() {
        if (!state.isSyncing()) {
            return;
        }
        long pausedPositionMs = state.getPausedPositionMs();
        state.forceResume();
        cancelFuture(syncTimeoutFuture);
        syncTimeoutFuture = null;

        ResumePacket resumePacket = new ResumePacket(pausedPositionMs);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(resumePacket, player);
        }
        LOGGER.info("Resumed all clients at " + pausedPositionMs + "ms before removing the current track");
    }

    private void playNext() {
        if (state.isLooping()) {
            replayCurrent();
            return;
        }
        playNext(true);
    }

    private void replayCurrent() {
        int currentIndex = state.getCurrentIndex();
        if (currentIndex < 0 || currentIndex >= state.size()) {
            playNext(true);
            return;
        }
        cancelFuture(advanceFuture);
        advanceFuture = null;
        broadcastNowPlaying("", 0.0f);
        state.resetPlayback();
        syncToAll();
        playNext(false, false);
    }

    private void playNext(boolean removeCurrentFromQueue) {
        playNext(removeCurrentFromQueue, true);
    }

    private void playNext(boolean removeCurrentFromQueue, boolean shuffleQueue) {
        cancelActiveAudioDownload();
        PlaylistEntry previousLastTrack = state.peekLastTrack();
        boolean queueChanged = false;
        if (removeCurrentFromQueue) {
            PlaylistEntry removed = state.removeCurrent();
            if (removed != null) {
                // Keep the just-finished file available for the Previous button.
                // The older last-track file is cleaned up below on the next transition.
                if (previousLastTrack != null && !previousLastTrack.getVideoId()
                    .equals(removed.getVideoId())) {
                    audioDownloadService.delete(previousLastTrack.getVideoId());
                }
                broadcastNowPlaying("", 0.0f);
                queueChanged = true;
            }
        }

        if (shuffleQueue && state.isShuffling()) {
            state.shuffleQueued();
            queueChanged = true;
        }
        if (queueChanged) {
            syncToAll();
        }

        int nextIndex = state.getCurrentIndex() + 1;
        PlaylistEntry entry = null;
        if (nextIndex >= 0 && nextIndex < state.size()) {
            PlaylistEntry nextCandidate = state.get(nextIndex);
            cancelPreloadsExcept(
                nextCandidate.getVideoId(),
                state.peekLastTrack() == null ? null
                    : state.peekLastTrack()
                        .getVideoId());
            entry = state.advanceToNext(
                parseDuration(
                    state.get(nextIndex)
                        .getDuration()));
        } else {
            state.advanceToNext(DEFAULT_TRACK_DURATION_MS);
        }

        if (entry == null) {
            state.resetPlayback();
            broadcastNowPlaying("", 0.0f);
            return;
        }

        final PlaylistEntry selectedEntry = entry;
        final int selectedIndex = state.getCurrentIndex();
        preloadAdjacentTracks();
        LOGGER.info("Requesting download for: " + selectedEntry.getTitle() + " (" + selectedEntry.getVideoId() + ")");
        final CompletableFuture<Path> downloadFuture = audioDownloadService.download(selectedEntry.getVideoId());
        activeAudioDownload = downloadFuture;
        activeAudioVideoId = selectedEntry.getVideoId();
        downloadFuture.whenComplete(new BiConsumer<Path, Throwable>() {

            @Override
            public void accept(final Path filePath, Throwable downloadFailure) {
                if (activeAudioDownload == downloadFuture) {
                    activeAudioDownload = null;
                    activeAudioVideoId = null;
                }
                if (downloadFailure != null) {
                    LOGGER
                        .log(Level.WARNING, "Audio download failed for " + selectedEntry.getVideoId(), downloadFailure);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            downloadFailed(selectedEntry, selectedIndex);
                        }
                    });
                    return;
                }

                try {
                    if (filePath == null || !Files.isRegularFile(filePath)) {
                        enqueueServerTask(new Runnable() {

                            @Override
                            public void run() {
                                downloadFailed(selectedEntry, selectedIndex);
                            }
                        });
                        return;
                    }

                    final long audioFileSize = Files.size(filePath);
                    if (!supportsAudioLength(audioFileSize)) {
                        enqueueServerTask(new Runnable() {

                            @Override
                            public void run() {
                                audioTooLarge(selectedEntry, selectedIndex, audioFileSize);
                            }
                        });
                        return;
                    }
                    final byte[] audioBytes = readAudioBytes(filePath);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            downloadedAudioReady(selectedEntry, selectedIndex, audioBytes);
                        }
                    });
                } catch (IOException exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Failed to read downloaded audio for " + selectedEntry.getVideoId(),
                        exception);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            downloadFailed(selectedEntry, selectedIndex);
                        }
                    });
                    return;
                } catch (RuntimeException exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Failed to validate or read downloaded audio for " + selectedEntry.getVideoId(),
                        exception);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            downloadFailed(selectedEntry, selectedIndex);
                        }
                    });
                    return;
                }
            }
        });
    }

    private void preloadAdjacentTracks() {
        int nextIndex = state.getCurrentIndex() + 1;
        if (nextIndex >= 0 && nextIndex < state.size()) {
            preloadAudio(state.get(nextIndex));
        }
        preloadAudio(state.peekLastTrack());
    }

    private void preloadAudio(PlaylistEntry entry) {
        if (entry == null || isEmpty(entry.getVideoId()) || !preloadingAudio.add(entry.getVideoId())) {
            return;
        }
        audioDownloadService.download(entry.getVideoId())
            .whenComplete(new BiConsumer<Path, Throwable>() {

                @Override
                public void accept(Path path, Throwable failure) {
                    preloadingAudio.remove(entry.getVideoId());
                    if (failure != null) {
                        LOGGER.log(Level.FINE, "Preloading failed for " + entry.getVideoId(), failure);
                    }
                }
            });
    }

    private void cancelActiveAudioDownload() {
        if (activeAudioVideoId != null) {
            audioDownloadService.cancelDownload(activeAudioVideoId);
        }
        activeAudioDownload = null;
        activeAudioVideoId = null;
    }

    private void cancelPreloadsExcept(String keepFirst, String keepSecond) {
        for (String videoId : new HashSet<String>(preloadingAudio)) {
            if (!videoId.equals(keepFirst) && !videoId.equals(keepSecond)) {
                audioDownloadService.cancelDownload(videoId);
                preloadingAudio.remove(videoId);
            }
        }
    }

    private void removeChartEntries(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries) {
        String currentVideoId = state.getCurrentVideoId();
        boolean removingCurrent = false;
        List<String> removedIds = new ArrayList<String>();
        Set<String> requestedIds = new HashSet<String>();
        for (AddChartsToPlaylistPacket.Entry chart : entries) {
            if (isEmpty(chart.getVideoId()) || !requestedIds.add(chart.getVideoId())) {
                continue;
            }
            if (state.findIndex(chart.getVideoId()) < 0) {
                continue;
            }
            if (chart.getVideoId()
                .equals(currentVideoId)) {
                removingCurrent = true;
                if (state.isSyncing()) {
                    resumePausedClientsBeforeCurrentRemoval();
                }
            }
            if (state.remove(chart.getVideoId()) >= 0) {
                removedIds.add(chart.getVideoId());
            }
        }
        for (String videoId : removedIds) {
            audioDownloadService.delete(videoId);
        }
        if (removingCurrent) {
            cancelFuture(advanceFuture);
            broadcastNowPlaying("", 0.0f);
            playNext(false);
        }
        if (!removedIds.isEmpty()) {
            syncToAll();
        }
        sendChat(
            player,
            removedIds.isEmpty() ? EnumChatFormatting.YELLOW : EnumChatFormatting.GREEN,
            "Removed " + removedIds.size() + " chart songs from the playlist.");
    }

    private void downloadFailed(PlaylistEntry entry, int index) {
        if (!isCurrentEntry(entry, index)) {
            return;
        }
        LOGGER.warning("Download failed for: " + entry.getTitle());
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            sendChat(
                player,
                EnumChatFormatting.RED,
                "Failed to download: " + entry.getTitle() + " - Check server logs for details");
        }
        playNext(true);
    }

    private void audioTooLarge(PlaylistEntry entry, int index, long audioLength) {
        if (!isCurrentEntry(entry, index)) {
            return;
        }
        LOGGER.warning(
            "Audio file is too large for packet transfer for " + entry
                .getVideoId() + " (" + audioLength + " bytes; maximum " + maxAudioBytes() + ")");
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            sendChat(player, EnumChatFormatting.RED, "Audio is too large to play: " + entry.getTitle() + " - skipped.");
        }
        playNext(true);
    }

    private void downloadedAudioReady(PlaylistEntry entry, int index, byte[] audioBytes) {
        if (!isCurrentEntry(entry, index)) {
            return;
        }
        if (audioBytes == null || audioBytes.length == 0) {
            LOGGER.warning("Downloaded audio was empty for: " + entry.getVideoId());
            playNext(true);
            return;
        }

        state.markLoaded(entry.getVideoId(), System.currentTimeMillis());
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            if (!sendAudioChunks(player, entry.getVideoId(), entry.getTitle(), audioBytes, 0L)) {
                audioTooLarge(entry, index, audioBytes.length);
                return;
            }
        }
        broadcastNowPlaying(entry.getTitle(), 0.0f);

        cancelFuture(advanceFuture);
        long delay = state.getCurrentTrackDurationMs() + NEXT_TRACK_DELAY_MS;
        advanceFuture = scheduleServerTask(new Runnable() {

            @Override
            public void run() {
                playNext();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void requestLateJoinAudio(final EntityPlayerMP player, final UUID playerUuid, final PlaylistEntry entry,
        final String videoId) {
        scheduler.submit(new Runnable() {

            @Override
            public void run() {
                try {
                    Path filePath = audioDownloadService.getFilePath(videoId);
                    if (filePath == null || !Files.isRegularFile(filePath)) {
                        enqueueLateJoinFailure(playerUuid, videoId);
                        return;
                    }
                    final byte[] audioBytes = readAudioBytes(filePath);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            if (!state.isSyncing() || !videoId.equals(state.getCurrentVideoId())
                                || !state.containsPending(playerUuid)) {
                                return;
                            }
                            if (audioBytes.length == 0) {
                                removeLateJoinPending(playerUuid, videoId);
                                return;
                            }
                            if (!sendAudioChunks(player, videoId, entry.getTitle(), audioBytes, -1L)) {
                                LOGGER.warning("Audio file is too large for late-join transfer: " + videoId);
                                removeLateJoinPending(playerUuid, videoId);
                            }
                        }
                    });
                } catch (IOException exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Failed to read audio file for late-join resend (" + videoId + ")",
                        exception);
                    enqueueLateJoinFailure(playerUuid, videoId);
                } catch (RuntimeException exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Failed to locate or read audio file for late-join resend (" + videoId + ")",
                        exception);
                    enqueueLateJoinFailure(playerUuid, videoId);
                }
            }
        });
    }

    private void enqueueLateJoinFailure(final UUID playerUuid, final String videoId) {
        enqueueServerTask(new Runnable() {

            @Override
            public void run() {
                removeLateJoinPending(playerUuid, videoId);
            }
        });
    }

    private void removeLateJoinPending(UUID playerUuid, String videoId) {
        if (!state.isSyncing() || !videoId.equals(state.getCurrentVideoId())) {
            return;
        }
        if (state.removePending(playerUuid) && !state.hasPendingPlayers()) {
            doResume();
        }
    }

    private void scheduleSyncTimeout() {
        if (syncTimeoutFuture != null) {
            return;
        }
        syncTimeoutFuture = scheduleServerTask(new Runnable() {

            @Override
            public void run() {
                if (state.isSyncing()) {
                    LOGGER.warning(
                        "Late-join synchronization timed out with " + state.getPendingPlayerCount()
                            + " pending players");
                    doResume();
                }
            }
        }, LATE_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void doResume() {
        if (!state.isSyncing()) {
            return;
        }
        long pausedPositionMs = state.getPausedPositionMs();
        long now = System.currentTimeMillis();
        long pauseDuration = Math.max(0L, now - state.getPauseStartTime());
        state.resume(now);
        cancelFuture(syncTimeoutFuture);
        syncTimeoutFuture = null;

        ResumePacket resumePacket = new ResumePacket(pausedPositionMs);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(resumePacket, player);
        }
        LOGGER
            .info("Resuming all clients at " + pausedPositionMs + "ms after a " + pauseDuration + "ms late-join pause");

        cancelFuture(advanceFuture);
        long remaining = state.getCurrentTrackDurationMs() - pausedPositionMs + NEXT_TRACK_DELAY_MS;
        if (remaining > 0L && state.isPlaying()) {
            advanceFuture = scheduleServerTask(new Runnable() {

                @Override
                public void run() {
                    playNext();
                }
            }, remaining, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleNextTrack(long positionMs, long durationMs) {
        cancelFuture(advanceFuture);
        long remaining = durationMs - positionMs + NEXT_TRACK_DELAY_MS;
        if (remaining > 0L && state.isPlaying() && !state.isPaused()) {
            advanceFuture = scheduleServerTask(new Runnable() {

                @Override
                public void run() {
                    playNext();
                }
            }, remaining, TimeUnit.MILLISECONDS);
        }
    }

    private long currentPositionMs(long nowMs, long durationMs) {
        long elapsed = Math.max(0L, nowMs - state.getPlaybackStartTime());
        return Math.min(Math.max(0L, durationMs - 1L), elapsed);
    }

    private void broadcastProgress() {
        int currentIndex = state.getCurrentIndex();
        if (!state.isPlaying() || state.isPaused()
            || state.isSyncing()
            || currentIndex < 0
            || currentIndex >= state.size()
            || isEmpty(state.getCurrentVideoId())) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - state.getPlaybackStartTime());
        PlaylistEntry entry = state.get(currentIndex);
        broadcastNowPlaying(entry.getTitle(), progressFor(elapsed, state.getCurrentTrackDurationMs()));
    }

    private boolean isCurrentEntry(PlaylistEntry entry, int index) {
        return entry != null && state.isPlaying()
            && state.getCurrentIndex() == index
            && index >= 0
            && index < state.size()
            && entry.getVideoId()
                .equals(
                    state.get(index)
                        .getVideoId());
    }

    private boolean sendAudioChunks(EntityPlayerMP player, String videoId, String title, byte[] audioBytes,
        long startOffsetMs) {
        if (player == null || audioBytes == null || audioBytes.length == 0) {
            return false;
        }
        int chunkSize = AudioChunkPacket.CHUNK_SIZE;
        long totalChunksLong = ((long) audioBytes.length - 1L) / chunkSize + 1L;
        if (totalChunksLong > AudioChunkPacket.MAX_CHUNKS) {
            return false;
        }
        int totalChunks = (int) totalChunksLong;
        for (int index = 0; index < totalChunks; index++) {
            int start = index * chunkSize;
            int end = Math.min(start + chunkSize, audioBytes.length);
            byte[] chunk = Arrays.copyOfRange(audioBytes, start, end);
            HorizonRadioNetwork.CHANNEL
                .sendTo(new AudioChunkPacket(videoId, title, index, totalChunks, startOffsetMs, chunk), player);
        }
        return true;
    }

    private void syncToAll() {
        PlaylistSyncPacket packet = new PlaylistSyncPacket(toPacketEntries(state.snapshot()));
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private void broadcastNowPlaying(String title, float progress) {
        NowPlayingPacket packet = new NowPlayingPacket(title, progress);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private void broadcastLoopState(boolean looping) {
        LoopStatePacket packet = new LoopStatePacket(looping);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private void broadcastShuffleState(boolean shuffling) {
        ShuffleStatePacket packet = new ShuffleStatePacket(shuffling);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private List<PlaylistSyncPacket.Entry> toPacketEntries(List<PlaylistEntry> entries) {
        List<PlaylistSyncPacket.Entry> packetEntries = new ArrayList<PlaylistSyncPacket.Entry>();
        for (PlaylistEntry entry : entries) {
            packetEntries.add(
                new PlaylistSyncPacket.Entry(
                    entry.getVideoId(),
                    entry.getTitle(),
                    entry.getDuration(),
                    entry.getAddedBy()));
        }
        return packetEntries;
    }

    private List<EntityPlayerMP> onlinePlayersSnapshot() {
        List<EntityPlayerMP> players = new ArrayList<EntityPlayerMP>();
        for (Object candidate : server.getConfigurationManager().playerEntityList) {
            if (candidate instanceof EntityPlayerMP) {
                players.add((EntityPlayerMP) candidate);
            }
        }
        return players;
    }

    private ScheduledFuture<?> scheduleServerTask(final Runnable task, long delay, TimeUnit unit) {
        return scheduler.schedule(new Runnable() {

            @Override
            public void run() {
                enqueueServerTask(task);
            }
        }, delay, unit);
    }

    private void enqueueServerTask(final Runnable task) {
        ServerThreadExecutor.execute(server, new Runnable() {

            @Override
            public void run() {
                if (!shuttingDown) {
                    task.run();
                }
            }
        });
    }

    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private void sendChat(EntityPlayerMP player, EnumChatFormatting color, String message) {
        player.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "[HorizonRadio] " + color + message));
    }

    private static int configuredMaxPlaylistSize() {
        HorizonRadioConfig config = HorizonRadio.getConfig();
        return config == null ? HorizonRadioConfig.DEFAULT_MAX_PLAYLIST_SIZE : config.getMaxPlaylistSize();
    }

    private static long configuredMaxTrackDurationMs() {
        HorizonRadioConfig config = HorizonRadio.getConfig();
        int minutes = config == null ? HorizonRadioConfig.DEFAULT_MAX_TRACK_DURATION_MINUTES
            : config.getMaxTrackDurationMinutes();
        return minutes * 60L * 1000L;
    }

    private static long maxAudioBytes() {
        return (long) AudioChunkPacket.CHUNK_SIZE * AudioChunkPacket.MAX_CHUNKS;
    }

    public static boolean supportsAudioLength(long audioLength) {
        return audioLength > 0L
            && ((audioLength - 1L) / AudioChunkPacket.CHUNK_SIZE + 1L) <= AudioChunkPacket.MAX_CHUNKS;
    }

    public static boolean isSearchDurationAllowed(String duration, long maxDurationMs) {
        long durationMs = DurationParser.parseMillisStrict(duration);
        return durationMs >= 0L && durationMs < maxDurationMs;
    }

    /** Java 8 replacement for the newer read-all-bytes convenience APIs. */
    private static byte[] readAudioBytes(Path filePath) throws IOException {
        InputStream input = Files.newInputStream(filePath);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static float progressFor(long elapsedMs, long durationMs) {
        if (durationMs <= 0L) {
            return 0.0f;
        }
        return Math.min(1.0f, Math.max(0.0f, (float) elapsedMs / (float) durationMs));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim()
            .length() == 0;
    }

    /** Parses values such as {@code 3:45} and {@code 1:02:30}. */
    static long parseDuration(String duration) {
        return PlaylistState.parseDuration(duration);
    }

    public void shutdown() {
        shuttingDown = true;
        cancelActiveAudioDownload();
        for (String videoId : new HashSet<String>(preloadingAudio)) {
            audioDownloadService.cancelDownload(videoId);
        }
        preloadingAudio.clear();
        cancelFuture(advanceFuture);
        cancelFuture(progressFuture);
        cancelFuture(syncTimeoutFuture);
        scheduler.shutdownNow();
        state.clear();
        LOGGER.info("HorizonRadio: Playlist manager shut down");
    }
}
