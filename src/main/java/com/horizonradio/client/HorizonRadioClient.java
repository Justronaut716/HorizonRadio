package com.horizonradio.client;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import com.horizonradio.client.audio.AudioPlayer;
import com.horizonradio.client.audio.ClientRadioPlayback;
import com.horizonradio.client.audio.PlaybackClock;
import com.horizonradio.client.media.ClientMediaService;
import com.horizonradio.client.media.ClientMetadataCache;
import com.horizonradio.HorizonRadio;
import com.horizonradio.core.client.ClientQueueState;
import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.core.model.DurationParser;
import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.core.server.ChartRegionCatalog;
import com.horizonradio.network.HorizonRadioNetwork;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket.Entry;
import com.horizonradio.network.packets.AddToPlaylistPacket;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.ClearPlaylistPacket;
import com.horizonradio.network.packets.ClockSyncRequestPacket;
import com.horizonradio.network.packets.ClockSyncResponsePacket;
import com.horizonradio.network.packets.PlayNowPacket;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistResyncRequestPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.PreviousTrackPacket;
import com.horizonradio.network.packets.RadioSearchResultsPacket;
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
    private static final List<RadioSearchResultsPacket.Entry> CACHED_RADIO_RESULTS = new ArrayList<RadioSearchResultsPacket.Entry>();
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
    private static ClientTransport transport = new NoopClientTransport();
    private static HorizonRadioClientConfig clientConfig;
    private static AudioDownloadService clientAudioDownloadService;
    private static ClientMediaService clientMediaService;
    private static ClientMetadataCache clientMetadataCache;
    private static final Set<String> requestedVideoMetadata = new HashSet<String>();
    private static final Set<String> requestedStationMetadata = new HashSet<String>();
    private static final ClientQueueState CLIENT_QUEUE = new ClientQueueState();
    private static boolean playlistResyncRequested;
    private static long searchTabDiscoveryGeneration;
    private static long chartGeneration;
    private static long radioSearchGeneration;
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
                    legacy.add(new HorizonRadioScreen.SearchResult(
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
        pendingChartRegionCode = canonicalRegionCode;
        lastRequestedChartRegionCode = canonicalRegionCode;
        chartRequestPending = true;
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
                    ClientProxy.scheduleOnClientThread(new Runnable() {

                        @Override
                        public void run() {
                            synchronized (HorizonRadioClient.class) {
                                if (generation != chartGeneration) {
                                    return;
                                }
                                if (failure != null) {
                                    showChartError();
                                } else {
                                    updateChartResults(toScreenResults(results), canonicalRegionCode);
                                }
                            }
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
            if (transport instanceof ForgeClientTransport) {
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

    /** Compatibility overload retained for older GUI and transport tests. */
    @Deprecated
    public static synchronized void sendPlayNow(String videoId, String title, String duration) {
        long durationMs = durationMillis(duration);
        if (isValidSelection(videoId, durationMs)) {
            PlaylistSelection selection = new PlaylistSelection(videoId, durationMs);
            if (transport instanceof ForgeClientTransport) {
                sendPlayNowSelection(selection);
            } else {
                transport.sendPlayNow(videoId, title, duration);
            }
        }
    }

    public static synchronized void sendAddChartsToPlaylist(List<?> selections) {
        sendAddChartsToPlaylist(selections, false);
    }

    public static synchronized void sendAddChartsToPlaylist(List<?> selections,
        boolean remove) {
        List<PlaylistSelection> mapped = toPlaylistSelections(selections);
        transport.sendAddChartSelections(mapped, remove);
    }

    public static synchronized void sendRemove(String videoId) {
        transport.sendRemove(videoId);
    }

    public static synchronized void sendClearPlaylist() {
        transport.sendClearPlaylist();
    }

    public static synchronized void sendReorder(int fromIndex, int targetIndex) {
        transport.sendReorder(fromIndex, targetIndex);
    }

    public static synchronized void sendSeek(float progress) {
        if (activeTrackSourceType == MediaSourceType.RADIO) {
            return;
        }
        transport.sendSeek(progress);
    }

    public static synchronized void sendTogglePlayback() {
        if (activeTrackSourceType == MediaSourceType.RADIO) {
            return;
        }
        transport.sendTogglePlayback();
    }

    public static synchronized void updateLooping(boolean looping) {
        cachedLooping = looping;
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateLooping(looping);
        }
    }

    public static synchronized void sendSkipTrack() {
        transport.sendSkipTrack();
    }

    public static synchronized void sendPreviousTrack() {
        transport.sendPreviousTrack();
    }

    public static synchronized void sendToggleLoop() {
        transport.sendToggleLoop();
    }

    public static synchronized void sendToggleShuffle() {
        transport.sendToggleShuffle();
    }

    public static synchronized void sendRadioSearch(String query) {
        if (clientMediaService == null) {
            updateRadioStations(null);
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
                                    updateRadioStations(stations);
                                }
                            }
                        }
                    });
                }
            });
    }

    public static synchronized void sendSelectRadio(String stationUuid) {
        transport.sendSelectRadio(stationUuid);
    }

    public static synchronized void sendStopRadio() {
        transport.sendStopRadio();
    }

    public static synchronized void sendClockSync() {
        transport.sendClockSync(System.currentTimeMillis());
    }

    public static synchronized List<HorizonRadioScreen.PlaylistEntry> getCachedPlaylist() {
        return new ArrayList<HorizonRadioScreen.PlaylistEntry>(CACHED_PLAYLIST);
    }

    public static synchronized List<HorizonRadioScreen.SearchResult> getCachedCharts() {
        return new ArrayList<HorizonRadioScreen.SearchResult>(CACHED_CHARTS);
    }

    public static synchronized String getCachedChartRegionCode() {
        return cachedChartRegionCode;
    }

    public static synchronized List<RadioSearchResultsPacket.Entry> getCachedRadioResults() {
        return new ArrayList<RadioSearchResultsPacket.Entry>(CACHED_RADIO_RESULTS);
    }

    public static synchronized ClientRadioPresentation getCachedRadioPresentation() {
        return cachedRadioPresentation;
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
        AudioPlayer.getInstance()
            .setVolume(clientConfig.getVolume());
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
                    .getVolume());
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
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateChartResults(CACHED_CHARTS, cachedChartRegionCode);
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
        if (packet == null) {
            return;
        }
        List<PlaylistEntry> entries = new ArrayList<PlaylistEntry>();
        for (PlaylistSyncPacket.Entry entry : packet.getEntries()) {
            entries.add(PlaylistEntry.of(entry.getSourceType(), entry.getSourceId(), 0L, entry.getAddedBy()));
        }
        CLIENT_QUEUE.applySnapshot(packet.getQueueRevision(), packet.isShuffling(), packet.isLooping(), entries);
        playlistResyncRequested = false;
        cachedShuffling = CLIENT_QUEUE.isShuffling();
        cachedLooping = CLIENT_QUEUE.isLooping();
        stopLocalRadioWhenAbsentFromQueue();
        refreshCachedPlaylistFromQueue();
        updateShuffling(cachedShuffling);
        updateLooping(cachedLooping);
    }

    public static synchronized void handlePlaylistDelta(PlaylistDeltaPacket packet) {
        if (CLIENT_QUEUE.applyDelta(packet)) {
            stopLocalRadioWhenAbsentFromQueue();
            refreshCachedPlaylistFromQueue();
            return;
        }
        requestPlaylistResync();
    }

    public static synchronized void requestPlaylistResync() {
        if (!CLIENT_QUEUE.isSnapshotRequired() || playlistResyncRequested) {
            return;
        }
        playlistResyncRequested = true;
        transport.sendPlaylistResync(CLIENT_QUEUE.getRevision());
    }

    public static synchronized void completeChartAdds(List<String> videoIds) {
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.completeChartAdds(videoIds);
        }
    }

    public static synchronized void updateRadioSearchResults(RadioSearchResultsPacket packet) {
        CACHED_RADIO_RESULTS.clear();
        if (packet != null) {
            CACHED_RADIO_RESULTS.addAll(packet.getEntries());
        }
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateRadioResultsFromPacketEntries(CACHED_RADIO_RESULTS);
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

    public static synchronized void handleAudioChunk(AudioChunkPacket packet) {
        AudioPlayer.getInstance()
            .receiveChunk(packet);
    }

    public static synchronized void handleTrackSync(final TrackSyncPacket packet) {
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
        if (clientAudioDownloadService != null && previousSourceType == MediaSourceType.YOUTUBE && previousVideoId != null
            && (packet.getSourceType() != MediaSourceType.YOUTUBE || !previousVideoId.equals(activeTrackVideoId))) {
            clientAudioDownloadService.cancelDownload(previousVideoId);
        }

        if (packet.getSourceType() == MediaSourceType.RADIO) {
            clearCachedMusicState();
            AudioPlayer.getInstance().stop();
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
        AudioPlayer.getInstance().stopRadio();
        if (cachedRadioActive) {
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
        download.whenComplete(new BiConsumer<Path, Throwable>() {

            @Override
            public void accept(final Path filePath, Throwable failure) {
                ClientProxy.scheduleOnClientThread(new Runnable() {

                    @Override
                    public void run() {
                        synchronized (HorizonRadioClient.class) {
                            if (generation != activeTrackGeneration || activeTrackSourceType != MediaSourceType.YOUTUBE
                                || !videoId.equals(activeTrackVideoId)) {
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
            || packet.getSourceId().trim().length() == 0) {
            return false;
        }
        if (packet.getGeneration() > currentGeneration) {
            return true;
        }
        return packet.getGeneration() == currentGeneration
            && (packet.getSourceType() != currentSourceType || !packet.getSourceId().equals(currentSourceId));
    }

    public static synchronized void handlePause(long positionMs) {
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
        if (packet == null) {
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
        if (clientRadioPlayback != null) {
            clientRadioPlayback.stop();
        }
        cancelActiveTrackDownload();
        CACHED_PLAYLIST.clear();
        CACHED_CHARTS.clear();
        CACHED_RADIO_RESULTS.clear();
        cachedChartsAt = 0L;
        chartRequestPending = false;
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
        CLIENT_QUEUE.applySnapshot(0L, false, false, new ArrayList<PlaylistEntry>());
        playlistResyncRequested = false;
        searchTabDiscoveryGeneration++;
        chartGeneration++;
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
        cancelActiveTrackDownload();
        if (clientRadioPlayback != null) {
            clientRadioPlayback.stop();
        }
        AudioPlayer.getInstance().stop();
        AudioPlayer.getInstance().stopRadio();
        if (cachedRadioActive) {
            updateRadioPresentation(null);
        }
        clearCachedMusicState();
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateNowPlaying(null, 0.0f);
            screen.updatePlaybackPaused(false);
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
        if (!cachedRadioActive || cachedRadioPresentation == null) {
            return;
        }
        for (PlaylistEntry entry : CLIENT_QUEUE.snapshot()) {
            if (entry.isRadio() && cachedRadioPresentation.getStationUuid().equals(entry.getSourceId())) {
                return;
            }
        }
        if (clientRadioPlayback != null) {
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
    }

    /** Keeps local radio failures on the client and ignores stale stream callbacks. */
    public static synchronized void handleLocalRadioFailure(long generation, String stationUuid, String message) {
        if (!isActiveRadio(generation, stationUuid)) {
            return;
        }
        cachedRadioActive = false;
        cachedRadioPresentation = ClientRadioPresentation.stopped(generation, message);
        clearCachedMusicState();
        AudioPlayer.getInstance().stopRadio();
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updateRadioPresentation(cachedRadioPresentation);
        }
    }

    /** Refreshes finite client presentation without generating a progress packet. */
    public static synchronized void onClientTick() {
        refreshLocalFinitePresentation(System.currentTimeMillis());
    }

    static synchronized void refreshLocalFinitePresentation(long clientNowMs) {
        if (activeTrackSourceType != MediaSourceType.YOUTUBE || activeTrackVideoId == null) {
            return;
        }
        SearchResult metadata = clientMetadataCache == null ? null : clientMetadataCache.getVideo(activeTrackVideoId);
        if (metadata != null) {
            if (metadata.getTitle() != null && metadata.getTitle().trim().length() > 0) {
                cachedNowPlaying = metadata.getTitle();
            }
            activeTrackDurationMs = DurationParser.parseMillisStrict(metadata.getDuration());
        }
        if (cachedNowPlaying == null || cachedNowPlaying.length() == 0) {
            cachedNowPlaying = activeTrackVideoId;
        }
        long positionMs = cachedPaused ? activeTrackPositionMs
            : PlaybackClock.finiteTrackPositionMs(
                activeTrackPositionMs,
                activeTrackStartAtMs,
                serverClockOffsetMs,
                clientNowMs);
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
            && stationUuid != null && stationUuid.equals(activeTrackSourceId);
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

    private static void refreshCachedPlaylistFromQueue() {
        CACHED_PLAYLIST.clear();
        for (PlaylistEntry entry : CLIENT_QUEUE.snapshot()) {
            CACHED_PLAYLIST.add(toScreenPlaylistEntry(entry));
        }
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updatePlaylist(CACHED_PLAYLIST);
        }
    }

    private static HorizonRadioScreen.PlaylistEntry toScreenPlaylistEntry(final PlaylistEntry entry) {
        SearchResult video = null;
        RadioStation station = null;
        if (clientMetadataCache != null) {
            if (entry.getSourceType() == MediaSourceType.YOUTUBE) {
                video = clientMetadataCache.getVideo(entry.getSourceId());
                requestVideoMetadata(entry.getSourceId());
            } else {
                station = clientMetadataCache.getStation(entry.getSourceId());
                requestStationMetadata(entry.getSourceId());
            }
        }
        return new HorizonRadioScreen.PlaylistEntry(
            entry.getSourceType(), entry.getSourceId(), entry.getAddedBy(), video, station);
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
                                refreshCachedPlaylistFromQueue();
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
                                if (generation == activeTrackGeneration && activeTrackSourceType == MediaSourceType.YOUTUBE
                                    && videoId.equals(activeTrackVideoId)) {
                                    refreshLocalFinitePresentation(System.currentTimeMillis());
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
                                refreshCachedPlaylistFromQueue();
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
                    converted.add(new HorizonRadioScreen.SearchResult(
                        result.getVideoId(), result.getTitle(), result.getChannel(), result.getDuration(), result.getThumbnail()));
                }
            }
        }
        return converted;
    }

    private static void updateRadioStations(List<RadioStation> stations) {
        List<HorizonRadioScreen.RadioStationResult> results = new ArrayList<HorizonRadioScreen.RadioStationResult>();
        if (stations != null) {
            for (RadioStation station : stations) {
                if (station != null) {
                    results.add(new HorizonRadioScreen.RadioStationResult(station.getStationUuid(), station.getName()));
                }
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
        transport.sendAdd(selection.videoId, selection.durationMs);
    }

    private static void sendPlayNowSelection(PlaylistSelection selection) {
        transport.sendPlayNow(selection.videoId, selection.durationMs);
    }

    private static boolean isValidSelection(String videoId, long durationMs) {
        return videoId != null && videoId.trim().length() > 0 && durationMs > 0L;
    }

    private static long maxTrackDurationMs() {
        int minutes = HorizonRadio.getConfig() == null ? HorizonRadioConfig.DEFAULT_MAX_TRACK_DURATION_MINUTES
            : HorizonRadio.getConfig().getMaxTrackDurationMinutes();
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
}
