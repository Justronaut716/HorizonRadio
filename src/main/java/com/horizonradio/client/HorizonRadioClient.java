package com.horizonradio.client;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import com.horizonradio.HorizonRadio;
import com.horizonradio.client.audio.AudioPlayer;
import com.horizonradio.client.audio.ClientRadioPlayback;
import com.horizonradio.client.audio.PlaybackClock;
import com.horizonradio.client.media.ClientMediaService;
import com.horizonradio.client.media.ClientMetadataCache;
import com.horizonradio.core.client.ClientLocalPlaylistState;
import com.horizonradio.core.client.ClientQueueState;
import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.core.model.DurationParser;
import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.core.server.ChartRegionCatalog;
import com.horizonradio.core.server.PlaylistImportService;
import com.horizonradio.network.HorizonRadioNetwork;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket.Entry;
import com.horizonradio.network.packets.AddToPlaylistPacket;
import com.horizonradio.network.packets.ClearPlaylistPacket;
import com.horizonradio.network.packets.ClockSyncRequestPacket;
import com.horizonradio.network.packets.ClockSyncResponsePacket;
import com.horizonradio.network.packets.PlayNowPacket;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistResyncRequestPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.PreviousTrackPacket;
import com.horizonradio.network.packets.RemoveFromPlaylistPacket;
import com.horizonradio.network.packets.ReorderPlaylistPacket;
import com.horizonradio.network.packets.SeekRequestPacket;
import com.horizonradio.network.packets.SelectRadioStationPacket;
import com.horizonradio.network.packets.SkipTrackPacket;
import com.horizonradio.network.packets.StopRadioPacket;
import com.horizonradio.network.packets.ToggleLoopPacket;
import com.horizonradio.network.packets.TogglePlaybackPacket;
import com.horizonradio.network.packets.ToggleShufflePacket;
import com.horizonradio.network.packets.TrackSyncPacket;
import com.horizonradio.server.AudioDownloadService;

/** Client-side state boundary used by the GUI and the future Forge transport. */
public final class HorizonRadioClient {

    private static final List<HorizonRadioScreen.PlaylistEntry> CACHED_PLAYLIST = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
    private static final List<HorizonRadioScreen.SearchResult> CACHED_CHARTS = new ArrayList<HorizonRadioScreen.SearchResult>();
    private static final List<HorizonRadioScreen.SearchResult> CACHED_PLAYLIST_RESULTS = new ArrayList<HorizonRadioScreen.SearchResult>();
    private static final List<RadioStation> CACHED_RADIO_RESULTS = new ArrayList<RadioStation>();
    private static final long CHART_CACHE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static String cachedNowPlaying;
    private static float cachedProgress;
    private static boolean cachedPaused;
    private static boolean cachedLooping;
    private static boolean cachedShuffling;
    private static boolean cachedRadioActive;
    private static ClientRadioPresentation cachedRadioPresentation;
    private static long cachedChartsAt;
    private static boolean chartRequestPending;
    private static String cachedChartRegionCode = "";
    private static String pendingChartRegionCode = "";
    private static String lastRequestedChartRegionCode;
    private static HorizonRadioScreen chartRequestScreen;
    private static ClientTransport transport = new NoopClientTransport();
    private static HorizonRadioClientConfig clientConfig;
    private static ClientFavorites clientFavorites = new ClientFavorites();
    private static AudioDownloadService clientAudioDownloadService;
    private static ClientMediaService clientMediaService;
    private static ClientMetadataCache clientMetadataCache;
    private static final Set<String> requestedVideoMetadata = new HashSet<String>();
    private static final Set<String> requestedStationMetadata = new HashSet<String>();
    private static final ClientQueueState CLIENT_QUEUE = new ClientQueueState();
    private static final ClientLocalPlaylistState LOCAL_QUEUE = new ClientLocalPlaylistState(
        HorizonRadioConfig.DEFAULT_MAX_PLAYLIST_SIZE);
    private static PlaybackMode playbackMode = PlaybackMode.SERVER;
    private static long localPlaybackGeneration;
    private static boolean playlistResyncRequested;
    private static boolean pendingAddResolutionResyncRequested;
    private static final List<PendingAddResolution> pendingAddResolutions = new ArrayList<PendingAddResolution>();
    private static long searchTabDiscoveryGeneration;
    private static long chartGeneration;
    private static long playlistImportGeneration;
    private static long radioSearchGeneration;
    private static HorizonRadioScreen playlistImportScreen;
    private static MediaSourceType activeTrackSourceType;
    private static String activeTrackSourceId;
    private static String activeTrackVideoId;
    private static long activeTrackGeneration = -1L;
    private static long activeTrackPositionMs;
    private static long activeTrackStartAtMs;
    private static long activeTrackDurationMs;
    private static long serverClockOffsetMs;
    private static ClientRadioPlayback clientRadioPlayback;

    private HorizonRadioClient() {}

    public interface ClientTransport {

        /** Compatibility transport seam; new callers use the ID/duration overload. */
        void sendAdd(String videoId, String title, String duration);

        default void sendAdd(String videoId, long durationMs) {
            sendAdd(videoId, videoId, formatDuration(durationMs));
        }

        /** Compatibility transport seam; new callers use the ID/duration overload. */
        void sendPlayNow(String videoId, String title, String duration);

        default void sendPlayNow(String videoId, long durationMs) {
            sendPlayNow(videoId, videoId, formatDuration(durationMs));
        }

        void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results);

        default void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results, boolean remove) {
            sendAddChartsToPlaylist(results);
        }

        default void sendAddChartSelections(List<PlaylistSelection> selections, boolean remove) {
            List<HorizonRadioScreen.SearchResult> legacy = new ArrayList<HorizonRadioScreen.SearchResult>();
            if (selections != null) {
                for (PlaylistSelection selection : selections) {
                    legacy.add(
                        new HorizonRadioScreen.SearchResult(
                            selection.videoId,
                            selection.videoId,
                            "",
                            formatDuration(selection.durationMs),
                            ""));
                }
            }
            sendAddChartsToPlaylist(legacy, remove);
        }

        default void sendPlaylistResync(long knownRevision) {}

        void sendRemove(String videoId);

        void sendClearPlaylist();

        void sendReorder(int fromIndex, int targetIndex);

        void sendSeek(float progress);

        void sendTogglePlayback();

        void sendSkipTrack();

        void sendPreviousTrack();

        void sendToggleLoop();

        void sendToggleShuffle();

        void sendSelectRadio(String stationUuid);

        void sendStopRadio();

        default void sendClockSync(long clientSentAtMs) {}
    }

    /** Forge transport for registered client-to-server protocol messages. */
    public static final class ForgeClientTransport implements ClientTransport {

        @Override
        public void sendAdd(String videoId, String title, String duration) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new AddToPlaylistPacket(videoId, durationMillis(duration)));
        }

        @Override
        public void sendAdd(String videoId, long durationMs) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new AddToPlaylistPacket(videoId, durationMs));
        }

        @Override
        public void sendPlayNow(String videoId, String title, String duration) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new PlayNowPacket(videoId, durationMillis(duration)));
        }

        @Override
        public void sendPlayNow(String videoId, long durationMs) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new PlayNowPacket(videoId, durationMs));
        }

        @Override
        public void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results) {
            sendAddChartsToPlaylist(results, false);
        }

        @Override
        public void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results, boolean remove) {
            List<Entry> entries = new ArrayList<Entry>();
            if (results != null) {
                for (HorizonRadioScreen.SearchResult result : results) {
                    entries.add(new Entry(result.videoId, result.title, result.duration));
                }
            }
            StringBuilder packetIds = new StringBuilder();
            for (Entry entry : entries) {
                if (packetIds.length() > 0) {
                    packetIds.append(',');
                }
                packetIds.append(entry.getVideoId());
            }
            debugChat(
                "Sende AddChartsToPlaylistPacket remove=" + remove
                    + " ids="
                    + packetIds
                    + " entries="
                    + entries.size());
            HorizonRadioNetwork.CHANNEL.sendToServer(new AddChartsToPlaylistPacket(entries, remove));
        }

        @Override
        public void sendAddChartSelections(List<PlaylistSelection> selections, boolean remove) {
            List<Entry> entries = new ArrayList<Entry>();
            if (selections != null) {
                for (PlaylistSelection selection : selections) {
                    entries.add(new Entry(selection.videoId, selection.durationMs));
                }
            }
            StringBuilder packetIds = new StringBuilder();
            for (Entry entry : entries) {
                if (packetIds.length() > 0) {
                    packetIds.append(',');
                }
                packetIds.append(entry.getVideoId());
            }
            debugChat(
                "Sende kompaktes AddChartsToPlaylistPacket remove=" + remove
                    + " ids="
                    + packetIds
                    + " entries="
                    + entries.size());
            HorizonRadioNetwork.CHANNEL.sendToServer(new AddChartsToPlaylistPacket(entries, remove));
        }

        @Override
        public void sendPlaylistResync(long knownRevision) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new PlaylistResyncRequestPacket(knownRevision));
        }

        @Override
        public void sendRemove(String videoId) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new RemoveFromPlaylistPacket(videoId));
        }

        @Override
        public void sendClearPlaylist() {
            HorizonRadioNetwork.CHANNEL.sendToServer(new ClearPlaylistPacket());
        }

        @Override
        public void sendReorder(int fromIndex, int targetIndex) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new ReorderPlaylistPacket(fromIndex, targetIndex));
        }

        @Override
        public void sendSeek(float progress) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new SeekRequestPacket(progress));
        }

        @Override
        public void sendTogglePlayback() {
            HorizonRadioNetwork.CHANNEL.sendToServer(new TogglePlaybackPacket());
        }

        @Override
        public void sendSkipTrack() {
            HorizonRadioNetwork.CHANNEL.sendToServer(new SkipTrackPacket());
        }

        @Override
        public void sendPreviousTrack() {
            HorizonRadioNetwork.CHANNEL.sendToServer(new PreviousTrackPacket());
        }

        @Override
        public void sendToggleLoop() {
            HorizonRadioNetwork.CHANNEL.sendToServer(new ToggleLoopPacket());
        }

        @Override
        public void sendToggleShuffle() {
            HorizonRadioNetwork.CHANNEL.sendToServer(new ToggleShufflePacket());
        }

        @Override
        public void sendSelectRadio(String stationUuid) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new SelectRadioStationPacket(stationUuid));
        }

        @Override
        public void sendStopRadio() {
            HorizonRadioNetwork.CHANNEL.sendToServer(new StopRadioPacket());
        }

        @Override
        public void sendClockSync(long clientSentAtMs) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new ClockSyncRequestPacket(clientSentAtMs));
        }
    }

    /** No-op transport retained for common tests and before client initialization. */
    public static final class NoopClientTransport implements ClientTransport {

        @Override
        public void sendAdd(String videoId, String title, String duration) {}

        @Override
        public void sendPlayNow(String videoId, String title, String duration) {}

        @Override
        public void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results) {}

        @Override
        public void sendRemove(String videoId) {}

        @Override
        public void sendClearPlaylist() {}

        @Override
        public void sendReorder(int fromIndex, int targetIndex) {}

        @Override
        public void sendSeek(float progress) {}

        @Override
        public void sendTogglePlayback() {}

        @Override
        public void sendSkipTrack() {}

        @Override
        public void sendPreviousTrack() {}

        @Override
        public void sendToggleLoop() {}

        @Override
        public void sendToggleShuffle() {}

        @Override
        public void sendSelectRadio(String stationUuid) {}

        @Override
        public void sendStopRadio() {}
    }

    public static synchronized void setTransport(ClientTransport clientTransport) {
        transport = clientTransport == null ? new NoopClientTransport() : clientTransport;
    }

    public static synchronized PlaybackMode getPlaybackMode() {
        return playbackMode;
    }

    public static synchronized void setPlaybackMode(PlaybackMode mode) {
        setActivePlaybackMode(mode);
    }

    private static void setActivePlaybackMode(PlaybackMode mode) {
        if (mode == null || !mode.isSelectable() || mode == playbackMode) {
            return;
        }

        localPlaybackGeneration++;
        serverClockOffsetMs = 0L;
        AudioPlayer.getInstance()
            .resetServerClock();
        stopLocalPlayback(-1L);
        LOCAL_QUEUE.clear();
        pendingAddResolutions.clear();
        pendingAddResolutionResyncRequested = false;

        if (mode == PlaybackMode.PRIVATE) {
            playbackMode = PlaybackMode.PRIVATE;
            updatePrivateLooping(LOCAL_QUEUE.isLooping());
            updatePrivateShuffling(LOCAL_QUEUE.isShuffling());
            refreshCachedPlaylistFromActiveQueue();
            persistPlaybackMode();
            return;
        }

        CLIENT_QUEUE.reset();
        playlistResyncRequested = false;
        playbackMode = PlaybackMode.SERVER;
        updateLooping(false);
        updateShuffling(false);
        refreshCachedPlaylistFromActiveQueue();
        transport.sendPlaylistResync(CLIENT_QUEUE.getRevision());
        sendClockSync();
        persistPlaybackMode();
    }

    static synchronized void setClientAudioDownloadService(AudioDownloadService service) {
        if (clientAudioDownloadService != null && clientAudioDownloadService != service) {
            clientAudioDownloadService.shutdown();
        }
        clientAudioDownloadService = service;
        activeTrackSourceType = null;
        activeTrackSourceId = null;
        activeTrackVideoId = null;
        activeTrackGeneration = -1L;
        activeTrackPositionMs = 0L;
        activeTrackStartAtMs = 0L;
        activeTrackDurationMs = 0L;
    }

    static synchronized void setClientMediaService(ClientMediaService service) {
        clientMediaService = service;
        clientMetadataCache = service == null ? null : new ClientMetadataCache(service);
        requestedVideoMetadata.clear();
        requestedStationMetadata.clear();
    }

    static synchronized void setClientRadioPlayback(ClientRadioPlayback playback) {
        if (clientRadioPlayback != null && clientRadioPlayback != playback) {
            clientRadioPlayback.stop();
        }
        clientRadioPlayback = playback;
    }

    public static synchronized void sendSearch(String query) {
        if (clientMediaService == null) {
            updateSearchResults(new ArrayList<HorizonRadioScreen.SearchResult>());
            return;
        }
        final long generation = ++searchTabDiscoveryGeneration;
        clientMediaService.search(query, maxTrackDurationMs())
            .whenComplete(new BiConsumer<List<SearchResult>, Throwable>() {

                @Override
                public void accept(final List<SearchResult> results, final Throwable failure) {
                    ClientProxy.scheduleOnClientThread(new Runnable() {

                        @Override
                        public void run() {
                            synchronized (HorizonRadioClient.class) {
                                if (generation != searchTabDiscoveryGeneration) {
                                    return;
                                }
                                if (failure != null) {
                                    showSearchError();
                                } else {
                                    updateSearchResults(toScreenResults(results));
                                }
                            }
                        }
                    });
                }
            });
    }

    public static synchronized void sendChartsRequest() {
        sendChartsRequest(false);
    }

    public static synchronized void sendChartsRequest(boolean forceRefresh) {
        sendChartsRequest(ChartRegionCatalog.GLOBAL_CODE, forceRefresh);
    }

    public static synchronized void sendChartsRequest(String regionCode, boolean forceRefresh) {
        String canonicalRegionCode = canonicalChartRegionCode(regionCode, ChartRegionCatalog.GLOBAL_CODE);
        final HorizonRadioScreen originatingScreen = getOpenScreen();
        pendingChartRegionCode = canonicalRegionCode;
        lastRequestedChartRegionCode = canonicalRegionCode;
        chartRequestPending = true;
        chartRequestScreen = originatingScreen;
        if (clientMediaService == null) {
            updateChartResults(new ArrayList<HorizonRadioScreen.SearchResult>(), canonicalRegionCode);
            return;
        }
        ChartRegion region = ChartRegionCatalog.byCode(canonicalRegionCode);
        if (region == null) {
            updateChartResults(new ArrayList<HorizonRadioScreen.SearchResult>(), canonicalRegionCode);
            return;
        }
        final long generation = ++chartGeneration;
        clientMediaService.fetchCharts(region)
            .whenComplete(new BiConsumer<List<SearchResult>, Throwable>() {

                @Override
                public void accept(final List<SearchResult> results, final Throwable failure) {
                    if (failure != null) {
                        ClientProxy.scheduleOnClientThread(new Runnable() {

                            @Override
                            public void run() {
                                synchronized (HorizonRadioClient.class) {
                                    if (isCurrentChartRequest(generation, originatingScreen)) {
                                        showChartError();
                                    }
                                }
                            }
                        });
                        return;
                    }
                    resolveChartDurations(results).whenComplete(new BiConsumer<List<SearchResult>, Throwable>() {

                        @Override
                        public void accept(final List<SearchResult> resolved, Throwable resolutionFailure) {
                            ClientProxy.scheduleOnClientThread(new Runnable() {

                                @Override
                                public void run() {
                                    synchronized (HorizonRadioClient.class) {
                                        if (!isCurrentChartRequest(generation, originatingScreen)) {
                                            return;
                                        }
                                        if (resolutionFailure != null) {
                                            showChartError();
                                            return;
                                        }
                                        updateChartResults(toScreenResults(resolved), canonicalRegionCode);
                                    }
                                }
                            });
                        }
                    });
                }
            });
    }

    public static synchronized boolean isChartRequestPending() {
        return chartRequestPending;
    }

    public static synchronized void beginChartLoading() {
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.beginChartLoading();
        }
    }

    public static synchronized void sendImportPlaylist(String playlistUrl) {
        if (clientMediaService == null) {
            updateSearchResults(new ArrayList<HorizonRadioScreen.SearchResult>());
            return;
        }
        completeLocalImport(clientMediaService.importPlaylist(playlistUrl));
    }

    public static synchronized void sendPlaylistImport(String playlistUrl) {
        String sanitizedUrl = playlistUrl == null ? "" : playlistUrl.trim();
        final HorizonRadioScreen originatingScreen = getOpenScreen();
        if (!PlaylistImportService.isPlaylistUrl(sanitizedUrl)) {
            playlistImportGeneration++;
            playlistImportScreen = null;
            if (originatingScreen != null) {
                originatingScreen.showPlaylistError("Paste a valid YouTube playlist URL");
            }
            return;
        }
        final long generation = ++playlistImportGeneration;
        playlistImportScreen = originatingScreen;
        if (originatingScreen != null) {
            originatingScreen.beginPlaylistLoading();
        }
        if (clientMediaService == null) {
            if (originatingScreen != null && isCurrentPlaylistImport(generation, originatingScreen)) {
                playlistImportScreen = null;
                originatingScreen.showPlaylistError("Playlist konnte nicht geladen werden");
            }
            return;
        }
        clientMediaService.importPlaylist(sanitizedUrl)
            .whenComplete(new BiConsumer<List<SearchResult>, Throwable>() {

                @Override
                public void accept(final List<SearchResult> results, final Throwable failure) {
                    ClientProxy.scheduleOnClientThread(new Runnable() {

                        @Override
                        public void run() {
                            synchronized (HorizonRadioClient.class) {
                                if (!isCurrentPlaylistImport(generation, originatingScreen)) {
                                    return;
                                }
                                playlistImportScreen = null;
                                if (failure != null) {
                                    originatingScreen.showPlaylistError("Playlist konnte nicht geladen werden");
                                } else {
                                    publishPlaylistResults(toScreenPlaylistResults(results), originatingScreen);
                                }
                            }
                        }
                    });
                }
            });
    }

    public static synchronized void sendImportVideo(String videoUrl) {
        if (clientMediaService == null) {
            updateSearchResults(new ArrayList<HorizonRadioScreen.SearchResult>());
            return;
        }
        final long generation = ++searchTabDiscoveryGeneration;
        clientMediaService.importVideo(videoUrl)
            .whenComplete(new BiConsumer<SearchResult, Throwable>() {

                @Override
                public void accept(final SearchResult result, final Throwable failure) {
                    ClientProxy.scheduleOnClientThread(new Runnable() {

                        @Override
                        public void run() {
                            synchronized (HorizonRadioClient.class) {
                                if (generation != searchTabDiscoveryGeneration) {
                                    return;
                                }
                                List<SearchResult> imported = new ArrayList<SearchResult>();
                                if (failure == null && result != null) {
                                    imported.add(result);
                                }
                                if (failure != null) {
                                    showSearchError();
                                } else {
                                    updateSearchResults(toScreenResults(imported));
                                }
                            }
                        }
                    });
                }
            });
    }

    public static synchronized void sendAdd(String videoId, long durationMs) {
        if (isValidSelection(videoId, durationMs)) {
            sendAddSelection(new PlaylistSelection(videoId, durationMs));
        }
    }

    /** Compatibility overload retained for older GUI and transport tests. */
    @Deprecated
    public static synchronized void sendAdd(String videoId, String title, String duration) {
        long durationMs = durationMillis(duration);
        if (isValidSelection(videoId, durationMs)) {
            PlaylistSelection selection = new PlaylistSelection(videoId, durationMs);
            if (playbackMode == PlaybackMode.PRIVATE || transport instanceof ForgeClientTransport) {
                sendAddSelection(selection);
            } else {
                transport.sendAdd(videoId, title, duration);
            }
        }
    }

    public static synchronized void sendPlayNow(String videoId, long durationMs) {
        if (isValidSelection(videoId, durationMs)) {
            sendPlayNowSelection(new PlaylistSelection(videoId, durationMs));
        }
    }

    public static synchronized void sendPlayNow(final HorizonRadioScreen.SearchResult result) {
        if (result == null) {
            return;
        }
        resolveChartSelection(result).whenComplete(new BiConsumer<ChartActionResolution, Throwable>() {

            @Override
            public void accept(final ChartActionResolution resolution, Throwable failure) {
                ClientProxy.scheduleOnClientThread(new Runnable() {

                    @Override
                    public void run() {
                        synchronized (HorizonRadioClient.class) {
                            if (failure != null || resolution == null || resolution.selection == null) {
                                debugChat(
                                    "Direktes Abspielen fehlgeschlagen: " + result.videoId
                                        + " ("
                                        + failureMessage(failure, resolution)
                                        + ")");
                                return;
                            }
                            updateCachedResultDuration(result.videoId, resolution.metadata);
                            debugChat("Spiele Chart lokal vor: " + result.videoId);
                            sendPlayNowSelection(resolution.selection);
                        }
                    }
                });
            }
        });
    }

    /** Compatibility overload retained for older GUI and transport tests. */
    @Deprecated
    public static synchronized void sendPlayNow(String videoId, String title, String duration) {
        long durationMs = durationMillis(duration);
        if (isValidSelection(videoId, durationMs)) {
            PlaylistSelection selection = new PlaylistSelection(videoId, durationMs);
            if (playbackMode == PlaybackMode.PRIVATE || transport instanceof ForgeClientTransport) {
                sendPlayNowSelection(selection);
            } else {
                transport.sendPlayNow(videoId, title, duration);
            }
        }
    }

    public static synchronized void sendAddChartsToPlaylist(List<?> selections) {
        sendAddChartsToPlaylist(selections, false);
    }

    public static synchronized void sendAddChartsToPlaylist(List<?> selections, boolean remove) {
        if (playbackMode == PlaybackMode.PRIVATE && remove) {
            removePrivateSelectionIds(selections);
            return;
        }
        if (playbackMode == PlaybackMode.PRIVATE && (remove || selections == null || selections.isEmpty()
            || !containsSearchResult(selections))) {
            applyPrivateSelections(toPlaylistSelections(selections), remove);
            return;
        }
        if (remove || selections == null || selections.isEmpty() || !containsSearchResult(selections)) {
            List<PlaylistSelection> mapped = toPlaylistSelections(selections);
            transport.sendAddChartSelections(mapped, remove);
            return;
        }
        resolveAndSendSelections(selections, QueueSelectionOrigin.CHARTS);
    }

    public static synchronized void sendPlaylistResultsToQueue(List<?> selections) {
        sendPlaylistResultsToQueue(selections, false);
    }

    public static synchronized void sendPlaylistResultsToQueue(List<?> selections, boolean remove) {
        if (selections == null || selections.isEmpty()) {
            return;
        }
        if (playbackMode == PlaybackMode.PRIVATE && remove) {
            removePrivateSelectionIds(selections);
            return;
        }
        if (playbackMode == PlaybackMode.PRIVATE && (remove || !containsSearchResult(selections))) {
            applyPrivateSelections(toPlaylistSelections(selections), remove);
            return;
        }
        if (remove) {
            transport.sendAddChartSelections(toPlaylistSelections(selections), true);
            return;
        }
        resolveAndSendSelections(selections, QueueSelectionOrigin.PLAYLIST);
    }

    public static synchronized void sendRemove(String videoId) {
        if (playbackMode == PlaybackMode.PRIVATE) {
            int removedIndex = LOCAL_QUEUE.findIndex(MediaSourceType.YOUTUBE, videoId);
            boolean removedCurrent = removedIndex >= 0 && removedIndex == LOCAL_QUEUE.getCurrentIndex();
            if (LOCAL_QUEUE.remove(MediaSourceType.YOUTUBE, videoId) >= 0) {
                if (removedCurrent) {
                    startNextPrivateEntry(removedIndex, System.currentTimeMillis());
                }
                refreshCachedPlaylistFromActiveQueue();
            }
            return;
        }
        transport.sendRemove(videoId);
    }

    public static synchronized void sendClearPlaylist() {
        if (playbackMode == PlaybackMode.PRIVATE) {
            if (LOCAL_QUEUE.size() > 0) {
                invalidateAndStopPrivatePlayback(false);
                LOCAL_QUEUE.clear();
                refreshCachedPlaylistFromActiveQueue();
            }
            return;
        }
        transport.sendClearPlaylist();
    }

    public static synchronized void sendReorder(int fromIndex, int targetIndex) {
        if (playbackMode == PlaybackMode.PRIVATE) {
            if (LOCAL_QUEUE.moveQueued(fromIndex, targetIndex)) {
                refreshCachedPlaylistFromActiveQueue();
            }
            return;
        }
        transport.sendReorder(fromIndex, targetIndex);
    }

    public static synchronized void sendSeek(float progress) {
        if (playbackMode == PlaybackMode.PRIVATE) {
            PlaylistEntry entry = LOCAL_QUEUE.getCurrentEntry();
            if (entry != null && entry.isFinite()) {
                long nowMs = System.currentTimeMillis();
                long positionMs = (long) (Math.max(0.0f, Math.min(1.0f, progress)) * entry.getDurationMs());
                long alignedPositionMs = LOCAL_QUEUE.seek(positionMs, nowMs);
                if (alignedPositionMs >= 0L) {
                    alignPrivateFiniteAudio(alignedPositionMs, LOCAL_QUEUE.isPaused());
                    updatePrivateFinitePresentation(entry, alignedPositionMs);
                    refreshCachedPlaylistFromActiveQueue();
                }
            }
            return;
        }
        if (activeTrackSourceType == MediaSourceType.RADIO) {
            return;
        }
        transport.sendSeek(progress);
    }

    public static synchronized void sendTogglePlayback() {
        if (playbackMode == PlaybackMode.PRIVATE) {
            long nowMs = System.currentTimeMillis();
            PlaylistEntry entry = LOCAL_QUEUE.getCurrentEntry();
            if (entry == null || !entry.isFinite()) {
                return;
            }
            long positionMs = LOCAL_QUEUE.currentPositionMs(nowMs);
            if (LOCAL_QUEUE.isPaused()) {
                long resumedPositionMs = LOCAL_QUEUE.resumePlayback(nowMs);
                if (resumedPositionMs >= 0L) {
                    activeTrackPositionMs = resumedPositionMs;
                    activeTrackStartAtMs = nowMs;
                    cachedPaused = false;
                    AudioPlayer.getInstance()
                        .resume(resumedPositionMs, 0L);
                    updatePrivateFinitePresentation(entry, resumedPositionMs);
                    refreshCachedPlaylistFromActiveQueue();
                }
            } else if (positionMs >= 0L && LOCAL_QUEUE.pausePlayback(positionMs, nowMs) >= 0L) {
                activeTrackPositionMs = positionMs;
                activeTrackStartAtMs = 0L;
                cachedPaused = true;
                AudioPlayer.getInstance()
                    .pause(positionMs);
                updatePrivateFinitePresentation(entry, positionMs);
                refreshCachedPlaylistFromActiveQueue();
            }
            return;
        }
        if (activeTrackSourceType == MediaSourceType.RADIO) {
            return;
        }
        transport.sendTogglePlayback();
    }

    public static synchronized void updateLooping(boolean looping) {
        if (playbackMode != PlaybackMode.SERVER) {
            return;
        }
        cachedLooping = looping;
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateLooping(looping);
        }
    }

    private static void updatePrivateLooping(boolean looping) {
        cachedLooping = looping;
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateLooping(looping);
        }
    }

    public static synchronized void sendSkipTrack() {
        if (playbackMode == PlaybackMode.PRIVATE) {
            int currentIndex = LOCAL_QUEUE.getCurrentIndex();
            PlaylistEntry removed = LOCAL_QUEUE.removeCurrent();
            if (removed == null && LOCAL_QUEUE.size() > 0) {
                currentIndex = 0;
                PlaylistEntry prepared = LOCAL_QUEUE.get(0);
                if (LOCAL_QUEUE.remove(prepared.getSourceType(), prepared.getSourceId()) >= 0) {
                    removed = prepared;
                }
            }
            if (removed != null) {
                startNextPrivateEntry(currentIndex, System.currentTimeMillis());
                refreshCachedPlaylistFromActiveQueue();
            }
            return;
        }
        transport.sendSkipTrack();
    }

    public static synchronized void sendPreviousTrack() {
        if (playbackMode == PlaybackMode.PRIVATE) {
            PlaylistEntry current = LOCAL_QUEUE.getCurrentEntry();
            if (current != null && current.isFinite()) {
                long positionMs = LOCAL_QUEUE.currentPositionMs(System.currentTimeMillis());
                if (!LOCAL_QUEUE.wasPreviousRestarted() || positionMs > 10_000L) {
                    LOCAL_QUEUE.markPreviousRestarted();
                    long nowMs = System.currentTimeMillis();
                    if (LOCAL_QUEUE.seek(0L, nowMs) >= 0L) {
                        alignPrivateFiniteAudio(0L, LOCAL_QUEUE.isPaused());
                        updatePrivateFinitePresentation(current, 0L);
                        refreshCachedPlaylistFromActiveQueue();
                    }
                    return;
                }
            }

            PlaylistEntry previous = LOCAL_QUEUE.takeLastTrack();
            if (previous != null && previous.isFinite()) {
                LOCAL_QUEUE.resetPlayback();
                PlaylistEntry prepared = LOCAL_QUEUE.prepareImmediatePlayback(previous);
                if (prepared != null) {
                    startPrivateFinite(prepared, 0L, false, System.currentTimeMillis());
                }
                refreshCachedPlaylistFromActiveQueue();
            } else if (current != null && current.isFinite()
                && LOCAL_QUEUE.seek(0L, System.currentTimeMillis()) >= 0L) {
                alignPrivateFiniteAudio(0L, LOCAL_QUEUE.isPaused());
                updatePrivateFinitePresentation(current, 0L);
                refreshCachedPlaylistFromActiveQueue();
            }
            return;
        }
        transport.sendPreviousTrack();
    }

    public static synchronized void sendToggleLoop() {
        if (playbackMode == PlaybackMode.PRIVATE) {
            updatePrivateLooping(LOCAL_QUEUE.toggleLooping());
            return;
        }
        transport.sendToggleLoop();
    }

    public static synchronized void sendToggleShuffle() {
        if (playbackMode == PlaybackMode.PRIVATE) {
            updatePrivateShuffling(LOCAL_QUEUE.toggleShuffling());
            if (LOCAL_QUEUE.isShuffling()) {
                LOCAL_QUEUE.shuffleQueued(new Random());
                refreshCachedPlaylistFromActiveQueue();
            }
            return;
        }
        transport.sendToggleShuffle();
    }

    public static synchronized void sendRadioSearch(String query) {
        if (clientMediaService == null) {
            updateRadioSearchResults(null);
            return;
        }
        final long generation = ++radioSearchGeneration;
        clientMediaService.searchRadio(query)
            .whenComplete(new BiConsumer<List<RadioStation>, Throwable>() {

                @Override
                public void accept(final List<RadioStation> stations, final Throwable failure) {
                    ClientProxy.scheduleOnClientThread(new Runnable() {

                        @Override
                        public void run() {
                            synchronized (HorizonRadioClient.class) {
                                if (generation != radioSearchGeneration) {
                                    return;
                                }
                                if (failure != null) {
                                    showRadioError();
                                } else {
                                    updateRadioSearchResults(stations);
                                }
                            }
                        }
                    });
                }
            });
    }

    public static synchronized void sendSelectRadio(String stationUuid) {
        if (playbackMode == PlaybackMode.PRIVATE) {
            invalidateAndStopPrivatePlayback(true);
            refreshCachedPlaylistFromActiveQueue();
            return;
        }
        transport.sendSelectRadio(stationUuid);
    }

    public static synchronized void sendStopRadio() {
        if (playbackMode == PlaybackMode.PRIVATE) {
            return;
        }
        transport.sendStopRadio();
    }

    public static synchronized void sendClockSync() {
        if (playbackMode != PlaybackMode.SERVER) {
            return;
        }
        transport.sendClockSync(System.currentTimeMillis());
    }

    public static synchronized List<HorizonRadioScreen.PlaylistEntry> getCachedPlaylist() {
        return new ArrayList<HorizonRadioScreen.PlaylistEntry>(CACHED_PLAYLIST);
    }

    public static synchronized List<HorizonRadioScreen.SearchResult> getCachedCharts() {
        return new ArrayList<HorizonRadioScreen.SearchResult>(CACHED_CHARTS);
    }

    public static synchronized List<HorizonRadioScreen.SearchResult> getCachedPlaylistResults() {
        return new ArrayList<HorizonRadioScreen.SearchResult>(CACHED_PLAYLIST_RESULTS);
    }

    public static synchronized String getCachedChartRegionCode() {
        return cachedChartRegionCode;
    }

    public static synchronized List<RadioStation> getCachedRadioResults() {
        return new ArrayList<RadioStation>(CACHED_RADIO_RESULTS);
    }

    public static synchronized ClientRadioPresentation getCachedRadioPresentation() {
        return cachedRadioPresentation;
    }

    public static synchronized List<ClientFavorites.Song> getFavoriteSongs() {
        return clientFavorites.getSongs();
    }

    public static synchronized List<ClientFavorites.Radio> getFavoriteRadios() {
        return clientFavorites.getRadios();
    }

    public static synchronized boolean hasCurrentFavoriteSource() {
        return currentSongFavorite() != null || currentRadioFavorite() != null;
    }

    public static synchronized boolean isCurrentSourceFavorite() {
        ClientFavorites.Song song = currentSongFavorite();
        if (song != null) {
            return clientFavorites.isSongFavorite(song.getVideoId());
        }
        ClientFavorites.Radio radio = currentRadioFavorite();
        return radio != null && clientFavorites.isRadioFavorite(radio.getStationUuid());
    }

    public static synchronized boolean toggleCurrentFavorite() {
        ClientFavorites.Song song = currentSongFavorite();
        if (song != null) {
            boolean added = clientFavorites.toggleSong(song);
            persistClientFavorites();
            return added;
        }
        ClientFavorites.Radio radio = currentRadioFavorite();
        if (radio != null) {
            boolean added = clientFavorites.toggleRadio(radio);
            persistClientFavorites();
            return added;
        }
        return false;
    }

    public static synchronized boolean hasFreshCachedCharts() {
        return !CACHED_CHARTS.isEmpty() && cachedChartsAt > 0L
            && System.currentTimeMillis() - cachedChartsAt < CHART_CACHE_TTL_MILLIS;
    }

    public static synchronized String getCachedNowPlaying() {
        return cachedNowPlaying;
    }

    public static synchronized float getCachedProgress() {
        return cachedProgress;
    }

    static synchronized void loadClientConfig(File configDirectory) {
        clientConfig = HorizonRadioClientConfig.load(configDirectory);
        clientFavorites = clientConfig.getFavorites();
        AudioPlayer.getInstance()
            .setVolume(clientConfig.getVolume());
        setActivePlaybackMode(clientConfig.getPlaybackMode());
    }

    public static synchronized float getVolume() {
        return AudioPlayer.getInstance()
            .getVolume();
    }

    static synchronized void setVolumePreview(float value) {
        AudioPlayer.getInstance()
            .setVolume(value);
    }

    static synchronized void persistVolume() {
        if (clientConfig != null) {
            clientConfig.save(
                AudioPlayer.getInstance()
                    .getVolume(),
                clientFavorites,
                playbackMode);
        }
    }

    private static void persistClientFavorites() {
        if (clientConfig != null) {
            clientConfig.save(
                AudioPlayer.getInstance()
                    .getVolume(),
                clientFavorites,
                playbackMode);
        }
    }

    private static void persistPlaybackMode() {
        if (clientConfig != null) {
            clientConfig.save(
                AudioPlayer.getInstance()
                    .getVolume(),
                clientFavorites,
                playbackMode);
        }
    }

    public static synchronized void setVolume(float value) {
        setVolumePreview(value);
        persistVolume();
    }

    public static synchronized void updateSearchResults(List<HorizonRadioScreen.SearchResult> results) {
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateSearchResults(results);
        }
    }

    public static synchronized void updateChartResults(List<HorizonRadioScreen.SearchResult> results) {
        updateChartResults(results, pendingChartRegionCode);
    }

    public static synchronized void updateChartResults(List<HorizonRadioScreen.SearchResult> results,
        String regionCode) {
        String responseRegionCode = canonicalChartRegionCode(regionCode, pendingChartRegionCode);
        if (lastRequestedChartRegionCode != null && !lastRequestedChartRegionCode.equals(responseRegionCode)) {
            return;
        }
        if (chartRequestPending && !pendingChartRegionCode.equals(responseRegionCode)) {
            return;
        }
        CACHED_CHARTS.clear();
        if (results != null) {
            CACHED_CHARTS.addAll(results);
        }
        cachedChartRegionCode = responseRegionCode;
        pendingChartRegionCode = responseRegionCode;
        cachedChartsAt = System.currentTimeMillis();
        chartRequestPending = false;
        chartRequestScreen = null;
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateChartResults(CACHED_CHARTS, cachedChartRegionCode);
        }
    }

    private static void updateCachedResultDuration(String videoId, SearchResult metadata) {
        if (videoId == null || metadata == null
            || metadata.getDuration() == null
            || metadata.getDuration()
                .trim()
                .isEmpty()) {
            return;
        }
        String duration = metadata.getDuration();
        updateCachedResultDuration(CACHED_CHARTS, videoId, duration);
        updateCachedResultDuration(CACHED_PLAYLIST_RESULTS, videoId, duration);
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateChartDuration(videoId, duration);
            screen.updatePlaylistResultDuration(videoId, duration);
        }
    }

    private static void updateCachedResultDuration(List<HorizonRadioScreen.SearchResult> results, String videoId,
        String duration) {
        for (int index = 0; index < results.size(); index++) {
            HorizonRadioScreen.SearchResult result = results.get(index);
            if (result != null && videoId.equals(result.videoId)) {
                results.set(
                    index,
                    new HorizonRadioScreen.SearchResult(
                        result.videoId,
                        result.title,
                        result.channel,
                        duration,
                        result.thumbnail));
            }
        }
    }

    public static synchronized void updatePlaylist(List<HorizonRadioScreen.PlaylistEntry> entries) {
        CACHED_PLAYLIST.clear();
        if (entries != null) {
            CACHED_PLAYLIST.addAll(entries);
        }
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updatePlaylist(CACHED_PLAYLIST);
        }
    }

    public static synchronized void handlePlaylistSnapshot(PlaylistSyncPacket packet) {
        if (playbackMode != PlaybackMode.SERVER || packet == null) {
            return;
        }
        long revisionBefore = CLIENT_QUEUE.getRevision();
        int sizeBefore = CLIENT_QUEUE.snapshot()
            .size();
        debugChat(
            "PlaylistSyncPacket empfangen revision=" + packet.getQueueRevision()
                + " entries="
                + packet.getEntries()
                    .size()
                + " queueBefore="
                + sizeBefore
                + " clientRevision="
                + revisionBefore);
        List<PlaylistEntry> entries = new ArrayList<PlaylistEntry>();
        for (PlaylistSyncPacket.Entry entry : packet.getEntries()) {
            entries.add(PlaylistEntry.of(entry.getSourceType(), entry.getSourceId(), 0L, entry.getAddedBy()));
        }
        CLIENT_QUEUE.applySnapshot(packet.getQueueRevision(), packet.isShuffling(), packet.isLooping(), entries);
        if (CLIENT_QUEUE.isSnapshotRequired()) {
            debugChat(
                "PlaylistSyncPacket verworfen: veraltet oder doppelte IDs; Resync wird angefordert. clientRevision="
                    + CLIENT_QUEUE.getRevision());
            requestPlaylistResync();
            return;
        }
        debugChat(
            "PlaylistSyncPacket übernommen queueAfter=" + CLIENT_QUEUE.snapshot()
                .size() + " revision=" + CLIENT_QUEUE.getRevision());
        playlistResyncRequested = false;
        cachedShuffling = CLIENT_QUEUE.isShuffling();
        cachedLooping = CLIENT_QUEUE.isLooping();
        stopLocalRadioWhenAbsentFromQueue();
        refreshCachedPlaylistFromActiveQueue();
        completePendingAddResolutionFromSnapshot();
        updateShuffling(cachedShuffling);
        updateLooping(cachedLooping);
    }

    public static synchronized void handlePlaylistDelta(PlaylistDeltaPacket packet) {
        if (playbackMode != PlaybackMode.SERVER) {
            return;
        }
        long revisionBefore = CLIENT_QUEUE.getRevision();
        int sizeBefore = CLIENT_QUEUE.snapshot()
            .size();
        String deltaId = packet == null || packet.getEntry() == null ? "-"
            : packet.getEntry()
                .getSourceId();
        boolean applied = CLIENT_QUEUE.applyDelta(packet);
        debugChat(
            "PlaylistDeltaPacket op=" + (packet == null ? "null" : packet.getOperation())
                + " id="
                + deltaId
                + " revision="
                + (packet == null ? "-" : packet.getQueueRevision())
                + " accepted="
                + applied
                + " queueBefore="
                + sizeBefore
                + " queueAfter="
                + CLIENT_QUEUE.snapshot()
                    .size()
                + " clientRevisionBefore="
                + revisionBefore
                + " clientRevisionAfter="
                + CLIENT_QUEUE.getRevision());
        if (applied) {
            stopLocalRadioWhenAbsentFromQueue();
            refreshCachedPlaylistFromActiveQueue();
            return;
        }
        requestPlaylistResync();
    }

    public static synchronized void requestPlaylistResync() {
        if (playbackMode != PlaybackMode.SERVER) {
            return;
        }
        if (!CLIENT_QUEUE.isSnapshotRequired() || playlistResyncRequested) {
            return;
        }
        playlistResyncRequested = true;
        transport.sendPlaylistResync(CLIENT_QUEUE.getRevision());
    }

    private static void clearPendingAdds(QueueSelectionOrigin origin, List<String> videoIds) {
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null && videoIds != null && !videoIds.isEmpty()) {
            if (origin == QueueSelectionOrigin.PLAYLIST) {
                screen.completePlaylistAdds(videoIds);
            } else {
                screen.completeChartAdds(videoIds);
            }
        }
    }

    private static void awaitPendingAddResolution(QueueSelectionOrigin origin, List<PlaylistSelection> selections) {
        HorizonRadioScreen screen = getOpenScreen();
        if (screen == null || selections == null || selections.isEmpty()) {
            return;
        }
        List<String> videoIds = new ArrayList<String>();
        for (PlaylistSelection selection : selections) {
            if (selection != null && selection.videoId != null) {
                videoIds.add(selection.videoId);
            }
        }
        Set<String> pendingIds = screen.pendingAddIds(origin == QueueSelectionOrigin.PLAYLIST, videoIds);
        if (pendingIds.isEmpty()) {
            return;
        }
        pendingAddResolutions
            .add(new PendingAddResolution(screen, origin == QueueSelectionOrigin.PLAYLIST, pendingIds));
        if (pendingAddResolutions.size() == 1 && !pendingAddResolutionResyncRequested) {
            pendingAddResolutionResyncRequested = true;
            transport.sendPlaylistResync(CLIENT_QUEUE.getRevision());
        }
    }

    private static void completePendingAddResolutionFromSnapshot() {
        if (!pendingAddResolutionResyncRequested) {
            return;
        }
        pendingAddResolutionResyncRequested = false;
        if (!pendingAddResolutions.isEmpty()) {
            PendingAddResolution resolution = pendingAddResolutions.remove(0);
            if (resolution.playlistOrigin) {
                resolution.screen.completePlaylistAdds(new ArrayList<String>(resolution.videoIds));
            } else {
                resolution.screen.completeChartAdds(new ArrayList<String>(resolution.videoIds));
            }
        }
        if (!pendingAddResolutions.isEmpty()) {
            pendingAddResolutionResyncRequested = true;
            transport.sendPlaylistResync(CLIENT_QUEUE.getRevision());
        }
    }

    public static synchronized void updateRadioPresentation(ClientRadioPresentation presentation) {
        boolean wasRadioActive = cachedRadioActive;
        cachedRadioPresentation = presentation;
        cachedRadioActive = presentation != null && presentation.isActive();
        if (cachedRadioActive || wasRadioActive || hasRadioStatus(presentation)) {
            clearCachedMusicState();
            cancelActiveTrackDownload();
        }
        if (cachedRadioActive) {
            AudioPlayer.getInstance()
                .stop();
        } else {
            AudioPlayer.getInstance()
                .stopRadio();
        }
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateRadioPresentation(presentation);
        }
    }

    public static synchronized void updateNowPlaying(String title, float progress) {
        if (cachedRadioActive) {
            return;
        }
        cachedNowPlaying = title == null || title.length() == 0 ? null : title;
        cachedProgress = Math.max(0.0f, Math.min(1.0f, progress));
        if (cachedNowPlaying == null) {
            cachedPaused = false;
            cancelActiveTrackDownload();
            AudioPlayer.getInstance()
                .stop();
        }
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateNowPlaying(cachedNowPlaying, cachedProgress);
        }
    }

    public static synchronized void handleTrackSync(final TrackSyncPacket packet) {
        if (playbackMode != PlaybackMode.SERVER) {
            return;
        }
        if (!shouldAcceptTrackSync(activeTrackGeneration, activeTrackSourceType, activeTrackSourceId, packet)) {
            debugChat("Veraltete Track-Synchronisation ignoriert.");
            return;
        }

        if (packet.isStop()) {
            stopLocalPlayback(packet.getGeneration());
            return;
        }

        activeTrackGeneration = packet.getGeneration();
        MediaSourceType previousSourceType = activeTrackSourceType;
        activeTrackSourceType = packet.getSourceType();
        activeTrackSourceId = packet.getSourceId();
        String previousVideoId = activeTrackVideoId;
        activeTrackVideoId = packet.getVideoId();
        if (clientAudioDownloadService != null && previousSourceType == MediaSourceType.YOUTUBE
            && previousVideoId != null
            && (packet.getSourceType() != MediaSourceType.YOUTUBE || !previousVideoId.equals(activeTrackVideoId))) {
            clientAudioDownloadService.cancelDownload(previousVideoId);
        }

        if (packet.getSourceType() == MediaSourceType.RADIO) {
            clearCachedMusicState();
            AudioPlayer.getInstance()
                .stop();
            setLocalRadioPresentation(ClientRadioPresentation.live(packet.getGeneration(), packet.getSourceId()));
            if (clientRadioPlayback != null) {
                clientRadioPlayback.start(packet.getGeneration(), packet.getSourceId());
                debugChat("Radio " + packet.getSourceId() + " lokal angefordert.");
            } else {
                debugChat("Kein lokaler Radio-Player verfügbar.");
            }
            return;
        }

        if (clientRadioPlayback != null) {
            clientRadioPlayback.stop();
        }
        AudioPlayer.getInstance()
            .stopRadio();
        if (cachedRadioActive || cachedRadioPresentation != null) {
            updateRadioPresentation(null);
        }

        AudioPlayer.getInstance()
            .beginLocalTrack(packet.getVideoId(), packet.getPositionMs(), packet.getStartAtMs(), packet.isPaused());
        activeTrackPositionMs = packet.getPositionMs();
        activeTrackStartAtMs = packet.isPaused() ? 0L : packet.getStartAtMs();
        activeTrackDurationMs = 0L;
        cachedPaused = packet.isPaused();
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updatePlaybackPaused(cachedPaused);
        }

        long delayMs = packet.getStartAtMs() <= 0L ? 0L : packet.getStartAtMs() - System.currentTimeMillis();
        debugChat(
            "Track " + packet.getVideoId()
                + " lokal angefordert; "
                + (packet.isPaused() ? "pausiert bei " + packet.getPositionMs() + " ms."
                    : "Startziel in " + Math.max(0L, delayMs) + " ms."));

        requestActiveVideoMetadata(packet.getGeneration(), packet.getVideoId());

        if (clientAudioDownloadService == null) {
            debugChat("Kein lokaler Audio-Downloader verfügbar.");
            return;
        }

        final long generation = packet.getGeneration();
        final String videoId = packet.getVideoId();
        final long startAtMs = packet.getStartAtMs();
        CompletableFuture<Path> download;
        try {
            download = clientAudioDownloadService.download(videoId);
        } catch (RuntimeException exception) {
            debugChat("Lokaler Download konnte nicht gestartet werden: " + videoId);
            return;
        }
        if (download == null) {
            debugChat("Lokaler Downloader lieferte keinen Download für: " + videoId);
            return;
        }
        prefetchNextFiniteTrack(CLIENT_QUEUE.snapshot());
        download.whenComplete(new BiConsumer<Path, Throwable>() {

            @Override
            public void accept(final Path filePath, Throwable failure) {
                ClientProxy.scheduleOnClientThread(new Runnable() {

                    @Override
                    public void run() {
                        synchronized (HorizonRadioClient.class) {
                            if (activeTrackSourceType != MediaSourceType.YOUTUBE
                                || !shouldAcceptServerAudioCompletion(
                                    playbackMode,
                                    activeTrackGeneration,
                                    generation,
                                    activeTrackVideoId,
                                    videoId)) {
                                return;
                            }
                            if (failure != null || filePath == null || !Files.isRegularFile(filePath)) {
                                debugChat("Lokaler Audio-Download fehlgeschlagen: " + videoId);
                                return;
                            }
                            AudioPlayer.getInstance()
                                .loadLocalTrack(videoId, filePath);
                            if (startAtMs > 0L && System.currentTimeMillis() > startAtMs) {
                                debugChat("Track " + videoId + " ist verspätet; Client holt die Position nach.");
                            } else {
                                debugChat("Track " + videoId + " lokal bereit.");
                            }
                        }
                    }
                });
            }
        });
    }

    static boolean shouldAcceptServerAudioCompletion(PlaybackMode currentMode, long currentGeneration,
        long expectedGeneration, String currentVideoId, String expectedVideoId) {
        return currentMode == PlaybackMode.SERVER && currentGeneration == expectedGeneration
            && currentVideoId != null && currentVideoId.equals(expectedVideoId);
    }

    static boolean shouldAcceptTrackSync(long currentGeneration, String currentVideoId, TrackSyncPacket packet) {
        return shouldAcceptTrackSync(currentGeneration, MediaSourceType.YOUTUBE, currentVideoId, packet);
    }

    static boolean shouldAcceptTrackSync(long currentGeneration, MediaSourceType currentSourceType,
        String currentSourceId, TrackSyncPacket packet) {
        if (packet == null) {
            return false;
        }
        if (packet.isStop()) {
            return packet.getGeneration() > currentGeneration;
        }
        if (packet.getSourceType() == null || packet.getSourceId() == null
            || packet.getSourceId()
                .trim()
                .length() == 0) {
            return false;
        }
        if (packet.getGeneration() > currentGeneration) {
            return true;
        }
        return packet.getGeneration() == currentGeneration
            && (packet.getSourceType() != currentSourceType || !packet.getSourceId()
                .equals(currentSourceId));
    }

    public static synchronized void handlePause(long positionMs) {
        if (playbackMode != PlaybackMode.SERVER) {
            return;
        }
        if (activeTrackSourceType == MediaSourceType.RADIO) {
            return;
        }
        cachedPaused = true;
        activeTrackPositionMs = Math.max(0L, positionMs);
        activeTrackStartAtMs = 0L;
        AudioPlayer.getInstance()
            .pause(positionMs);
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updatePlaybackPaused(true);
        }
    }

    public static synchronized void handleResume(long positionMs) {
        handleResume(positionMs, 0L);
    }

    public static synchronized void handleResume(long positionMs, long startAtMs) {
        if (playbackMode != PlaybackMode.SERVER) {
            return;
        }
        if (activeTrackSourceType == MediaSourceType.RADIO) {
            return;
        }
        cachedPaused = false;
        activeTrackPositionMs = Math.max(0L, positionMs);
        activeTrackStartAtMs = Math.max(0L, startAtMs);
        AudioPlayer.getInstance()
            .resume(positionMs, startAtMs);
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updatePlaybackPaused(false);
        }
    }

    public static synchronized void handleClockSync(ClockSyncResponsePacket packet, long clientReceivedAtMs) {
        if (playbackMode != PlaybackMode.SERVER || packet == null) {
            return;
        }
        long serverClockOffsetMs = PlaybackClock.estimateServerOffsetMs(
            packet.getClientSentAtMs(),
            packet.getServerReceivedAtMs(),
            packet.getServerSentAtMs(),
            clientReceivedAtMs);
        HorizonRadioClient.serverClockOffsetMs = serverClockOffsetMs;
        AudioPlayer.getInstance()
            .updateServerClockOffset(serverClockOffsetMs);
    }

    public static synchronized void clearCache() {
        localPlaybackGeneration++;
        if (clientRadioPlayback != null) {
            clientRadioPlayback.stop();
        }
        cancelActiveTrackDownload();
        CACHED_PLAYLIST.clear();
        CACHED_CHARTS.clear();
        CACHED_PLAYLIST_RESULTS.clear();
        CACHED_RADIO_RESULTS.clear();
        cachedChartsAt = 0L;
        chartRequestPending = false;
        chartRequestScreen = null;
        playlistImportScreen = null;
        cachedChartRegionCode = "";
        pendingChartRegionCode = "";
        lastRequestedChartRegionCode = null;
        cachedNowPlaying = null;
        cachedProgress = 0.0f;
        cachedPaused = false;
        cachedLooping = false;
        cachedShuffling = false;
        cachedRadioActive = false;
        cachedRadioPresentation = null;
        CLIENT_QUEUE.reset();
        LOCAL_QUEUE.clear();
        playlistResyncRequested = false;
        pendingAddResolutionResyncRequested = false;
        pendingAddResolutions.clear();
        searchTabDiscoveryGeneration++;
        chartGeneration++;
        playlistImportGeneration++;
        radioSearchGeneration++;
        serverClockOffsetMs = 0L;
        AudioPlayer.getInstance()
            .stop();
        AudioPlayer.getInstance()
            .resetRadio();
        AudioPlayer.getInstance()
            .resetServerClock();
    }

    private static void cancelActiveTrackDownload() {
        if (clientAudioDownloadService != null && activeTrackSourceType == MediaSourceType.YOUTUBE
            && activeTrackVideoId != null) {
            clientAudioDownloadService.cancelDownload(activeTrackVideoId);
        }
        activeTrackSourceType = null;
        activeTrackSourceId = null;
        activeTrackVideoId = null;
        activeTrackGeneration = -1L;
        activeTrackPositionMs = 0L;
        activeTrackStartAtMs = 0L;
        activeTrackDurationMs = 0L;
    }

    private static void stopLocalPlayback(long generation) {
        boolean stoppingRadio = activeTrackSourceType == MediaSourceType.RADIO && activeTrackSourceId != null
            && activeTrackSourceId.trim()
                .length() > 0;
        String stoppedRadioUuid = stoppingRadio ? activeTrackSourceId : null;
        String stoppedRadioName = stoppedRadioUuid;
        if (stoppingRadio && cachedRadioPresentation != null
            && cachedRadioPresentation.getStationName() != null
            && cachedRadioPresentation.getStationName()
                .trim()
                .length() > 0) {
            stoppedRadioName = cachedRadioPresentation.getStationName();
        }
        cancelActiveTrackDownload();
        if (clientRadioPlayback != null) {
            clientRadioPlayback.stop();
        }
        AudioPlayer.getInstance()
            .stop();
        AudioPlayer.getInstance()
            .stopRadio();
        if (stoppingRadio) {
            updateRadioPresentation(
                ClientRadioPresentation.inactive(generation, stoppedRadioUuid, stoppedRadioName, "", false));
        } else if (cachedRadioActive || cachedRadioPresentation != null) {
            updateRadioPresentation(null);
        }
        clearCachedMusicState();
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            if (!stoppingRadio) {
                screen.updateNowPlaying(null, 0.0f);
                screen.updatePlaybackPaused(false);
            }
        }
        activeTrackSourceType = null;
        activeTrackSourceId = null;
        activeTrackVideoId = null;
        activeTrackGeneration = generation;
        activeTrackPositionMs = 0L;
        activeTrackStartAtMs = 0L;
        activeTrackDurationMs = 0L;
    }

    private static void stopLocalRadioWhenAbsentFromQueue() {
        if (cachedRadioPresentation == null) {
            return;
        }
        for (PlaylistEntry entry : CLIENT_QUEUE.snapshot()) {
            if (entry.isRadio() && cachedRadioPresentation.getStationUuid()
                .equals(entry.getSourceId())) {
                return;
            }
        }
        if (cachedRadioActive && clientRadioPlayback != null) {
            clientRadioPlayback.stop();
        }
        updateRadioPresentation(null);
    }

    /** Applies local stream status only while the matching radio source is active. */
    public static synchronized void handleLocalRadioStarted(long generation, String stationUuid, String stationName) {
        if (!isActiveRadio(generation, stationUuid)) {
            return;
        }
        setLocalRadioPresentation(ClientRadioPresentation.active(generation, stationUuid, stationName, "LIVE"));
        refreshFavoritedCurrentRadioMetadata();
    }

    /** Keeps local radio failures on the client and ignores stale stream callbacks. */
    public static synchronized void handleLocalRadioFailure(long generation, String stationUuid, String message) {
        if (!isActiveRadio(generation, stationUuid)) {
            return;
        }
        cachedRadioActive = false;
        cachedRadioPresentation = ClientRadioPresentation.stopped(generation, message);
        clearCachedMusicState();
        AudioPlayer.getInstance()
            .stopRadio();
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateRadioPresentation(cachedRadioPresentation);
        }
    }

    /** Refreshes finite client presentation without generating a progress packet. */
    public static synchronized void onClientTick() {
        onClientTick(System.currentTimeMillis());
    }

    static synchronized void onClientTick(long clientNowMs) {
        if (playbackMode == PlaybackMode.PRIVATE) {
            advancePrivateFinitePlayback(clientNowMs);
            return;
        }
        refreshLocalFinitePresentation(clientNowMs);
    }

    static boolean shouldAcceptPrivateAudioCompletion(PlaybackMode currentMode, long currentGeneration,
        long expectedGeneration, String currentVideoId, String expectedVideoId) {
        return currentMode == PlaybackMode.PRIVATE && currentGeneration == expectedGeneration
            && currentVideoId != null && currentVideoId.equals(expectedVideoId);
    }

    private static void advancePrivateFinitePlayback(long clientNowMs) {
        PlaylistEntry entry = LOCAL_QUEUE.getCurrentEntry();
        if (entry == null || !entry.isFinite()) {
            return;
        }
        long positionMs = LOCAL_QUEUE.currentPositionMs(clientNowMs);
        if (positionMs < 0L) {
            return;
        }
        updatePrivateFinitePresentation(entry, positionMs);
        if (LOCAL_QUEUE.isPaused()
            || clientNowMs - LOCAL_QUEUE.getPlaybackStartTime() < entry.getDurationMs()) {
            return;
        }

        if (LOCAL_QUEUE.isLooping()) {
            startPrivateFinite(entry, 0L, false, clientNowMs);
            refreshCachedPlaylistFromActiveQueue();
            return;
        }

        int currentIndex = LOCAL_QUEUE.getCurrentIndex();
        LOCAL_QUEUE.removeCurrent();
        if (LOCAL_QUEUE.isShuffling()) {
            LOCAL_QUEUE.shuffleQueued(new Random());
        }
        startNextPrivateEntry(currentIndex, clientNowMs);
        refreshCachedPlaylistFromActiveQueue();
    }

    static synchronized void refreshLocalFinitePresentation(long clientNowMs) {
        if (activeTrackSourceType != MediaSourceType.YOUTUBE || activeTrackVideoId == null) {
            return;
        }
        SearchResult metadata = clientMetadataCache == null ? null : clientMetadataCache.getVideo(activeTrackVideoId);
        if (metadata != null) {
            if (metadata.getTitle() != null && metadata.getTitle()
                .trim()
                .length() > 0) {
                cachedNowPlaying = metadata.getTitle();
            }
            activeTrackDurationMs = DurationParser.parseMillisStrict(metadata.getDuration());
        }
        if (cachedNowPlaying == null || cachedNowPlaying.length() == 0) {
            cachedNowPlaying = activeTrackVideoId;
        }
        long positionMs = cachedPaused ? activeTrackPositionMs
            : PlaybackClock
                .finiteTrackPositionMs(activeTrackPositionMs, activeTrackStartAtMs, serverClockOffsetMs, clientNowMs);
        cachedProgress = activeTrackDurationMs <= 0L ? 0.0f
            : Math.max(0.0f, Math.min(1.0f, (float) positionMs / (float) activeTrackDurationMs));
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateNowPlaying(cachedNowPlaying, cachedProgress);
            screen.updatePlaybackPaused(cachedPaused);
        }
    }

    private static boolean isActiveRadio(long generation, String stationUuid) {
        return activeTrackSourceType == MediaSourceType.RADIO && activeTrackGeneration == generation
            && stationUuid != null
            && stationUuid.equals(activeTrackSourceId);
    }

    private static void setLocalRadioPresentation(ClientRadioPresentation presentation) {
        cachedRadioPresentation = presentation;
        cachedRadioActive = presentation != null && presentation.isActive();
        clearCachedMusicState();
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateRadioPresentation(presentation);
        }
    }

    private static void debugChat(String message) {
        ClientProxy.sendDebugChat(message);
    }

    public static synchronized boolean isPaused() {
        return cachedPaused;
    }

    public static synchronized boolean isLooping() {
        return cachedLooping;
    }

    public static synchronized void updateShuffling(boolean shuffling) {
        if (playbackMode != PlaybackMode.SERVER) {
            return;
        }
        cachedShuffling = shuffling;
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateShuffling(shuffling);
        }
    }

    private static void updatePrivateShuffling(boolean shuffling) {
        cachedShuffling = shuffling;
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateShuffling(shuffling);
        }
    }

    public static synchronized boolean isShuffling() {
        return cachedShuffling;
    }

    public static synchronized boolean isRadioActive() {
        return cachedRadioActive;
    }

    private static HorizonRadioScreen getOpenScreen() {
        return HorizonRadioScreen.getActiveScreen();
    }

    private static boolean hasRadioStatus(ClientRadioPresentation presentation) {
        return presentation != null && presentation.getStatus() != null
            && presentation.getStatus()
                .length() > 0;
    }

    private static void clearCachedMusicState() {
        cachedNowPlaying = null;
        cachedProgress = 0.0f;
        cachedPaused = false;
    }

    private static ClientFavorites.Song currentSongFavorite() {
        if (activeTrackSourceType != MediaSourceType.YOUTUBE || activeTrackVideoId == null
            || activeTrackVideoId.trim()
                .length() == 0) {
            return null;
        }
        SearchResult metadata = clientMetadataCache == null ? null : clientMetadataCache.getVideo(activeTrackVideoId);
        String title = metadata == null ? cachedNowPlaying : metadata.getTitle();
        String channel = metadata == null ? "" : metadata.getChannel();
        String duration = metadata == null ? formatDuration(activeTrackDurationMs) : metadata.getDuration();
        String thumbnail = metadata == null ? "" : metadata.getThumbnail();
        return new ClientFavorites.Song(activeTrackVideoId, title, channel, duration, thumbnail);
    }

    private static ClientFavorites.Radio currentRadioFavorite() {
        String stationUuid = null;
        if (activeTrackSourceType == MediaSourceType.RADIO) {
            stationUuid = activeTrackSourceId;
        } else if (cachedRadioPresentation != null && !cachedRadioPresentation.isMusicMode()) {
            stationUuid = cachedRadioPresentation.getStationUuid();
        }
        if (stationUuid == null || stationUuid.trim()
            .length() == 0) {
            return null;
        }
        String stationName = cachedRadioPresentation == null ? "" : cachedRadioPresentation.getStationName();
        return new ClientFavorites.Radio(stationUuid, stationName);
    }

    private static void refreshFavoritedCurrentSongMetadata() {
        ClientFavorites.Song current = currentSongFavorite();
        if (current != null && clientFavorites.isSongFavorite(current.getVideoId())) {
            clientFavorites.updateSong(current);
            persistClientFavorites();
        }
    }

    private static void refreshFavoritedCurrentRadioMetadata() {
        ClientFavorites.Radio current = currentRadioFavorite();
        if (current != null && clientFavorites.isRadioFavorite(current.getStationUuid())) {
            clientFavorites.updateRadio(current);
            persistClientFavorites();
        }
    }

    private static String canonicalChartRegionCode(String value, String fallback) {
        if (value == null || value.trim()
            .length() == 0) {
            return fallback;
        }
        ChartRegion region = ChartRegionCatalog.byCode(value.trim());
        return region == null ? fallback : region.getCode();
    }

    private static void completeLocalImport(CompletableFuture<List<SearchResult>> future) {
        final long generation = ++searchTabDiscoveryGeneration;
        future.whenComplete(new BiConsumer<List<SearchResult>, Throwable>() {

            @Override
            public void accept(final List<SearchResult> results, final Throwable failure) {
                ClientProxy.scheduleOnClientThread(new Runnable() {

                    @Override
                    public void run() {
                        synchronized (HorizonRadioClient.class) {
                            if (generation == searchTabDiscoveryGeneration) {
                                if (failure != null) {
                                    showSearchError();
                                } else {
                                    updateSearchResults(toScreenResults(results));
                                }
                            }
                        }
                    }
                });
            }
        });
    }

    private static void refreshCachedPlaylistFromActiveQueue() {
        List<HorizonRadioScreen.PlaylistEntry> refreshed = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        List<PlaylistEntry> queue = playbackMode == PlaybackMode.PRIVATE ? LOCAL_QUEUE.snapshot() : CLIENT_QUEUE.snapshot();
        for (PlaylistEntry entry : queue) {
            refreshed.add(toScreenPlaylistEntry(entry));
        }
        CACHED_PLAYLIST.clear();
        CACHED_PLAYLIST.addAll(refreshed);
        prefetchNextFiniteTrack(queue);
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updatePlaylist(CACHED_PLAYLIST);
        }
    }

    private static void prefetchNextFiniteTrack(List<PlaylistEntry> queue) {
        if (clientAudioDownloadService == null || queue == null) {
            return;
        }
        boolean currentSeen = activeTrackSourceType == null || activeTrackSourceType == MediaSourceType.RADIO;
        PlaylistEntry fallback = null;
        for (PlaylistEntry entry : queue) {
            if (entry == null || !entry.isFinite()) {
                continue;
            }
            if (fallback == null || !entry.getSourceId()
                .equals(activeTrackSourceId)) {
                fallback = entry;
            }
            if (!currentSeen) {
                if (entry.getSourceId()
                    .equals(activeTrackSourceId) && activeTrackSourceType == MediaSourceType.YOUTUBE) {
                    currentSeen = true;
                }
                continue;
            }
            if (activeTrackSourceType == MediaSourceType.YOUTUBE && entry.getSourceId()
                .equals(activeTrackSourceId)) {
                continue;
            }
            requestLocalAudioDownload(entry.getSourceId());
            return;
        }
        if (fallback != null && (activeTrackSourceType != MediaSourceType.YOUTUBE || !fallback.getSourceId()
            .equals(activeTrackSourceId))) {
            requestLocalAudioDownload(fallback.getSourceId());
        }
    }

    private static void requestLocalAudioDownload(String videoId) {
        if (videoId == null || clientAudioDownloadService == null) {
            return;
        }
        try {
            clientAudioDownloadService.download(videoId);
        } catch (RuntimeException exception) {
            debugChat("Vorladen konnte nicht gestartet werden: " + videoId);
        }
    }

    private static HorizonRadioScreen.PlaylistEntry toScreenPlaylistEntry(final PlaylistEntry entry) {
        SearchResult video = null;
        RadioStation station = null;
        if (clientMetadataCache != null) {
            if (entry.getSourceType() == MediaSourceType.YOUTUBE) {
                requestVideoMetadata(entry.getSourceId());
                video = clientMetadataCache.getVideo(entry.getSourceId());
            } else {
                requestStationMetadata(entry.getSourceId());
                station = clientMetadataCache.getStation(entry.getSourceId());
            }
        }
        return new HorizonRadioScreen.PlaylistEntry(
            entry.getSourceType(),
            entry.getSourceId(),
            entry.getAddedBy(),
            video,
            station);
    }

    private static void requestVideoMetadata(final String sourceId) {
        if (!requestedVideoMetadata.add(sourceId)) {
            return;
        }
        clientMetadataCache.video(sourceId)
            .whenComplete(new BiConsumer<SearchResult, Throwable>() {

                @Override
                public void accept(SearchResult ignored, Throwable failure) {
                    ClientProxy.scheduleOnClientThread(new Runnable() {

                        @Override
                        public void run() {
                            synchronized (HorizonRadioClient.class) {
                                refreshCachedPlaylistFromActiveQueue();
                            }
                        }
                    });
                }
            });
    }

    private static void requestActiveVideoMetadata(final long generation, final String videoId) {
        if (clientMetadataCache == null) {
            return;
        }
        clientMetadataCache.video(videoId)
            .whenComplete(new BiConsumer<SearchResult, Throwable>() {

                @Override
                public void accept(SearchResult ignored, Throwable failure) {
                    ClientProxy.scheduleOnClientThread(new Runnable() {

                        @Override
                        public void run() {
                            synchronized (HorizonRadioClient.class) {
                                if (generation == activeTrackGeneration
                                    && activeTrackSourceType == MediaSourceType.YOUTUBE
                                    && videoId.equals(activeTrackVideoId)) {
                                    refreshLocalFinitePresentation(System.currentTimeMillis());
                                    refreshFavoritedCurrentSongMetadata();
                                }
                            }
                        }
                    });
                }
            });
    }

    private static void requestStationMetadata(final String sourceId) {
        if (!requestedStationMetadata.add(sourceId)) {
            return;
        }
        clientMetadataCache.station(sourceId)
            .whenComplete(new BiConsumer<RadioStation, Throwable>() {

                @Override
                public void accept(RadioStation ignored, Throwable failure) {
                    ClientProxy.scheduleOnClientThread(new Runnable() {

                        @Override
                        public void run() {
                            synchronized (HorizonRadioClient.class) {
                                refreshCachedPlaylistFromActiveQueue();
                            }
                        }
                    });
                }
            });
    }

    private static List<HorizonRadioScreen.SearchResult> toScreenResults(List<SearchResult> results) {
        List<HorizonRadioScreen.SearchResult> converted = new ArrayList<HorizonRadioScreen.SearchResult>();
        if (results != null) {
            for (SearchResult result : results) {
                if (result != null) {
                    converted.add(
                        new HorizonRadioScreen.SearchResult(
                            result.getVideoId(),
                            result.getTitle(),
                            result.getChannel(),
                            result.getDuration(),
                            result.getThumbnail()));
                }
            }
        }
        return converted;
    }

    private static List<HorizonRadioScreen.SearchResult> toScreenPlaylistResults(List<SearchResult> results) {
        List<HorizonRadioScreen.SearchResult> converted = new ArrayList<HorizonRadioScreen.SearchResult>();
        if (results == null) {
            return converted;
        }
        for (SearchResult result : results) {
            if (result == null) {
                continue;
            }
            converted.add(
                new HorizonRadioScreen.SearchResult(
                    result.getVideoId(),
                    result.getTitle(),
                    result.getChannel(),
                    result.getDuration(),
                    result.getThumbnail()));
            if (converted.size() >= 50) {
                break;
            }
        }
        return converted;
    }

    private static void publishPlaylistResults(List<HorizonRadioScreen.SearchResult> results,
        HorizonRadioScreen screen) {
        CACHED_PLAYLIST_RESULTS.clear();
        if (results != null) {
            CACHED_PLAYLIST_RESULTS.addAll(results);
        }
        if (screen != null) {
            screen.updatePlaylistResults(CACHED_PLAYLIST_RESULTS);
        }
    }

    private static CompletableFuture<SearchResult> resolveChartDuration(final SearchResult chart) {
        if (chart == null) {
            return CompletableFuture.completedFuture(chart);
        }
        long durationMs = durationMillis(chart.getDuration());
        if (isValidChartDuration(chart.getVideoId(), durationMs)) {
            return CompletableFuture.completedFuture(chart);
        }
        if (durationMs > 0L) {
            return CompletableFuture.completedFuture(chartWithDuration(chart, "--:--"));
        }
        if (clientMetadataCache == null) {
            return CompletableFuture.completedFuture(chartWithDuration(chart, "--:--"));
        }
        try {
            CompletableFuture<SearchResult> lookup = clientMetadataCache.video(chart.getVideoId());
            if (lookup == null) {
                return CompletableFuture.completedFuture(chartWithDuration(chart, "--:--"));
            }
            return resolveChartDurationFromLookup(chart, lookup);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(chartWithDuration(chart, "--:--"));
        }
    }

    private static CompletableFuture<SearchResult> resolveChartDurationFromLookup(final SearchResult chart,
        CompletableFuture<SearchResult> lookup) {
        return lookup.handle(new BiFunction<SearchResult, Throwable, SearchResult>() {

            @Override
            public SearchResult apply(SearchResult metadata, Throwable failure) {
                if (failure == null && metadata != null
                    && isValidChartDuration(chart.getVideoId(), durationMillis(metadata.getDuration()))) {
                    return chartWithDuration(chart, metadata.getDuration());
                }
                return chartWithDuration(chart, "--:--");
            }
        });
    }

    private static CompletableFuture<List<SearchResult>> resolveChartDurations(List<SearchResult> charts) {
        if (charts == null) {
            return CompletableFuture.completedFuture(new ArrayList<SearchResult>());
        }
        final List<CompletableFuture<SearchResult>> resolved = new ArrayList<CompletableFuture<SearchResult>>();
        for (SearchResult chart : charts) {
            resolved.add(resolveChartDuration(chart));
        }
        CompletableFuture<?>[] futures = resolved.toArray(new CompletableFuture<?>[resolved.size()]);
        return CompletableFuture.allOf(futures)
            .thenApply(ignored -> {
                List<SearchResult> enriched = new ArrayList<SearchResult>();
                for (CompletableFuture<SearchResult> future : resolved) {
                    enriched.add(future.getNow(null));
                }
                return enriched;
            });
    }

    private static SearchResult chartWithDuration(SearchResult chart, String duration) {
        return new SearchResult(
            chart.getVideoId(),
            chart.getTitle(),
            chart.getChannel(),
            duration,
            chart.getThumbnail());
    }

    public static synchronized void updateRadioSearchResults(List<RadioStation> stations) {
        CACHED_RADIO_RESULTS.clear();
        if (stations != null) {
            CACHED_RADIO_RESULTS.addAll(stations);
        }
        List<HorizonRadioScreen.RadioStationResult> results = new ArrayList<HorizonRadioScreen.RadioStationResult>();
        for (RadioStation station : CACHED_RADIO_RESULTS) {
            if (station != null) {
                results.add(new HorizonRadioScreen.RadioStationResult(station.getStationUuid(), station.getName()));
            }
        }
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateRadioResults(results);
        }
    }

    private static void showSearchError() {
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.showSearchError();
        }
    }

    private static void showChartError() {
        chartRequestPending = false;
        chartRequestScreen = null;
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.showChartError();
        }
    }

    private static void showRadioError() {
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.showRadioError();
        }
    }

    private static boolean containsSearchResult(List<?> selections) {
        for (Object selection : selections) {
            if (selection instanceof HorizonRadioScreen.SearchResult) {
                return true;
            }
        }
        return false;
    }

    private static void resolveAndSendSelections(final List<?> selections, final QueueSelectionOrigin origin) {
        final List<CompletableFuture<ChartActionResolution>> resolutions = new ArrayList<CompletableFuture<ChartActionResolution>>();
        for (Object selection : selections) {
            resolutions.add(resolveChartSelection(selection));
        }
        CompletableFuture<?>[] futureArray = resolutions.toArray(new CompletableFuture<?>[resolutions.size()]);
        CompletableFuture.allOf(futureArray)
            .whenComplete(new BiConsumer<Void, Throwable>() {

                @Override
                public void accept(final Void ignored, final Throwable failure) {
                    ClientProxy.scheduleOnClientThread(new Runnable() {

                        @Override
                        public void run() {
                            synchronized (HorizonRadioClient.class) {
                                List<PlaylistSelection> mapped = new ArrayList<PlaylistSelection>();
                                List<String> failedIds = new ArrayList<String>();
                                if (failure != null) {
                                    for (Object selection : selections) {
                                        String videoId = chartSelectionVideoId(selection);
                                        if (videoId != null) {
                                            failedIds.add(videoId);
                                        }
                                    }
                                    clearPendingAdds(origin, failedIds);
                                    debugChat(addFailureMessage(origin, failureMessage(failure, null)));
                                    return;
                                }
                                for (CompletableFuture<ChartActionResolution> future : resolutions) {
                                    ChartActionResolution resolution = future.getNow(null);
                                    if (resolution != null && resolution.selection != null) {
                                        mapped.add(resolution.selection);
                                        updateCachedResultDuration(resolution.videoId, resolution.metadata);
                                    } else if (resolution != null && resolution.videoId != null) {
                                        failedIds.add(resolution.videoId);
                                        debugChat(
                                            addItemFailureMessage(
                                                origin,
                                                resolution.videoId,
                                                failureMessage(null, resolution)));
                                    }
                                }
                                clearPendingAdds(origin, failedIds);
                                if (!mapped.isEmpty()) {
                                    if (playbackMode == PlaybackMode.PRIVATE) {
                                        applyPrivateSelections(mapped, false);
                                        clearPendingAdds(origin, selectionVideoIds(mapped));
                                    } else {
                                        transport.sendAddChartSelections(mapped, false);
                                        awaitPendingAddResolution(origin, mapped);
                                    }
                                    debugChat(addSuccessMessage(origin, mapped.size()));
                                }
                            }
                        }
                    });
                }
            });
    }

    private static CompletableFuture<ChartActionResolution> resolveChartSelection(Object selection) {
        if (selection instanceof PlaylistSelection) {
            PlaylistSelection item = (PlaylistSelection) selection;
            return CompletableFuture.completedFuture(
                isValidChartDuration(item.videoId, item.durationMs)
                    ? ChartActionResolution.success(item.videoId, item, null)
                    : ChartActionResolution.failure(item.videoId, "ungültige oder zu lange Dauer"));
        }
        if (!(selection instanceof HorizonRadioScreen.SearchResult)) {
            return CompletableFuture.completedFuture(ChartActionResolution.failure(null, "ungültiger Chart-Eintrag"));
        }
        final HorizonRadioScreen.SearchResult result = (HorizonRadioScreen.SearchResult) selection;
        if (!isValidSelection(result.videoId, 1L)) {
            return CompletableFuture
                .completedFuture(ChartActionResolution.failure(result.videoId, "ungültige Video-ID"));
        }
        long advertisedDurationMs = durationMillis(result.duration);
        if (advertisedDurationMs > 0L) {
            return CompletableFuture.completedFuture(
                isValidChartDuration(result.videoId, advertisedDurationMs)
                    ? ChartActionResolution
                        .success(result.videoId, new PlaylistSelection(result.videoId, advertisedDurationMs), null)
                    : ChartActionResolution.failure(result.videoId, "Dauer überschreitet das konfigurierte Limit"));
        }
        if (clientMetadataCache == null) {
            return CompletableFuture.completedFuture(
                ChartActionResolution.failure(result.videoId, "lokaler Metadaten-Dienst ist nicht verfügbar"));
        }
        debugChat("Lade Chart-Dauer lokal: " + result.videoId);
        try {
            CompletableFuture<SearchResult> lookup = clientMetadataCache.video(result.videoId);
            if (lookup == null) {
                return CompletableFuture
                    .completedFuture(ChartActionResolution.failure(result.videoId, "keine Metadaten verfügbar"));
            }
            return lookup.handle(new BiFunction<SearchResult, Throwable, ChartActionResolution>() {

                @Override
                public ChartActionResolution apply(SearchResult metadata, Throwable failure) {
                    if (failure != null) {
                        return ChartActionResolution.failure(result.videoId, failureMessage(failure, null));
                    }
                    if (metadata == null) {
                        return ChartActionResolution.failure(result.videoId, "keine Metadaten verfügbar");
                    }
                    long durationMs = durationMillis(metadata.getDuration());
                    if (!isValidChartDuration(result.videoId, durationMs)) {
                        return ChartActionResolution.failure(result.videoId, "keine gültige endliche Dauer");
                    }
                    return ChartActionResolution
                        .success(result.videoId, new PlaylistSelection(result.videoId, durationMs), metadata);
                }
            });
        } catch (RuntimeException exception) {
            return CompletableFuture
                .completedFuture(ChartActionResolution.failure(result.videoId, failureMessage(exception, null)));
        }
    }

    private static String addFailureMessage(QueueSelectionOrigin origin, String message) {
        return origin == QueueSelectionOrigin.PLAYLIST ? "Playlist-Hinzufügen fehlgeschlagen: " + message
            : "Chart-Hinzufügen fehlgeschlagen: " + message;
    }

    private static String addItemFailureMessage(QueueSelectionOrigin origin, String videoId, String message) {
        return (origin == QueueSelectionOrigin.PLAYLIST ? "Playlist konnte nicht hinzugefügt werden: "
            : "Chart konnte nicht hinzugefügt werden: ") + videoId + " (" + message + ")";
    }

    private static String addSuccessMessage(QueueSelectionOrigin origin, int count) {
        return (origin == QueueSelectionOrigin.PLAYLIST ? "Playlist-Auswahl lokal aufgelöst: "
            : "Chart-Auswahl lokal aufgelöst: ") + count + " Titel.";
    }

    private static boolean isValidChartDuration(String videoId, long durationMs) {
        return isValidSelection(videoId, durationMs) && durationMs < maxTrackDurationMs();
    }

    static synchronized void onChartScreenClosed(HorizonRadioScreen screen) {
        if (screen != null && chartRequestPending && chartRequestScreen == screen) {
            chartGeneration++;
            chartRequestPending = false;
            chartRequestScreen = null;
        }
    }

    static synchronized void onPlaylistScreenClosed(HorizonRadioScreen screen) {
        if (screen != null && playlistImportScreen == screen) {
            playlistImportGeneration++;
            playlistImportScreen = null;
        }
    }

    private static boolean isCurrentChartRequest(long generation, HorizonRadioScreen originatingScreen) {
        if (generation != chartGeneration) {
            return false;
        }
        if (originatingScreen == getOpenScreen()) {
            return true;
        }
        chartGeneration++;
        chartRequestPending = false;
        chartRequestScreen = null;
        return false;
    }

    private static boolean isCurrentPlaylistImport(long generation, HorizonRadioScreen originatingScreen) {
        return generation == playlistImportGeneration && originatingScreen != null
            && originatingScreen == playlistImportScreen
            && originatingScreen == getOpenScreen();
    }

    private static String chartSelectionVideoId(Object selection) {
        if (selection instanceof PlaylistSelection) {
            return ((PlaylistSelection) selection).videoId;
        }
        if (selection instanceof HorizonRadioScreen.SearchResult) {
            return ((HorizonRadioScreen.SearchResult) selection).videoId;
        }
        return null;
    }

    private static String failureMessage(Throwable failure, ChartActionResolution resolution) {
        if (resolution != null && resolution.failureMessage != null && resolution.failureMessage.length() > 0) {
            return resolution.failureMessage;
        }
        if (failure != null && failure.getMessage() != null
            && failure.getMessage()
                .length() > 0) {
            return failure.getMessage();
        }
        return "Dauer konnte nicht ermittelt werden";
    }

    private static List<PlaylistSelection> toPlaylistSelections(List<?> selections) {
        List<PlaylistSelection> mapped = new ArrayList<PlaylistSelection>();
        if (selections == null) {
            return mapped;
        }
        for (Object selection : selections) {
            if (selection instanceof PlaylistSelection) {
                PlaylistSelection item = (PlaylistSelection) selection;
                if (isValidSelection(item.videoId, item.durationMs)) {
                    mapped.add(item);
                }
            } else if (selection instanceof HorizonRadioScreen.SearchResult) {
                HorizonRadioScreen.SearchResult result = (HorizonRadioScreen.SearchResult) selection;
                long durationMs = durationMillis(result.duration);
                if (isValidSelection(result.videoId, durationMs)) {
                    mapped.add(new PlaylistSelection(result.videoId, durationMs));
                }
            }
        }
        return mapped;
    }

    private static void sendAddSelection(PlaylistSelection selection) {
        if (playbackMode == PlaybackMode.PRIVATE) {
            if (LOCAL_QUEUE.add(PlaylistEntry.youtube(selection.videoId, selection.durationMs, "Private"))) {
                refreshCachedPlaylistFromActiveQueue();
            }
            return;
        }
        transport.sendAdd(selection.videoId, selection.durationMs);
    }

    private static void sendPlayNowSelection(PlaylistSelection selection) {
        if (playbackMode == PlaybackMode.PRIVATE) {
            PlaylistEntry prepared = LOCAL_QUEUE.prepareImmediatePlayback(
                PlaylistEntry.youtube(selection.videoId, selection.durationMs, "Private"));
            if (prepared != null) {
                startPrivateFinite(prepared, 0L, false, System.currentTimeMillis());
                refreshCachedPlaylistFromActiveQueue();
            }
            return;
        }
        transport.sendPlayNow(selection.videoId, selection.durationMs);
    }

    private static void startNextPrivateEntry(int index, long clientNowMs) {
        if (index >= 0 && index < LOCAL_QUEUE.size()) {
            startPrivateFinite(LOCAL_QUEUE.get(index), 0L, false, clientNowMs);
        } else {
            invalidateAndStopPrivatePlayback(true);
        }
    }

    private static void startPrivateFinite(final PlaylistEntry entry, long positionMs, boolean paused,
        long clientNowMs) {
        if (playbackMode != PlaybackMode.PRIVATE || entry == null || !entry.isFinite()) {
            return;
        }
        int index = LOCAL_QUEUE.findIndex(entry.getSourceType(), entry.getSourceId());
        if (index < 0) {
            return;
        }

        final long generation = ++localPlaybackGeneration;
        stopLocalPlayback(generation);
        long maximumPositionMs = Math.max(0L, entry.getDurationMs() - 1L);
        long safePositionMs = Math.max(0L, Math.min(maximumPositionMs, positionMs));
        long playbackStartAtMs = clientNowMs - safePositionMs;
        LOCAL_QUEUE.startFiniteTrack(index, playbackStartAtMs);
        if (paused) {
            LOCAL_QUEUE.pausePlayback(safePositionMs, clientNowMs);
        }

        final String videoId = entry.getSourceId();
        activeTrackSourceType = MediaSourceType.YOUTUBE;
        activeTrackSourceId = videoId;
        activeTrackVideoId = videoId;
        activeTrackGeneration = generation;
        activeTrackPositionMs = safePositionMs;
        activeTrackStartAtMs = paused ? 0L : clientNowMs;
        activeTrackDurationMs = entry.getDurationMs();
        cachedPaused = paused;
        updatePrivateFinitePresentation(entry, safePositionMs);

        if (clientRadioPlayback != null) {
            clientRadioPlayback.stop();
        }
        AudioPlayer.getInstance()
            .stopRadio();
        AudioPlayer.getInstance()
            .beginLocalTrack(videoId, safePositionMs, paused ? 0L : clientNowMs, paused);
        requestActiveVideoMetadata(generation, videoId);

        if (clientAudioDownloadService == null) {
            failPrivateFiniteStart(generation, videoId, "Kein lokaler Audio-Downloader verfügbar.");
            return;
        }

        CompletableFuture<Path> download;
        try {
            download = clientAudioDownloadService.download(videoId);
        } catch (RuntimeException exception) {
            failPrivateFiniteStart(generation, videoId, "Lokaler Download konnte nicht gestartet werden: " + videoId);
            return;
        }
        if (download == null) {
            failPrivateFiniteStart(generation, videoId, "Lokaler Downloader lieferte keinen Download für: " + videoId);
            return;
        }

        download.whenComplete(new BiConsumer<Path, Throwable>() {

            @Override
            public void accept(final Path filePath, final Throwable failure) {
                ClientProxy.scheduleOnClientThread(new Runnable() {

                    @Override
                    public void run() {
                        synchronized (HorizonRadioClient.class) {
                            if (!shouldAcceptPrivateAudioCompletion(
                                playbackMode,
                                localPlaybackGeneration,
                                generation,
                                activeTrackSourceId,
                                videoId)) {
                                return;
                            }
                            if (failure != null || filePath == null || !Files.isRegularFile(filePath)) {
                                failPrivateFiniteStart(
                                    generation,
                                    videoId,
                                    "Lokaler Audio-Download fehlgeschlagen: " + videoId);
                                return;
                            }
                            AudioPlayer.getInstance()
                                .loadLocalTrack(videoId, filePath);
                            debugChat("Track " + videoId + " lokal bereit.");
                        }
                    }
                });
            }
        });
    }

    private static void failPrivateFiniteStart(long generation, String videoId, String message) {
        if (!shouldAcceptPrivateAudioCompletion(
            playbackMode,
            localPlaybackGeneration,
            generation,
            activeTrackSourceId,
            videoId)) {
            return;
        }
        debugChat(message);
        invalidateAndStopPrivatePlayback(true);
        refreshCachedPlaylistFromActiveQueue();
    }

    private static void invalidateAndStopPrivatePlayback(boolean resetQueuePlayback) {
        long generation = ++localPlaybackGeneration;
        if (resetQueuePlayback) {
            LOCAL_QUEUE.resetPlayback();
        }
        stopLocalPlayback(generation);
    }

    private static void alignPrivateFiniteAudio(long positionMs, boolean paused) {
        activeTrackPositionMs = positionMs;
        activeTrackStartAtMs = paused ? 0L : System.currentTimeMillis();
        AudioPlayer.getInstance()
            .pause(positionMs);
        if (!paused) {
            AudioPlayer.getInstance()
                .resume(positionMs, 0L);
        }
    }

    private static void updatePrivateFinitePresentation(PlaylistEntry entry, long positionMs) {
        SearchResult metadata = clientMetadataCache == null ? null : clientMetadataCache.getVideo(entry.getSourceId());
        String title = metadata == null ? null : metadata.getTitle();
        cachedNowPlaying = title == null || title.trim()
            .length() == 0 ? entry.getSourceId() : title;
        activeTrackDurationMs = entry.getDurationMs();
        activeTrackPositionMs = Math.max(0L, positionMs);
        cachedPaused = LOCAL_QUEUE.isPaused();
        cachedProgress = entry.getDurationMs() <= 0L ? 0.0f
            : Math.max(0.0f, Math.min(1.0f, (float) positionMs / (float) entry.getDurationMs()));
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateNowPlaying(cachedNowPlaying, cachedProgress);
            screen.updatePlaybackPaused(cachedPaused);
        }
    }

    private static void applyPrivateSelections(List<PlaylistSelection> selections, boolean remove) {
        boolean changed = false;
        if (selections != null) {
            for (PlaylistSelection selection : selections) {
                if (selection == null) {
                    continue;
                }
                if (remove) {
                    changed |= LOCAL_QUEUE.remove(MediaSourceType.YOUTUBE, selection.videoId) >= 0;
                } else {
                    changed |= LOCAL_QUEUE.add(PlaylistEntry.youtube(selection.videoId, selection.durationMs, "Private"));
                }
            }
        }
        if (changed) {
            refreshCachedPlaylistFromActiveQueue();
        }
    }

    private static void removePrivateSelectionIds(List<?> selections) {
        boolean changed = false;
        if (selections != null) {
            for (Object selection : selections) {
                String videoId = chartSelectionVideoId(selection);
                changed |= videoId != null && LOCAL_QUEUE.remove(MediaSourceType.YOUTUBE, videoId) >= 0;
            }
        }
        if (changed) {
            refreshCachedPlaylistFromActiveQueue();
        }
    }

    private static List<String> selectionVideoIds(List<PlaylistSelection> selections) {
        List<String> videoIds = new ArrayList<String>();
        if (selections != null) {
            for (PlaylistSelection selection : selections) {
                if (selection != null && selection.videoId != null) {
                    videoIds.add(selection.videoId);
                }
            }
        }
        return videoIds;
    }

    private static boolean isValidSelection(String videoId, long durationMs) {
        return videoId != null && videoId.trim()
            .length() > 0 && durationMs > 0L;
    }

    private static long maxTrackDurationMs() {
        int minutes = HorizonRadio.getConfig() == null ? HorizonRadioConfig.DEFAULT_MAX_TRACK_DURATION_MINUTES
            : HorizonRadio.getConfig()
                .getMaxTrackDurationMinutes();
        return (long) minutes * 60L * 1000L;
    }

    private static long durationMillis(String duration) {
        return DurationParser.parseMillisStrict(duration);
    }

    private static String formatDuration(long durationMs) {
        if (durationMs <= 0L) {
            return "";
        }
        long seconds = durationMs / 1000L;
        return seconds / 60L + ":" + (seconds % 60L < 10L ? "0" : "") + seconds % 60L;
    }

    private static final class PendingAddResolution {

        private final HorizonRadioScreen screen;
        private final boolean playlistOrigin;
        private final Set<String> videoIds;

        private PendingAddResolution(HorizonRadioScreen screen, boolean playlistOrigin, Set<String> videoIds) {
            this.screen = screen;
            this.playlistOrigin = playlistOrigin;
            this.videoIds = videoIds;
        }
    }

    private static final class ChartActionResolution {

        private final String videoId;
        private final PlaylistSelection selection;
        private final SearchResult metadata;
        private final String failureMessage;

        private ChartActionResolution(String videoId, PlaylistSelection selection, SearchResult metadata,
            String failureMessage) {
            this.videoId = videoId;
            this.selection = selection;
            this.metadata = metadata;
            this.failureMessage = failureMessage;
        }

        private static ChartActionResolution success(String videoId, PlaylistSelection selection,
            SearchResult metadata) {
            return new ChartActionResolution(videoId, selection, metadata, null);
        }

        private static ChartActionResolution failure(String videoId, String message) {
            return new ChartActionResolution(videoId, null, null, message);
        }
    }

    static final class PlaylistSelection {

        final String videoId;
        final long durationMs;

        PlaylistSelection(String videoId, long durationMs) {
            if (!isValidSelection(videoId, durationMs)) {
                throw new IllegalArgumentException("playlist selection requires an ID and finite duration");
            }
            this.videoId = videoId;
            this.durationMs = durationMs;
        }
    }

    private enum QueueSelectionOrigin {
        CHARTS,
        PLAYLIST
    }
}
