package com.horizonradio.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.horizonradio.client.audio.AudioPlayer;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.core.server.ChartRegionCatalog;
import com.horizonradio.network.HorizonRadioNetwork;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket.Entry;
import com.horizonradio.network.packets.AddToPlaylistPacket;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.ClearPlaylistPacket;
import com.horizonradio.network.packets.ImportPlaylistPacket;
import com.horizonradio.network.packets.ImportVideoPacket;
import com.horizonradio.network.packets.PlayNowPacket;
import com.horizonradio.network.packets.PreviousTrackPacket;
import com.horizonradio.network.packets.RadioAudioChunkPacket;
import com.horizonradio.network.packets.RadioAudioStartPacket;
import com.horizonradio.network.packets.RadioSearchRequestPacket;
import com.horizonradio.network.packets.RadioSearchResultsPacket;
import com.horizonradio.network.packets.RadioStatePacket;
import com.horizonradio.network.packets.ReadyPacket;
import com.horizonradio.network.packets.RemoveFromPlaylistPacket;
import com.horizonradio.network.packets.ReorderPlaylistPacket;
import com.horizonradio.network.packets.RequestChartsPacket;
import com.horizonradio.network.packets.SearchRequestPacket;
import com.horizonradio.network.packets.SeekRequestPacket;
import com.horizonradio.network.packets.SelectRadioStationPacket;
import com.horizonradio.network.packets.SkipTrackPacket;
import com.horizonradio.network.packets.StopRadioPacket;
import com.horizonradio.network.packets.ToggleLoopPacket;
import com.horizonradio.network.packets.TogglePlaybackPacket;
import com.horizonradio.network.packets.ToggleShufflePacket;

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
    private static RadioStatePacket cachedRadioState;
    private static long cachedChartsAt;
    private static boolean chartRequestPending;
    private static String cachedChartRegionCode = "";
    private static String pendingChartRegionCode = "";
    private static String lastRequestedChartRegionCode;
    private static ClientTransport transport = new NoopClientTransport();
    private static HorizonRadioClientConfig clientConfig;

    private HorizonRadioClient() {}

    public interface ClientTransport {

        void sendSearch(String query);

        void sendChartsRequest(boolean forceRefresh);

        default void sendChartsRequest(String regionCode, boolean forceRefresh) {
            sendChartsRequest(forceRefresh);
        }

        void sendImportPlaylist(String playlistUrl);

        void sendImportVideo(String videoUrl);

        void sendAdd(String videoId, String title, String duration);

        void sendPlayNow(String videoId, String title, String duration);

        void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results);

        default void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results, boolean remove) {
            sendAddChartsToPlaylist(results);
        }

        void sendRemove(String videoId);

        void sendClearPlaylist();

        void sendReady(String videoId);

        void sendReorder(int fromIndex, int targetIndex);

        void sendSeek(float progress);

        void sendTogglePlayback();

        void sendSkipTrack();

        void sendPreviousTrack();

        void sendToggleLoop();

        void sendToggleShuffle();

        void sendRadioSearch(String query);

        void sendSelectRadio(String stationUuid);

        void sendStopRadio();
    }

    /** Forge transport for the four client-to-server protocol messages. */
    public static final class ForgeClientTransport implements ClientTransport {

        @Override
        public void sendSearch(String query) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new SearchRequestPacket(query));
        }

        @Override
        public void sendChartsRequest(boolean forceRefresh) {
            sendChartsRequest(ChartRegionCatalog.GLOBAL_CODE, forceRefresh);
        }

        @Override
        public void sendChartsRequest(String regionCode, boolean forceRefresh) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new RequestChartsPacket(regionCode, forceRefresh));
        }

        @Override
        public void sendImportPlaylist(String playlistUrl) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new ImportPlaylistPacket(playlistUrl));
        }

        @Override
        public void sendImportVideo(String videoUrl) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new ImportVideoPacket(videoUrl));
        }

        @Override
        public void sendAdd(String videoId, String title, String duration) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new AddToPlaylistPacket(videoId, title, duration));
        }

        @Override
        public void sendPlayNow(String videoId, String title, String duration) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new PlayNowPacket(videoId, title, duration));
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
        public void sendRemove(String videoId) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new RemoveFromPlaylistPacket(videoId));
        }

        @Override
        public void sendClearPlaylist() {
            HorizonRadioNetwork.CHANNEL.sendToServer(new ClearPlaylistPacket());
        }

        @Override
        public void sendReady(String videoId) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new ReadyPacket(videoId));
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
        public void sendRadioSearch(String query) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new RadioSearchRequestPacket(query));
        }

        @Override
        public void sendSelectRadio(String stationUuid) {
            HorizonRadioNetwork.CHANNEL.sendToServer(new SelectRadioStationPacket(stationUuid));
        }

        @Override
        public void sendStopRadio() {
            HorizonRadioNetwork.CHANNEL.sendToServer(new StopRadioPacket());
        }
    }

    /** No-op transport retained for common tests and before client initialization. */
    public static final class NoopClientTransport implements ClientTransport {

        @Override
        public void sendSearch(String query) {}

        @Override
        public void sendChartsRequest(boolean forceRefresh) {}

        @Override
        public void sendImportPlaylist(String playlistUrl) {}

        @Override
        public void sendImportVideo(String videoUrl) {}

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
        public void sendReady(String videoId) {}

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
        public void sendRadioSearch(String query) {}

        @Override
        public void sendSelectRadio(String stationUuid) {}

        @Override
        public void sendStopRadio() {}
    }

    public static synchronized void setTransport(ClientTransport clientTransport) {
        transport = clientTransport == null ? new NoopClientTransport() : clientTransport;
    }

    public static synchronized void sendSearch(String query) {
        transport.sendSearch(query);
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
        transport.sendChartsRequest(canonicalRegionCode, forceRefresh);
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
        transport.sendImportPlaylist(playlistUrl);
    }

    public static synchronized void sendImportVideo(String videoUrl) {
        transport.sendImportVideo(videoUrl);
    }

    public static synchronized void sendAdd(String videoId, String title, String duration) {
        transport.sendAdd(videoId, title, duration);
    }

    public static synchronized void sendPlayNow(String videoId, String title, String duration) {
        transport.sendPlayNow(videoId, title, duration);
    }

    public static synchronized void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results) {
        sendAddChartsToPlaylist(results, false);
    }

    public static synchronized void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results,
        boolean remove) {
        transport.sendAddChartsToPlaylist(results, remove);
    }

    public static synchronized void sendRemove(String videoId) {
        transport.sendRemove(videoId);
    }

    public static synchronized void sendClearPlaylist() {
        transport.sendClearPlaylist();
    }

    public static synchronized void sendReady(String videoId) {
        transport.sendReady(videoId);
    }

    public static synchronized void sendReorder(int fromIndex, int targetIndex) {
        transport.sendReorder(fromIndex, targetIndex);
    }

    public static synchronized void sendSeek(float progress) {
        transport.sendSeek(progress);
    }

    public static synchronized void sendTogglePlayback() {
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
        transport.sendRadioSearch(query);
    }

    public static synchronized void sendSelectRadio(String stationUuid) {
        transport.sendSelectRadio(stationUuid);
    }

    public static synchronized void sendStopRadio() {
        transport.sendStopRadio();
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

    public static synchronized RadioStatePacket getCachedRadioState() {
        return cachedRadioState;
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

    public static synchronized void updateRadioState(RadioStatePacket packet) {
        boolean wasRadioActive = cachedRadioActive;
        cachedRadioState = packet;
        cachedRadioActive = packet != null && packet.isActive();
        if (cachedRadioActive || wasRadioActive || hasRadioStatus(packet)) {
            clearCachedMusicState();
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
            screen.updateRadioState(packet);
        }
    }

    public static synchronized boolean handleRadioAudioStart(RadioAudioStartPacket packet) {
        if (!shouldAcceptRadioAudioStart(cachedRadioState, packet)) {
            return false;
        }
        return AudioPlayer.getInstance()
            .startRadio(packet);
    }

    static boolean shouldAcceptRadioAudioStart(RadioStatePacket state, RadioAudioStartPacket packet) {
        return state != null && state.isActive() && packet != null && state.getGeneration() == packet.getGeneration();
    }

    public static synchronized void handleRadioAudioChunk(RadioAudioChunkPacket packet) {
        AudioPlayer.getInstance()
            .receiveRadioChunk(packet);
    }

    public static synchronized void updateNowPlaying(String title, float progress) {
        if (cachedRadioActive) {
            return;
        }
        cachedNowPlaying = title == null || title.length() == 0 ? null : title;
        cachedProgress = Math.max(0.0f, Math.min(1.0f, progress));
        if (cachedNowPlaying == null) {
            cachedPaused = false;
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

    public static synchronized void handlePause(long positionMs) {
        cachedPaused = true;
        AudioPlayer.getInstance()
            .pause(positionMs);
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updatePlaybackPaused(true);
        }
    }

    public static synchronized void handleResume(long positionMs) {
        cachedPaused = false;
        AudioPlayer.getInstance()
            .resume(positionMs);
        HorizonRadioScreen screen = getOpenScreen();
        if (screen != null) {
            screen.updatePlaybackPaused(false);
        }
    }

    public static synchronized void clearCache() {
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
        cachedRadioState = null;
        AudioPlayer.getInstance()
            .stop();
        AudioPlayer.getInstance()
            .resetRadio();
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

    private static boolean hasRadioStatus(RadioStatePacket packet) {
        return packet != null && packet.getStatus() != null
            && packet.getStatus()
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
}
