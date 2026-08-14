package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.horizonradio.client.media.ClientMediaService;
import com.horizonradio.client.media.ClientMetadataCache;
import com.horizonradio.server.media.YouTubeMediaModels;
import com.horizonradio.server.media.YouTubeMetadataResolver;

public class AudioDownloadMetadataTest {

    @Test
    public void delegatesVideoMetadataExtractionToTheInjectedBoundedResolver() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-metadata-service");
        FixtureHttp http = new FixtureHttp(
            "{\"playabilityStatus\":{\"status\":\"OK\"},\"videoDetails\":{\"videoId\":\"dQw4w9WgXcQ\",\"title\":\"Service Fixture\",\"lengthSeconds\":\"90\",\"isLiveContent\":false}}");
        AudioDownloadService service = new AudioDownloadService(
            directory,
            new NoopBackend(),
            new YouTubeMetadataResolver(http));
        try {
            String result = service.extractVideoJson("https://youtu.be/dQw4w9WgXcQ")
                .get(2, TimeUnit.SECONDS);
            JsonObject video = new JsonParser().parse(result)
                .getAsJsonObject();
            assertEquals(
                "Service Fixture",
                video.get("title")
                    .getAsString());
            assertEquals(1, http.calls);
            assertEquals(1, http.closedInputs);
        } finally {
            service.shutdown();
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void productionMetadataFailureCompletesClientCacheLookupExceptionally() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-client-metadata-failure");
        AudioDownloadService service = new AudioDownloadService(
            directory,
            new NoopBackend(),
            new YouTubeMetadataResolver(new FixtureHttp("{\"playabilityStatus\":{\"status\":\"ERROR\"}}")));
        try {
            ClientMetadataCache cache = new ClientMetadataCache(
                new ClientMediaService(new YouTubeService(), service, new RadioBrowserService()));
            try {
                cache.video("dQw4w9WgXcQ")
                    .get(2, TimeUnit.SECONDS);
                fail("expected production metadata failure");
            } catch (ExecutionException expected) {
                assertNotNull(expected.getCause());
            }
            assertFalse(cache.isVideoLoading("dQw4w9WgXcQ"));
            assertNotNull(cache.getVideoError("dQw4w9WgXcQ"));
        } finally {
            service.shutdown();
            Files.deleteIfExists(directory);
        }
    }

    private static final class NoopBackend implements YouTubeMediaModels.AudioDownloadBackend {

        @Override
        public Path download(String videoId, Path destination, YouTubeMediaModels.CancellationToken token) {
            throw new AssertionError("download not expected");
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static final class FixtureHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] body;
        private int calls;
        private int closedInputs;

        private FixtureHttp(String json) {
            body = json.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] request,
            int timeoutMillis, long maximumBytes) {
            calls++;
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                body.length,
                new ByteArrayInputStream(body) {

                    @Override
                    public void close() throws java.io.IOException {
                        closedInputs++;
                        super.close();
                    }
                });
        }

        @Override
        public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
            long maximumBytes) {
            throw new AssertionError("metadata must not perform GET requests");
        }
    }
}
