package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.util.EnumChatFormatting;

import org.junit.Test;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartCache;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.core.server.ChartRegionCatalog;
import com.horizonradio.network.packets.RadioStatePacket;
import com.horizonradio.network.packets.SearchResultsPacket;

public class PlaylistManagerTest {

    private static final RadioStation STATION_A = new RadioStation(
        "station-a",
        "Station A",
        "https://stream.example/a",
        true,
        false);
    private static final RadioStation STATION_B = new RadioStation(
        "station-b",
        "Station B",
        "https://stream.example/b",
        true,
        false);

    @Test
    public void radioSelectionPromotesOnlyAfterTheCandidateIsReady() {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");

            assertFalse(manager.isRadioActive());
            assertEquals(0, stream.promotions);

            stream.ready();

            assertTrue(manager.isRadioActive());
            assertEquals(1, stream.promotions);
            assertEquals(1, browser.clickCounts);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void failedReplacementCandidatePreservesPublishedRadio() {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready();
            browser.station = STATION_B;

            manager.selectRadioStation(null, "station-b");
            stream.fail("unavailable");

            assertTrue(manager.isRadioActive());
            assertEquals(1, stream.promotions);
            assertEquals(0, stream.stopAllCalls);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void newerFailedSelectionInvalidatesThePriorUnpublishedCandidate() {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            browser.station = STATION_B;
            manager.selectRadioStation(null, "station-b");
            browser.failNextLookup();

            manager.selectRadioStation(null, "station-c");
            stream.ready(1);

            assertFalse(manager.isRadioActive());
            assertEquals(0, stream.promotions);
            assertEquals(2, stream.stopGenerationCalls);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void publishedStationContinuesRelayingWhileReplacementCandidateStarts() throws Exception {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready(0);
            browser.station = STATION_B;
            manager.selectRadioStation(null, "station-b");

            stream.chunk(0, 1L);

            assertTrue(manager.isRadioActive());
            assertEquals(1L, radioLastSequence(manager));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void publishedFailureStopsPendingReplacementCandidate() {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready(0);
            browser.station = STATION_B;
            manager.selectRadioStation(null, "station-b");
            long publishedGeneration = stream.generation(0);
            long pendingCandidateGeneration = stream.generation(1);

            assertTrue(publishedGeneration != pendingCandidateGeneration);

            stream.fail(0, "published stream ended");
            stream.ready(1);

            assertFalse(manager.isRadioActive());
            assertEquals(2, stream.stoppedGenerations.size());
            assertTrue(stream.stoppedGenerations.contains(Long.valueOf(publishedGeneration)));
            assertTrue(stream.stoppedGenerations.contains(Long.valueOf(pendingCandidateGeneration)));
            assertEquals(1, stream.promotions);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void playNowStopsRadioBeforeStartingTheRequestedMusicDownload() throws Exception {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        List<String> events = new ArrayList<String>();
        FakeRadioStream stream = new FakeRadioStream(events);
        FakeAudioDownload audioDownload = new FakeAudioDownload(events);
        PlaylistManager manager = new PlaylistManager(new YouTubeService(), audioDownload, browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready();

            manager.handlePlayNow(null, "song", "Song", "1:00");

            assertFalse(manager.isRadioActive());
            assertEquals(1, stream.stopAllCalls);
            assertEquals(1, audioDownload.downloadCalls);
            assertEquals("stopRadio", events.get(0));
            assertEquals("download:song", events.get(1));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void searchEntriesReturnFirstTenPlayableResults() {
        List<SearchResult> candidates = Arrays.asList(
            new SearchResult("valid-1", "Valid 1", "channel", "2:00", ""),
            new SearchResult("unknown", "Unknown", "channel", "", ""),
            new SearchResult("too-long", "Too long", "channel", "15:00", ""),
            new SearchResult("valid-2", "Valid 2", "channel", "3:00", ""),
            new SearchResult("valid-3", "Valid 3", "channel", "3:00", ""),
            new SearchResult("valid-4", "Valid 4", "channel", "3:00", ""),
            new SearchResult("valid-5", "Valid 5", "channel", "3:00", ""),
            new SearchResult("valid-6", "Valid 6", "channel", "3:00", ""),
            new SearchResult("valid-7", "Valid 7", "channel", "3:00", ""),
            new SearchResult("valid-8", "Valid 8", "channel", "3:00", ""),
            new SearchResult("valid-9", "Valid 9", "channel", "3:00", ""),
            new SearchResult("valid-10", "Valid 10", "channel", "3:00", ""),
            new SearchResult("valid-11", "Valid 11", "channel", "3:00", ""));

        List<SearchResultsPacket.Entry> entries = PlaylistManager.buildSearchEntries(candidates, 15L * 60L * 1000L);

        assertEquals(10, entries.size());
        assertEquals(
            "valid-10",
            entries.get(9)
                .getVideoId());
        assertFalse(containsVideoId(entries, "unknown"));
        assertFalse(containsVideoId(entries, "too-long"));
    }

    @Test
    public void searchEntriesReturnAllPlayableResultsWhenFewerThanTenExist() {
        List<SearchResultsPacket.Entry> entries = PlaylistManager.buildSearchEntries(
            Arrays.asList(
                new SearchResult("valid-1", "Valid 1", "channel", "2:00", ""),
                new SearchResult("valid-2", "Valid 2", "channel", "3:00", ""),
                new SearchResult("unknown", "Unknown", "channel", "unknown", "")),
            15L * 60L * 1000L);

        assertEquals(Arrays.asList("valid-1", "valid-2"), entryIds(entries));
    }

    @Test
    public void searchEntriesRejectObviousNonMusicVideos() {
        List<SearchResultsPacket.Entry> entries = PlaylistManager.buildSearchEntries(
            Arrays.asList(
                new SearchResult("podcast", "The Daily Podcast", "channel", "3:00", ""),
                new SearchResult("tutorial", "How to make a guitar stand", "channel", "3:00", ""),
                new SearchResult("reaction", "Reaction to the new music video", "channel", "3:00", ""),
                new SearchResult("song", "Artist - Song (Official Music Video)", "channel", "3:00", "")),
            15L * 60L * 1000L);

        assertEquals(Arrays.asList("song"), entryIds(entries));
    }

    @Test
    public void searchEntriesStillFillTenSongsAfterFilteringCandidates() {
        List<SearchResult> candidates = new ArrayList<SearchResult>();
        candidates.add(new SearchResult("podcast", "The Daily Podcast", "channel", "3:00", ""));
        for (int index = 0; index < 10; index++) {
            candidates.add(
                new SearchResult(
                    "song-" + index,
                    "Artist " + index + " - Song " + index + " (Official Audio)",
                    "channel",
                    "3:00",
                    ""));
        }

        List<SearchResultsPacket.Entry> entries = PlaylistManager.buildSearchEntries(candidates, 15L * 60L * 1000L);

        assertEquals(10, entries.size());
        assertEquals(
            "song-0",
            entries.get(0)
                .getVideoId());
        assertEquals(
            "song-9",
            entries.get(9)
                .getVideoId());
    }

    @Test
    public void radioFailureThenPlayNowBroadcastsClearedRadioStateBeforeMusic() throws Exception {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        FakeAudioDownload audioDownload = new FakeAudioDownload(new ArrayList<String>());
        List<RadioStatePacket> radioStates = new ArrayList<RadioStatePacket>();
        PlaylistManager manager = new PlaylistManager(
            new YouTubeService(),
            audioDownload,
            browser,
            stream,
            radioStates::add);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready();
            radioStates.clear();

            stream.fail("Radio stream stopped producing PCM data");

            assertEquals(1, radioStates.size());
            assertFalse(
                radioStates.get(0)
                    .isActive());
            assertEquals(
                "Radio stream stopped producing PCM data",
                radioStates.get(0)
                    .getStatus());

            radioStates.clear();
            manager.handlePlayNow(null, "song", "Song", "1:00");
            audioDownload.completeLastDownload();

            assertEquals(1, radioStates.size());
            assertFalse(
                radioStates.get(0)
                    .isActive());
            assertEquals(
                "",
                radioStates.get(0)
                    .getStatus());
            assertEquals(
                "station-a",
                radioStates.get(0)
                    .getStationUuid());
            assertEquals(
                "Station A",
                radioStates.get(0)
                    .getStationName());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void playlistHandlersDoNotStartMusicOrDestroyRadioWhileRadioIsActive() throws Exception {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        FakeAudioDownload audioDownload = new FakeAudioDownload(new ArrayList<String>());
        PlaylistManager manager = new PlaylistManager(new YouTubeService(), audioDownload, browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready();

            manager.handleAddToPlaylist(null, "one", "One", "1:00");
            manager.handleAddToPlaylist(null, "two", "Two", "1:00");
            manager.handleImportVideo(null, "https://www.youtube.com/watch?v=imported");
            manager.handleImportPlaylist(null, "https://www.youtube.com/playlist?list=import-list");
            manager.handleReorder(null, 0, 1);
            manager.handleRemoveFromPlaylist(null, "one");
            manager.handleClearPlaylist(null);

            assertTrue(manager.isRadioActive());
            assertEquals(0, audioDownload.downloadCalls);
            assertEquals(0, stream.stopAllCalls);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void explicitRadioStopStopsTheRelayAndLeavesItInactive() {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready();

            manager.stopRadio();

            assertFalse(manager.isRadioActive());
            assertEquals(1, stream.stopAllCalls);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void multibyteStationNameIsBoundedBeforeCandidatePromotion() {
        RadioStation oversized = new RadioStation(
            "station-long-name",
            repeat("界", 100),
            "https://stream.example/long-name",
            true,
            false);
        FakeRadioBrowser browser = new FakeRadioBrowser(oversized);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, oversized.getStationUuid());
            stream.ready();

            assertTrue(manager.isRadioActive());
            assertEquals(1, stream.promotions);
            assertEquals(
                repeat("界", 50),
                stream.station(0)
                    .getName());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void invalidStationUuidIsRejectedBeforeStartingCandidate() {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready();
            browser.station = new RadioStation(
                repeat("u", 65),
                "Invalid station",
                "https://stream.example/invalid",
                true,
                false);

            manager.selectRadioStation(null, "station-invalid");

            assertTrue(manager.isRadioActive());
            assertEquals("station-a", publishedStationUuid(manager));
            assertEquals(1, stream.sessionCount());
            assertEquals(1, stream.promotions);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void oversizedReadyChunkCannotRetirePublishedStation() {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready();
            browser.station = STATION_B;
            manager.selectRadioStation(null, "station-b");

            stream.readyWithData(1, new byte[30 * 1024 + 1]);

            assertTrue(manager.isRadioActive());
            assertEquals("station-a", publishedStationUuid(manager));
            assertEquals(1, stream.promotions);
            assertTrue(stream.stoppedGenerations.contains(Long.valueOf(stream.generation(1))));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void activeFailureBoundsMultibyteStatusBeforeBroadcast() {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        PlaylistManager manager = new PlaylistManager(browser, stream);
        try {
            manager.selectRadioStation(null, "station-a");
            stream.ready();

            stream.fail(repeat("界", 100));

            assertFalse(manager.isRadioActive());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void cachedChartsAreTerminalOnlyForFreshNonForceRequests() {
        assertTrue(PlaylistManager.shouldServeCachedCharts(true, false, true));
        assertFalse(PlaylistManager.shouldServeCachedCharts(true, true, true));
        assertFalse(PlaylistManager.shouldServeCachedCharts(true, false, false));
        assertFalse(PlaylistManager.shouldServeCachedCharts(false, false, true));
    }

    @Test
    public void freshNonForceChartRequestSendsCachedResultsWithoutRefreshActions() {
        RecordingChartActions actions = process(false, false, true, true);

        assertEquals(list("results"), actions.events);
    }

    @Test
    public void staleNonForceChartRequestWaitsForRefreshBeforeSendingResults() {
        RecordingChartActions actions = process(false, false, true, false);

        assertEquals(list("chat:YELLOW:Loading Global YouTube Music Top 50...", "waiter", "refresh"), actions.events);
    }

    @Test
    public void forceChartRequestWithCacheWaitsForRefreshBeforeSendingResults() {
        RecordingChartActions actions = process(true, true, true, true);

        assertEquals(
            list("chat:YELLOW:Refreshing Global YouTube Music Top 50...", "waiter", "refresh"),
            actions.events);
    }

    @Test
    public void chartRefreshPublishesResultsWithoutWaitingForDurationLookup() throws Exception {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        FakeAudioDownload audioDownload = new FakeAudioDownload(new ArrayList<String>());
        RecordingChartYouTube youtube = new RecordingChartYouTube();
        youtube.charts = Arrays.asList(new SearchResult("chart", "Chart Song", "Artist", "", ""));
        PlaylistManager manager = new PlaylistManager(youtube, audioDownload, browser, stream);
        try {
            invokeChartRefresh(manager, ChartRegionCatalog.byCode("DE"));

            assertEquals(0, audioDownload.durationLookupCalls);
            assertEquals(
                Arrays.asList(new SearchResult("chart", "Chart Song", "Artist", "", "")),
                cachedCharts(manager, "DE"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void chartPlayNowResolvesMissingDurationBeforeStartingMusic() throws Exception {
        FakeRadioBrowser browser = new FakeRadioBrowser(STATION_A);
        FakeRadioStream stream = new FakeRadioStream();
        FakeAudioDownload audioDownload = new FakeAudioDownload(new ArrayList<String>());
        PlaylistManager manager = new PlaylistManager(new YouTubeService(), audioDownload, browser, stream);
        try {
            manager.handlePlayNow(null, "chart", "Chart Song", "");

            assertEquals(1, audioDownload.durationLookupCalls);
            assertEquals(0, audioDownload.downloadCalls);

            audioDownload.completeLastDuration("chart\\t1:00");

            assertEquals(1, audioDownload.downloadCalls);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void unauthorizedForceChartRequestSendsCachedResultsAndDenialWithoutRefreshActions() {
        RecordingChartActions actions = process(true, false, true, false);

        assertEquals(list("results", "chat:RED:Only server operators can refresh the charts."), actions.events);
    }

    @Test
    public void chartRequestMessagesUseSelectedRegion() {
        RecordingChartActions actions = new RecordingChartActions();

        PlaylistManager.processChartRequest(ChartRegionCatalog.byCode("US"), false, false, false, false, actions);

        assertEquals(
            list("chat:YELLOW:Loading United States YouTube Music Top 50...", "waiter", "refresh"),
            actions.events);
    }

    @Test
    public void staleSearchRequestTokensAreRejected() {
        assertTrue(PlaylistManager.isLatestSearchRequest(Long.valueOf(7L), 7L));
        assertFalse(PlaylistManager.isLatestSearchRequest(Long.valueOf(8L), 7L));
        assertFalse(PlaylistManager.isLatestSearchRequest(null, 7L));
    }

    @Test
    public void chartFetcherReceivesCanonicalRegions() throws Exception {
        RecordingChartYouTube service = new RecordingChartYouTube();

        service.fetchTopCharts(ChartRegionCatalog.global())
            .get();
        service.fetchTopCharts(ChartRegionCatalog.byCode("DE"))
            .get();

        assertEquals(Arrays.asList("GLOBAL", "DE"), service.regionCodes());
    }

    private static RecordingChartActions process(boolean forceRefresh, boolean operator, boolean hasCachedCharts,
        boolean cacheFresh) {
        RecordingChartActions actions = new RecordingChartActions();
        PlaylistManager.processChartRequest(forceRefresh, operator, hasCachedCharts, cacheFresh, actions);
        return actions;
    }

    private static List<String> list(String... events) {
        List<String> result = new ArrayList<String>();
        for (String event : events) {
            result.add(event);
        }
        return result;
    }

    private static void invokeChartRefresh(PlaylistManager manager, ChartRegion region) throws Exception {
        Method refresh = PlaylistManager.class.getDeclaredMethod("refreshChartsIfNeeded", ChartRegion.class);
        refresh.setAccessible(true);
        refresh.invoke(manager, region);
    }

    private static List<SearchResult> cachedCharts(PlaylistManager manager, String regionCode) throws Exception {
        Field field = PlaylistManager.class.getDeclaredField("chartCache");
        field.setAccessible(true);
        return ((ChartCache) field.get(manager)).getResults(regionCode);
    }

    private static boolean containsVideoId(List<SearchResultsPacket.Entry> entries, String videoId) {
        for (SearchResultsPacket.Entry entry : entries) {
            if (videoId.equals(entry.getVideoId())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> entryIds(List<SearchResultsPacket.Entry> entries) {
        List<String> ids = new ArrayList<String>();
        for (SearchResultsPacket.Entry entry : entries) {
            ids.add(entry.getVideoId());
        }
        return ids;
    }

    private static long radioLastSequence(PlaylistManager manager) throws Exception {
        Field field = PlaylistManager.class.getDeclaredField("radioLastSequence");
        field.setAccessible(true);
        return field.getLong(manager);
    }

    private static String publishedStationUuid(PlaylistManager manager) {
        try {
            Field field = PlaylistManager.class.getDeclaredField("radioState");
            field.setAccessible(true);
            return ((com.horizonradio.core.server.RadioPlaybackState) field.get(manager)).getStationUuid();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to inspect published radio state", exception);
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static final class RecordingChartActions implements PlaylistManager.ChartRequestActions {

        private final List<String> events = new ArrayList<String>();

        @Override
        public void sendChartResults() {
            events.add("results");
        }

        @Override
        public void sendChat(EnumChatFormatting color, String message) {
            events.add("chat:" + color.name() + ":" + message);
        }

        @Override
        public void registerWaiter() {
            events.add("waiter");
        }

        @Override
        public void refresh() {
            events.add("refresh");
        }
    }

    private static final class RecordingChartYouTube extends YouTubeService {

        private final List<String> regionCodes = new ArrayList<String>();
        private List<SearchResult> charts = Collections.emptyList();

        @Override
        public CompletableFuture<List<SearchResult>> fetchTopCharts(ChartRegion region) {
            regionCodes.add(region.getCode());
            return CompletableFuture.completedFuture(charts);
        }

        private List<String> regionCodes() {
            return regionCodes;
        }
    }

    private static final class FakeRadioBrowser extends RadioBrowserService {

        private RadioStation station;
        private int clickCounts;
        private boolean failNextLookup;

        private FakeRadioBrowser(RadioStation station) {
            this.station = station;
        }

        @Override
        public CompletableFuture<RadioStation> lookup(String stationUuid) {
            if (failNextLookup) {
                failNextLookup = false;
                CompletableFuture<RadioStation> failure = new CompletableFuture<RadioStation>();
                failure.completeExceptionally(new IllegalStateException("lookup failed"));
                return failure;
            }
            return CompletableFuture.completedFuture(station);
        }

        @Override
        public CompletableFuture<Void> countClick(String stationUuid) {
            clickCounts++;
            return CompletableFuture.completedFuture(null);
        }

        private void failNextLookup() {
            failNextLookup = true;
        }
    }

    private static final class FakeRadioStream extends RadioStreamService {

        private final List<Session> sessions = new ArrayList<Session>();
        private final List<Long> stoppedGenerations = new ArrayList<Long>();
        private final List<String> events;
        private int promotions;
        private int stopAllCalls;
        private int stopGenerationCalls;

        private FakeRadioStream() {
            this(null);
        }

        private FakeRadioStream(List<String> events) {
            this.events = events;
        }

        @Override
        public void startCandidate(RadioStation candidate, long candidateGeneration,
            RadioStreamListener candidateListener) {
            sessions.add(new Session(candidate, candidateGeneration, candidateListener));
        }

        @Override
        public void promoteCandidate(long candidateGeneration) {
            assertEquals(sessions.get(sessions.size() - 1).generation, candidateGeneration);
            promotions++;
        }

        @Override
        public void stopGeneration(long candidateGeneration) {
            stopGenerationCalls++;
            stoppedGenerations.add(Long.valueOf(candidateGeneration));
        }

        @Override
        public void stopAll() {
            stopAllCalls++;
            if (events != null) {
                events.add("stopRadio");
            }
        }

        @Override
        public void shutdown() {}

        private void ready() {
            ready(sessions.size() - 1);
        }

        private void ready(int index) {
            readyWithData(index, new byte[] { 1, 2, 3, 4 });
        }

        private void readyWithData(int index, byte[] data) {
            Session session = sessions.get(index);
            session.listener.onReady(session.generation, session.station, 0L, data);
        }

        private long generation(int index) {
            return sessions.get(index).generation;
        }

        private RadioStation station(int index) {
            return sessions.get(index).station;
        }

        private int sessionCount() {
            return sessions.size();
        }

        private void fail(String message) {
            fail(sessions.size() - 1, message);
        }

        private void fail(int index, String message) {
            Session session = sessions.get(index);
            session.listener.onFailure(session.generation, message);
        }

        private void chunk(int index, long sequence) {
            Session session = sessions.get(index);
            session.listener.onChunk(session.generation, sequence, new byte[] { 1, 2, 3, 4 });
        }

        private static final class Session {

            private final RadioStation station;
            private final long generation;
            private final RadioStreamListener listener;

            private Session(RadioStation station, long generation, RadioStreamListener listener) {
                this.station = station;
                this.generation = generation;
                this.listener = listener;
            }
        }
    }

    private static final class FakeAudioDownload extends AudioDownloadService {

        private final List<String> events;
        private int downloadCalls;
        private int durationLookupCalls;
        private CompletableFuture<Path> lastDownload;
        private Path lastDownloadFile;
        private CompletableFuture<String> lastDurationLookup;

        private FakeAudioDownload(List<String> events) throws IOException {
            super(Files.createTempDirectory("horizonradio-playlist-test"), false);
            this.events = events;
        }

        @Override
        public synchronized CompletableFuture<Path> download(String videoId) {
            downloadCalls++;
            events.add("download:" + videoId);
            lastDownload = new CompletableFuture<Path>();
            return lastDownload;
        }

        @Override
        public CompletableFuture<String> extractVideoDurationOutput(List<String> videoIds) {
            durationLookupCalls++;
            lastDurationLookup = new CompletableFuture<String>();
            return lastDurationLookup;
        }

        private void completeLastDuration(String output) {
            lastDurationLookup.complete(output);
        }

        private void completeLastDownload() throws IOException {
            lastDownloadFile = Files.createTempFile("horizonradio-audio", ".wav");
            Files.write(lastDownloadFile, new byte[] { 1 });
            lastDownload.complete(lastDownloadFile);
        }

        @Override
        public boolean isDependenciesAvailable() {
            return true;
        }

        @Override
        public CompletableFuture<String> extractVideoJson(String videoUrl) {
            return CompletableFuture
                .completedFuture("{\"id\":\"imported\",\"title\":\"Imported\",\"duration_string\":\"1:00\"}");
        }

        @Override
        public CompletableFuture<String> extractPlaylistJson(String playlistUrl) {
            return CompletableFuture.completedFuture(
                "{\"entries\":[{\"id\":\"playlist-imported\",\"title\":\"Playlist Imported\",\"duration_string\":\"1:00\"}]}");
        }

        @Override
        public void delete(String videoId) {}

        @Override
        public void shutdown() {}
    }
}
