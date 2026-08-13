package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void staleSearchCompletionCannotReplaceANewerImport() throws Exception {
        DeferredProvider provider = new DeferredProvider();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendSearch("old search");
            HorizonRadioClient.sendImportPlaylist("https://example.test/playlist");

            provider.importPlaylist
                .complete("{\"entries\":[{\"id\":\"import\",\"title\":\"Imported\",\"duration\":120}]}");
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

    private static HorizonRadioScreen.SearchResult chart(String videoId) {
        return new HorizonRadioScreen.SearchResult(videoId, videoId, "", "", "");
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

    private static List<String> chartVideoIds(HorizonRadioScreen screen) {
        List<String> videoIds = new ArrayList<String>();
        for (HorizonRadioScreen.SearchResult result : chartResults(screen)) {
            videoIds.add(result.videoId);
        }
        return videoIds;
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
        private final CompletableFuture<String> importPlaylist = new CompletableFuture<String>();
        private final Map<String, CompletableFuture<String>> videoMetadata = new HashMap<String, CompletableFuture<String>>();
        private final List<CompletableFuture<List<SearchResult>>> chartRequests = new ArrayList<CompletableFuture<List<SearchResult>>>();
        private List<SearchResult> chartResults = Collections.emptyList();
        private int videoLookupCount;

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
            return importPlaylist;
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
