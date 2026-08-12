package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

        assertTrue(!methodBody(source, "public static synchronized void sendSearch(String query)")
            .contains("transport.sendSearch"));
        assertTrue(!methodBody(source, "public static synchronized void sendChartsRequest(String regionCode, boolean forceRefresh)")
            .contains("transport.sendChartsRequest"));
        assertTrue(!methodBody(source, "public static synchronized void sendImportPlaylist(String playlistUrl)")
            .contains("transport.sendImportPlaylist"));
        assertTrue(!methodBody(source, "public static synchronized void sendImportVideo(String videoUrl)")
            .contains("transport.sendImportVideo"));
        assertTrue(!methodBody(source, "public static synchronized void sendRadioSearch(String query)")
            .contains("transport.sendRadioSearch"));
    }

    @Test
    public void staleSearchCompletionCannotReplaceANewerImport() throws Exception {
        DirectScheduler scheduler = new DirectScheduler();
        new ClientProxy(scheduler);
        DeferredProvider provider = new DeferredProvider();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.sendSearch("old search");
            HorizonRadioClient.sendImportPlaylist("https://example.test/playlist");

            provider.importPlaylist.complete(
                "{\"entries\":[{\"id\":\"import\",\"title\":\"Imported\",\"duration\":120}]}");
            provider.search.complete(Collections.singletonList(result("search", "Old search")));

            assertEquals("import", searchResults(screen).get(0).videoId);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
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
            return CompletableFuture.completedFuture("{}");
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
                || "sendImportPlaylist".equals(name) || "sendImportVideo".equals(name)
                || "sendRadioSearch".equals(name)) {
                discoveryCallCount++;
            }
            return null;
        }
    }
}
