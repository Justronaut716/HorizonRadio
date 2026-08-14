package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.horizonradio.client.media.ClientMediaService;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartRegion;

public class HorizonRadioClientDiscoveryTest {

    private final RecordingTransport transport = new RecordingTransport();

    @Before
    public void setUp() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setClientMediaService(null);
        HorizonRadioClient.setTransport(transport.asTransport());
        new ClientProxy(new DirectScheduler());
    }

    @After
    public void tearDown() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setClientMediaService(null);
        HorizonRadioClient.setTransport(new HorizonRadioClient.NoopClientTransport());
    }

    @Test
    public void unavailableLocalDiscoveryNeverUsesTheConfiguredTransport() {
        HorizonRadioClient.sendSearch("ambient");
        HorizonRadioClient.sendChartsRequest("DE", false);
        HorizonRadioClient.sendImportPlaylist("https://example.test/playlist");
        HorizonRadioClient.sendImportVideo("https://example.test/video");
        HorizonRadioClient.sendRadioSearch("jazz");

        assertEquals(0, transport.discoveryCallCount);
    }

    @Test
    public void publicDiscoveryEntrypointsDoNotDelegateToTransport() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/horizonradio/client/HorizonRadioClient.java")),
            Charset.forName("UTF-8"));

        assertTrue(
            !methodBody(source, "public static synchronized void sendSearch(String query)")
                .contains("transport.sendSearch"));
        assertTrue(
            !methodBody(
                source,
                "public static synchronized void sendChartsRequest(String regionCode, boolean forceRefresh)")
                    .contains("transport.sendChartsRequest"));
        assertTrue(
            !methodBody(source, "public static synchronized void sendImportPlaylist(String playlistUrl)")
                .contains("transport.sendImportPlaylist"));
        assertTrue(
            !methodBody(source, "public static synchronized void sendImportVideo(String videoUrl)")
                .contains("transport.sendImportVideo"));
        assertTrue(
            !methodBody(source, "public static synchronized void sendRadioSearch(String query)")
                .contains("transport.sendRadioSearch"));
    }

    @Test
    public void playlistImportPublishesLocallyWithoutUsingTransport() throws Exception {
        DeferredProvider provider = new DeferredProvider();
        CompletableFuture<String> importPlaylist = provider.deferPlaylist();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLlocal");
            importPlaylist.complete("{\"entries\":[{\"id\":\"song-one\",\"title\":\"One\",\"duration\":60}]}");

            assertEquals(
                "song-one",
                HorizonRadioClient.getCachedPlaylistResults()
                    .get(0).videoId);
            assertEquals("song-one", playlistResults(screen).get(0).videoId);
            assertEquals(0, transport.discoveryCallCount);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void playlistImportDoesNotPublishAnOlderCompletion() {
        DeferredProvider provider = new DeferredProvider();
        CompletableFuture<String> older = provider.deferPlaylist();
        CompletableFuture<String> newer = provider.deferPlaylist();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLold");
            HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLnew");

            newer.complete("{\"entries\":[{\"id\":\"new-song\",\"title\":\"New\",\"duration\":60}]}");
            older.complete("{\"entries\":[{\"id\":\"old-song\",\"title\":\"Old\",\"duration\":60}]}");

            assertEquals(
                "new-song",
                HorizonRadioClient.getCachedPlaylistResults()
                    .get(0).videoId);
            assertEquals("new-song", playlistResults(screen).get(0).videoId);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void invalidPlaylistUrlReportsLocalErrorWithoutProviderOrTransport() {
        DeferredProvider provider = new DeferredProvider();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendPlaylistImport("not a playlist");

            assertTrue(
                HorizonRadioClient.getCachedPlaylistResults()
                    .isEmpty());
            assertEquals("Paste a valid YouTube playlist URL", playlistError(screen));
            assertFalse(isPlaylistLoading(screen));
            assertEquals(0, provider.playlistImportCallCount);
            assertEquals(0, transport.discoveryCallCount);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void closedPlaylistImportCannotPublishIntoReopenedScreen() {
        DeferredProvider provider = new DeferredProvider();
        CompletableFuture<String> pendingImport = provider.deferPlaylist();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen original = new HorizonRadioScreen();
        HorizonRadioScreen reopened = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(original);
        try {
            HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLclose");
            original.onGuiClosed();
            HorizonRadioScreen.setActiveScreen(reopened);

            pendingImport.complete("{\"entries\":[{\"id\":\"closed-song\",\"title\":\"Closed\",\"duration\":60}]}");

            assertTrue(
                HorizonRadioClient.getCachedPlaylistResults()
                    .isEmpty());
            assertTrue(playlistResults(reopened).isEmpty());
        } finally {
            HorizonRadioScreen.clearActiveScreen(original);
            HorizonRadioScreen.clearActiveScreen(reopened);
        }
    }

    @Test
    public void playlistImportPublishesFirstFiftyValidUniqueResultsOnly() throws Exception {
        DeferredProvider provider = new DeferredProvider();
        CompletableFuture<String> importPlaylist = provider.deferPlaylist();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLfifty");
            importPlaylist.complete(buildPlaylistImportJsonFixture());

            List<HorizonRadioScreen.SearchResult> cached = HorizonRadioClient.getCachedPlaylistResults();

            assertEquals(50, cached.size());
            assertEquals("fixture-01", cached.get(0).videoId);
            assertEquals("fixture-52", cached.get(49).videoId);
            assertEquals(0, transport.discoveryCallCount);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void staleSearchCompletionCannotReplaceANewerImport() throws Exception {
        DeferredProvider provider = new DeferredProvider();
        CompletableFuture<String> importPlaylist = provider.deferPlaylist();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendSearch("old search");
            HorizonRadioClient.sendImportPlaylist("https://example.test/playlist");

            importPlaylist.complete("{\"entries\":[{\"id\":\"import\",\"title\":\"Imported\",\"duration\":120}]}");
            provider.search.complete(Collections.singletonList(result("search", "Old search")));

            assertEquals("import", searchResults(screen).get(0).videoId);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void chartAddResolvesMissingDurationBeforeSendingCompactSelection() {
        DeferredProvider provider = new DeferredProvider();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        CompletableFuture<String> metadata = provider.deferVideo("dQw4w9WgXcQ");

        HorizonRadioClient.sendAddChartsToPlaylist(
            Collections.singletonList(new HorizonRadioScreen.SearchResult("dQw4w9WgXcQ", "Chart", "", "", "")));

        assertTrue(transport.chartSelections.isEmpty());
        metadata.complete("{\"id\":\"dQw4w9WgXcQ\",\"title\":\"Chart\",\"duration\":123}");

        assertEquals(1, transport.chartSelections.size());
        assertEquals("dQw4w9WgXcQ|123000", transport.chartSelections.get(0));
    }

    @Test
    public void chartBulkAddPreservesOrderAndClearsFailedPendingEntry() {
        DeferredProvider provider = new DeferredProvider();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            List<HorizonRadioScreen.SearchResult> results = Arrays
                .asList(chart("aQw4w9WgXcQ"), chart("bQw4w9WgXcQ"), chart("cQw4w9WgXcQ"));
            screen.beginChartAdd(results);
            CompletableFuture<String> first = provider.deferVideo("aQw4w9WgXcQ");
            CompletableFuture<String> second = provider.deferVideo("bQw4w9WgXcQ");
            CompletableFuture<String> third = provider.deferVideo("cQw4w9WgXcQ");

            HorizonRadioClient.sendAddChartsToPlaylist(results);

            first.complete("{\"id\":\"aQw4w9WgXcQ\",\"title\":\"A\",\"duration\":60}");
            third.complete("{\"id\":\"cQw4w9WgXcQ\",\"title\":\"C\",\"duration\":62}");
            second.completeExceptionally(new IllegalStateException("metadata unavailable"));

            assertEquals(Arrays.asList("aQw4w9WgXcQ|60000", "cQw4w9WgXcQ|62000"), transport.chartSelections);
            assertFalse(screen.isChartAddPending("bQw4w9WgXcQ"));
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void chartDirectPlayResolvesMissingDurationBeforeSending() {
        DeferredProvider provider = new DeferredProvider();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        CompletableFuture<String> metadata = provider.deferVideo("pQw4w9WgXcQ");

        HorizonRadioClient.sendPlayNow(new HorizonRadioScreen.SearchResult("pQw4w9WgXcQ", "Chart", "", "", ""));

        assertEquals(0, transport.playNowRequests.size());
        metadata.complete("{\"id\":\"pQw4w9WgXcQ\",\"title\":\"Chart\",\"duration\":240}");

        assertEquals(Collections.singletonList("pQw4w9WgXcQ|240000"), transport.playNowRequests);
    }

    @Test
    public void chartActionWithKnownDurationDoesNotResolveMetadata() {
        DeferredProvider provider = new DeferredProvider();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));

        HorizonRadioClient.sendAddChartsToPlaylist(
            Collections.singletonList(new HorizonRadioScreen.SearchResult("kQw4w9WgXcQ", "Known", "", "1:30", "")));

        assertEquals(Collections.singletonList("kQw4w9WgXcQ|90000"), transport.chartSelections);
        assertEquals(0, provider.videoLookupCount);
    }

    @Test
    public void playlistBulkAddPreservesOrderAndUsesOnlyQueueSelectionTransport() {
        HorizonRadioScreen.SearchResult first = new HorizonRadioScreen.SearchResult("pl-one", "One", "", "1:00", "");
        HorizonRadioScreen.SearchResult second = new HorizonRadioScreen.SearchResult("pl-two", "Two", "", "2:00", "");

        HorizonRadioClient.sendPlaylistResultsToQueue(Arrays.asList(first, second));

        assertEquals(Arrays.asList("pl-one|60000", "pl-two|120000"), transport.chartSelections);
        assertNull(transport.importPlaylistUrl);
    }

    @Test
    public void failedPlaylistMetadataClearsOnlyPlaylistPendingState() {
        DeferredProvider provider = new DeferredProvider();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        HorizonRadioScreen.SearchResult result = new HorizonRadioScreen.SearchResult(
            "pl-missing",
            "Missing",
            "",
            "",
            "");
        try {
            assertEquals(Collections.singletonList(result), screen.beginPlaylistAdd(Collections.singletonList(result)));

            HorizonRadioClient.sendPlaylistResultsToQueue(Collections.singletonList(result));
            provider.deferVideo("pl-missing")
                .completeExceptionally(new IllegalStateException("missing"));

            assertFalse(screen.isPlaylistAddPending("pl-missing"));
            assertTrue(transport.chartSelections.isEmpty());
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void chartsPublishOnlyAfterMissingDurationsAreResolved() {
        DeferredProvider provider = new DeferredProvider();
        provider.chartResults = Arrays.asList(
            new SearchResult("MdDurPref01", "First", "", "", ""),
            new SearchResult("KnDurPref02", "Second", "", "2:00", ""));
        CompletableFuture<String> metadata = provider.deferVideo("MdDurPref01");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendChartsRequest("DE", false);

            assertTrue(chartResults(screen).isEmpty());
            assertEquals(1, provider.videoLookupCount);

            metadata.complete("{\"id\":\"MdDurPref01\",\"title\":\"First\",\"duration\":90}");

            assertEquals(Arrays.asList("MdDurPref01", "KnDurPref02"), chartVideoIds(screen));
            assertEquals("1:30", chartResults(screen).get(0).duration);
            assertEquals("2:00", chartResults(screen).get(1).duration);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void failedChartMetadataLeavesPlaceholderAndOtherChartsPresent() {
        DeferredProvider provider = new DeferredProvider();
        provider.chartResults = Arrays.asList(
            new SearchResult("FlDurPref03", "Failed", "", "", ""),
            new SearchResult("KnDurPref02", "Known", "", "2:00", ""));
        CompletableFuture<String> metadata = provider.deferVideo("FlDurPref03");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendChartsRequest("DE", false);
            metadata.completeExceptionally(new IllegalStateException("metadata unavailable"));

            assertEquals(Arrays.asList("FlDurPref03", "KnDurPref02"), chartVideoIds(screen));
            assertEquals("--:--", chartResults(screen).get(0).duration);
            assertEquals("2:00", chartResults(screen).get(1).duration);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void chartDurationPrefetchIsReusedWhenAddingPublishedResult() {
        DeferredProvider provider = new DeferredProvider();
        provider.chartResults = Collections.singletonList(new SearchResult("CaDurPref04", "Cached", "", "", ""));
        CompletableFuture<String> metadata = provider.deferVideo("CaDurPref04");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendChartsRequest("DE", false);
            metadata.complete("{\"id\":\"CaDurPref04\",\"title\":\"Cached\",\"duration\":90}");

            assertEquals("1:30", chartResults(screen).get(0).duration);
            assertEquals(1, provider.videoLookupCount);

            HorizonRadioClient.sendAddChartsToPlaylist(chartResults(screen));

            assertEquals(Collections.singletonList("CaDurPref04|90000"), transport.chartSelections);
            assertEquals(1, provider.videoLookupCount);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void knownOverLimitChartUsesPlaceholderWithoutMetadataLookup() {
        DeferredProvider provider = new DeferredProvider();
        provider.chartResults = Collections.singletonList(new SearchResult("KnOverLim07", "Too long", "", "16:00", ""));
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendChartsRequest("DE", false);

            assertEquals("--:--", chartResults(screen).get(0).duration);
            assertEquals(0, provider.videoLookupCount);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void resolvedOverLimitChartUsesPlaceholder() {
        DeferredProvider provider = new DeferredProvider();
        provider.chartResults = Collections.singletonList(new SearchResult("RsOverLim08", "Too long", "", "", ""));
        CompletableFuture<String> metadata = provider.deferVideo("RsOverLim08");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendChartsRequest("DE", false);
            metadata.complete("{\"id\":\"RsOverLim08\",\"title\":\"Too long\",\"duration\":960}");

            assertEquals("--:--", chartResults(screen).get(0).duration);
            assertEquals(1, provider.videoLookupCount);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void staleChartCompletionCannotReplaceNewerGeneration() {
        DeferredProvider provider = new DeferredProvider();
        CompletableFuture<List<SearchResult>> older = provider.deferCharts();
        CompletableFuture<List<SearchResult>> newer = provider.deferCharts();
        CompletableFuture<String> olderMetadata = provider.deferVideo("OlChartP005");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendChartsRequest("DE", false);
            HorizonRadioClient.sendChartsRequest("DE", true);

            older.complete(Collections.singletonList(new SearchResult("OlChartP005", "Old", "", "", "")));
            newer.complete(Collections.singletonList(new SearchResult("NeChartP006", "New", "", "2:00", "")));

            assertEquals(Collections.singletonList("NeChartP006"), chartVideoIds(screen));

            olderMetadata.complete("{\"id\":\"OlChartP005\",\"title\":\"Old\",\"duration\":90}");

            assertEquals(Collections.singletonList("NeChartP006"), chartVideoIds(screen));
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void closedChartRequestCannotPublishIntoReopenedScreen() {
        DeferredProvider provider = new DeferredProvider();
        CompletableFuture<List<SearchResult>> pendingCharts = provider.deferCharts();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen original = new HorizonRadioScreen();
        HorizonRadioScreen reopened = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(original);
        try {
            HorizonRadioClient.sendChartsRequest("DE", false);
            original.onGuiClosed();
            boolean pendingAfterClose = HorizonRadioClient.isChartRequestPending();
            HorizonRadioScreen.setActiveScreen(reopened);

            pendingCharts
                .complete(Collections.singletonList(new SearchResult("ClChartP009", "Closed", "", "2:00", "")));

            assertTrue(chartResults(reopened).isEmpty());
            assertFalse(pendingAfterClose);
            assertFalse(HorizonRadioClient.isChartRequestPending());
        } finally {
            HorizonRadioScreen.clearActiveScreen(original);
            HorizonRadioScreen.clearActiveScreen(reopened);
        }
    }

    @Test
    public void chartAddRemainsPendingUntilAuthoritativePlaylistUpdate() {
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioScreen.SearchResult result = chartWithDuration("pending-chart", "2:00");
            assertEquals(Collections.singletonList(result), screen.beginChartAdd(Collections.singletonList(result)));

            HorizonRadioClient.sendAddChartsToPlaylist(Collections.singletonList(result));

            assertTrue(screen.isChartAddPending("pending-chart"));
            assertTrue(
                screen.beginChartAdd(Collections.singletonList(result))
                    .isEmpty());
            assertEquals(Collections.singletonList("pending-chart|120000"), transport.chartSelections);

            screen.updatePlaylist(
                Collections.singletonList(
                    new HorizonRadioScreen.PlaylistEntry("pending-chart", "pending-chart", "2:00", "tester")));
            assertFalse(screen.isChartAddPending("pending-chart"));
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void bulkChartAddDoesNotResendEntriesWhileTheyAwaitQueueUpdate() {
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            List<HorizonRadioScreen.SearchResult> results = new ArrayList<HorizonRadioScreen.SearchResult>();
            for (int index = 1; index <= 50; index++) {
                results.add(chartWithDuration("bulk-chart-" + index, "2:00"));
            }

            assertEquals(
                50,
                screen.beginChartAdd(results)
                    .size());
            HorizonRadioClient.sendAddChartsToPlaylist(results);

            assertEquals(50, transport.chartSelections.size());
            assertTrue(
                screen.beginChartAdd(results)
                    .isEmpty());
            assertEquals(50, transport.chartSelections.size());
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    private static HorizonRadioScreen.SearchResult chart(String videoId) {
        return new HorizonRadioScreen.SearchResult(videoId, videoId, "", "", "");
    }

    private static HorizonRadioScreen.SearchResult chartWithDuration(String videoId, String duration) {
        return new HorizonRadioScreen.SearchResult(videoId, videoId, "", duration, "");
    }

    private static List<HorizonRadioScreen.SearchResult> searchResults(HorizonRadioScreen screen) {
        try {
            java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("searchResults");
            field.setAccessible(true);
            return new ArrayList<HorizonRadioScreen.SearchResult>(
                (List<HorizonRadioScreen.SearchResult>) field.get(screen));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("search results were not available", exception);
        }
    }

    private static List<HorizonRadioScreen.SearchResult> chartResults(HorizonRadioScreen screen) {
        try {
            java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("chartResults");
            field.setAccessible(true);
            return new ArrayList<HorizonRadioScreen.SearchResult>(
                (List<HorizonRadioScreen.SearchResult>) field.get(screen));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("chart results were not available", exception);
        }
    }

    private static List<HorizonRadioScreen.SearchResult> playlistResults(HorizonRadioScreen screen) {
        try {
            java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("playlistResults");
            field.setAccessible(true);
            return new ArrayList<HorizonRadioScreen.SearchResult>(
                (List<HorizonRadioScreen.SearchResult>) field.get(screen));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("playlist results were not available", exception);
        }
    }

    private static String playlistError(HorizonRadioScreen screen) {
        try {
            java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("playlistError");
            field.setAccessible(true);
            return (String) field.get(screen);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("playlist error was not available", exception);
        }
    }

    private static boolean isPlaylistLoading(HorizonRadioScreen screen) {
        try {
            java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("playlistLoading");
            field.setAccessible(true);
            return ((Boolean) field.get(screen)).booleanValue();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("playlist loading was not available", exception);
        }
    }

    private static List<String> chartVideoIds(HorizonRadioScreen screen) {
        List<String> videoIds = new ArrayList<String>();
        for (HorizonRadioScreen.SearchResult result : chartResults(screen)) {
            videoIds.add(result.videoId);
        }
        return videoIds;
    }

    private static String buildPlaylistImportJsonFixture() {
        StringBuilder json = new StringBuilder("{\"entries\":[");
        for (int index = 1; index <= 52; index++) {
            if (index > 1) {
                json.append(',');
            }
            if (index == 17) {
                json.append("{\"id\":\"fixture-05\",\"title\":\"Duplicate Five\",\"duration\":77}");
            } else if (index == 23) {
                json.append("{\"title\":\"Missing Id\",\"duration\":78}");
            } else {
                json.append("{\"id\":\"fixture-")
                    .append(index < 10 ? "0" : "")
                    .append(index)
                    .append("\",\"title\":\"Fixture ")
                    .append(index)
                    .append("\",\"duration\":")
                    .append(59 + index)
                    .append('}');
            }
        }
        json.append("]}");
        return json.toString();
    }

    private static SearchResult result(String videoId, String title) {
        return new SearchResult(videoId, title, "", "2:00", "");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("method was not found: " + signature);
        }
        int nextMethod = source.indexOf("\n    public static synchronized", start + signature.length());
        return source.substring(start, nextMethod < 0 ? source.length() : nextMethod);
    }

    private static final class DirectScheduler implements ClientProxy.ClientTaskScheduler {

        @Override
        public void schedule(Runnable task) {
            task.run();
        }
    }

    private static final class DeferredProvider implements ClientMediaService.RemoteProvider {

        private final CompletableFuture<List<SearchResult>> search = new CompletableFuture<List<SearchResult>>();
        private final List<CompletableFuture<String>> deferredPlaylistImports = new ArrayList<CompletableFuture<String>>();
        private final Map<String, CompletableFuture<String>> videoMetadata = new HashMap<String, CompletableFuture<String>>();
        private final List<CompletableFuture<List<SearchResult>>> chartRequests = new ArrayList<CompletableFuture<List<SearchResult>>>();
        private List<SearchResult> chartResults = Collections.emptyList();
        private int playlistImportCallCount;
        private int videoLookupCount;

        private CompletableFuture<String> deferPlaylist() {
            CompletableFuture<String> future = new CompletableFuture<String>();
            deferredPlaylistImports.add(future);
            return future;
        }

        private CompletableFuture<String> deferVideo(String videoId) {
            CompletableFuture<String> future = new CompletableFuture<String>();
            videoMetadata.put(videoId, future);
            return future;
        }

        private CompletableFuture<List<SearchResult>> deferCharts() {
            CompletableFuture<List<SearchResult>> future = new CompletableFuture<List<SearchResult>>();
            chartRequests.add(future);
            return future;
        }

        @Override
        public CompletableFuture<List<SearchResult>> search(String query, long maxDurationMs) {
            return search;
        }

        @Override
        public CompletableFuture<List<SearchResult>> fetchCharts(ChartRegion region) {
            if (!chartRequests.isEmpty()) {
                return chartRequests.remove(0);
            }
            return CompletableFuture.completedFuture(chartResults);
        }

        @Override
        public CompletableFuture<String> extractPlaylistJson(String playlistUrl) {
            playlistImportCallCount++;
            if (!deferredPlaylistImports.isEmpty()) {
                return deferredPlaylistImports.remove(0);
            }
            return CompletableFuture.completedFuture("{\"entries\":[]}");
        }

        @Override
        public CompletableFuture<String> extractVideoJson(String videoUrl) {
            String videoId = videoUrl.substring(videoUrl.indexOf("v=") + 2);
            videoLookupCount++;
            CompletableFuture<String> future = videoMetadata.get(videoId);
            return future == null ? CompletableFuture.completedFuture("{}") : future;
        }

        @Override
        public CompletableFuture<List<RadioStation>> searchRadio(String query) {
            return CompletableFuture.completedFuture(Collections.<RadioStation>emptyList());
        }

        @Override
        public CompletableFuture<RadioStation> lookupRadio(String stationUuid) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class RecordingTransport implements InvocationHandler {

        private int discoveryCallCount;
        private final List<String> chartSelections = new ArrayList<String>();
        private final List<String> playNowRequests = new ArrayList<String>();
        private String importPlaylistUrl;

        private HorizonRadioClient.ClientTransport asTransport() {
            return (HorizonRadioClient.ClientTransport) Proxy.newProxyInstance(
                HorizonRadioClient.ClientTransport.class.getClassLoader(),
                new Class<?>[] { HorizonRadioClient.ClientTransport.class },
                this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            if ("sendSearch".equals(name) || "sendChartsRequest".equals(name)
                || "sendImportPlaylist".equals(name)
                || "sendImportVideo".equals(name)
                || "sendRadioSearch".equals(name)) {
                discoveryCallCount++;
            }
            if ("sendImportPlaylist".equals(name) && arguments != null && arguments.length > 0) {
                importPlaylistUrl = String.valueOf(arguments[0]);
            }
            if ("sendAddChartSelections".equals(name) && arguments != null
                && arguments.length > 0
                && arguments[0] instanceof List<?>) {
                for (Object selection : (List<?>) arguments[0]) {
                    try {
                        java.lang.reflect.Field videoId = selection.getClass()
                            .getDeclaredField("videoId");
                        java.lang.reflect.Field durationMs = selection.getClass()
                            .getDeclaredField("durationMs");
                        videoId.setAccessible(true);
                        durationMs.setAccessible(true);
                        chartSelections.add(videoId.get(selection) + "|" + durationMs.get(selection));
                    } catch (ReflectiveOperationException exception) {
                        throw new AssertionError("chart selection was not recorded", exception);
                    }
                }
            }
            if ("sendPlayNow".equals(name) && arguments != null
                && arguments.length == 2
                && arguments[1] instanceof Long) {
                playNowRequests.add(arguments[0] + "|" + arguments[1]);
            }
            return null;
        }
    }
}
