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
        private int videoLookupCount;

        private CompletableFuture<String> deferVideo(String videoId) {
            CompletableFuture<String> future = new CompletableFuture<String>();
            videoMetadata.put(videoId, future);
            return future;
        }

        @Override
        public CompletableFuture<List<SearchResult>> search(String query, long maxDurationMs) {
            return search;
        }

        @Override
        public CompletableFuture<List<SearchResult>> fetchCharts(ChartRegion region) {
            return CompletableFuture.completedFuture(Collections.<SearchResult>emptyList());
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
