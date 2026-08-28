package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.horizonradio.server.media.MediaException;
import com.horizonradio.server.media.YouTubeMediaModels;
import com.horizonradio.server.media.YouTubeMetadataResolver;

public class AudioDownloadServiceTest {

    @Test
    public void metadataUsesDiscoveryExecutorWhenDownloadExecutorRejectsWork() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-metadata-executor");
        AudioDownloadService service = new AudioDownloadService(
            directory,
            new FailingBackend(),
            errorMetadataResolver(),
            new DirectExecutor(),
            new RejectingExecutor());
        try {
            assertNull(
                service.extractVideoJson("https://youtu.be/dQw4w9WgXcQ")
                    .get(2, TimeUnit.SECONDS));
        } finally {
            service.shutdown();
            deleteDirectory(directory);
        }
    }

    @Test
    public void rejectedDownloadSubmissionFailsAndDoesNotRemainActive() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-rejected-download");
        AudioDownloadService service = new AudioDownloadService(
            directory,
            new FailingBackend(),
            new DirectExecutor(),
            new RejectingExecutor());
        try {
            java.util.concurrent.CompletableFuture<Path> first = service.download("dQw4w9WgXcQ");
            java.util.concurrent.CompletableFuture<Path> second = service.download("dQw4w9WgXcQ");

            assertRejectedForQueueFull(first);
            assertRejectedForQueueFull(second);
            assertNotSame("a rejected operation must be removed from active downloads", first, second);
        } finally {
            service.shutdown();
            deleteDirectory(directory);
        }
    }

    @Test
    public void cleansUpAllCachedTracksWhenTheServiceStarts() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-startup");
        Path first = directory.resolve("a234567890_.wav");
        Path second = directory.resolve("b234567890_.wav");
        Path partial = directory.resolve("dQw4w9WgXcQ.wav.part-123456.wav");
        Files.write(first, new byte[60]);
        Files.write(second, new byte[60]);
        Files.write(partial, new byte[200]);
        AudioDownloadService service = new AudioDownloadService(
            directory,
            new FailingBackend(),
            new DirectExecutor(),
            new DirectExecutor());
        try {
            // The constructor clears the session-scoped cache, covering clients whose previous
            // run was killed and left files behind.
            assertFalse(Files.exists(first));
            assertFalse(Files.exists(second));
            assertFalse(Files.exists(partial));
        } finally {
            service.shutdown();
            deleteDirectory(directory);
        }
    }

    @Test
    public void deletesEveryCachedFileWhenCleaningUpTheCache() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-cleanup");
        AudioDownloadService service = new AudioDownloadService(
            directory,
            new FailingBackend(),
            new DirectExecutor(),
            new DirectExecutor());
        try {
            Path first = directory.resolve("a234567890_.wav");
            Path second = directory.resolve("b234567890_.wav");
            Path partial = directory.resolve("dQw4w9WgXcQ.wav.part-123456.wav");
            Files.write(first, new byte[60]);
            Files.write(second, new byte[60]);
            Files.write(partial, new byte[200]);

            service.cleanUpCache();

            // The cache is session-scoped: every file is deleted, partial downloads included.
            assertFalse(Files.exists(first));
            assertFalse(Files.exists(second));
            assertFalse(Files.exists(partial));
        } finally {
            service.shutdown();
            deleteDirectory(directory);
        }
    }

    @Test
    public void keepsCachedTracksAvailableForReplayWithinTheSession() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-replay");
        FailingBackend backend = new FailingBackend();
        AudioDownloadService service = new AudioDownloadService(
            directory,
            backend,
            new DirectExecutor(),
            new DirectExecutor());
        try {
            Path cached = directory.resolve("dQw4w9WgXcQ.wav");
            writeCanonicalWave(cached);
            assertEquals(
                cached,
                service.download("dQw4w9WgXcQ")
                    .get(2, TimeUnit.SECONDS));
            // A cache hit within the session must not trigger a re-download.
            assertEquals(0, backend.calls.get());
        } finally {
            service.shutdown();
            deleteDirectory(directory);
        }
    }

    @Test
    public void keepsOnlyTheListedTracksAndLeavesInFlightParts() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-keep");
        AudioDownloadService service = new AudioDownloadService(
            directory,
            new FailingBackend(),
            new DirectExecutor(),
            new DirectExecutor());
        try {
            Path keep = directory.resolve("a234567890_.wav");
            Path prune = directory.resolve("b234567890_.wav");
            Path pruneOther = directory.resolve("dQw4w9WgXcQ.wav");
            Path partial = directory.resolve("dQw4w9WgXcQ.wav.part-123456.wav");
            Files.write(keep, new byte[60]);
            Files.write(prune, new byte[60]);
            Files.write(pruneOther, new byte[60]);
            Files.write(partial, new byte[200]);

            service.keepOnlyTracks(Arrays.asList("a234567890_"));

            // Only the listed track survives; partial downloads never get in the way of a prune.
            assertTrue(Files.exists(keep));
            assertFalse(Files.exists(prune));
            assertFalse(Files.exists(pruneOther));
            assertTrue(Files.exists(partial));
        } finally {
            service.shutdown();
            deleteDirectory(directory);
        }
    }

    @Test
    public void emptyKeepSetDeletesEveryCachedTrack() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-empty-keep");
        AudioDownloadService service = new AudioDownloadService(
            directory,
            new FailingBackend(),
            new DirectExecutor(),
            new DirectExecutor());
        try {
            Path first = directory.resolve("a234567890_.wav");
            Path second = directory.resolve("b234567890_.wav");
            Files.write(first, new byte[60]);
            Files.write(second, new byte[60]);

            service.keepOnlyTracks(Collections.<String>emptyList());

            assertFalse(Files.exists(first));
            assertFalse(Files.exists(second));
        } finally {
            service.shutdown();
            deleteDirectory(directory);
        }
    }

    @Test
    public void retriesAfterTheRateLimitBackoffWindow() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-rate-retry");
        RateLimitedBackend backend = new RateLimitedBackend(2, 150L);
        AudioDownloadService service = new AudioDownloadService(
            directory,
            backend,
            new DirectExecutor(),
            new DirectExecutor());
        try {
            Path result = service.download("dQw4w9WgXcQ")
                .get(15, TimeUnit.SECONDS);

            assertEquals(directory.resolve("dQw4w9WgXcQ.wav"), result);
            assertEquals(3, backend.calls.get());
        } finally {
            service.shutdown();
            deleteDirectory(directory);
        }
    }

    @Test
    public void givesUpAfterTheMaximumRateLimitRetries() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-rate-giveup");
        RateLimitedBackend backend = new RateLimitedBackend(Integer.MAX_VALUE, 50L);
        AudioDownloadService service = new AudioDownloadService(
            directory,
            backend,
            new DirectExecutor(),
            new DirectExecutor());
        try {
            assertNull(
                service.download("dQw4w9WgXcQ")
                    .get(15, TimeUnit.SECONDS));
            assertEquals(6, backend.calls.get());
        } finally {
            service.shutdown();
            deleteDirectory(directory);
        }
    }

    private static final class FailingBackend implements YouTubeMediaModels.AudioDownloadBackend {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Path download(String videoId, Path destination, YouTubeMediaModels.CancellationToken token)
            throws IOException {
            calls.incrementAndGet();
            throw new IOException("audio download backend must not be called");
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static final class RateLimitedBackend implements YouTubeMediaModels.AudioDownloadBackend {

        private final int failures;
        private final long backoffMillis;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile long retryAt;

        private RateLimitedBackend(int failures, long backoffMillis) {
            this.failures = failures;
            this.backoffMillis = backoffMillis;
        }

        @Override
        public Path download(String videoId, Path destination, YouTubeMediaModels.CancellationToken token)
            throws IOException {
            int call = calls.incrementAndGet();
            if (call <= failures) {
                retryAt = System.currentTimeMillis() + backoffMillis;
                throw new IOException("HTTP request failed with status 403");
            }
            writeCanonicalWave(destination);
            return destination;
        }

        @Override
        public long nextRateLimitRetryAtMillis() {
            return retryAt;
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static void assertRejectedForQueueFull(java.util.concurrent.CompletableFuture<Path> future)
        throws Exception {
        try {
            future.get();
            fail("expected the rejected download to fail");
        } catch (ExecutionException exception) {
            assertTrue(exception.getCause() instanceof MediaException);
            assertEquals(
                "media queue is full",
                exception.getCause()
                    .getMessage());
            assertTrue(
                exception.getCause()
                    .getCause() instanceof RejectedExecutionException);
        }
    }

    private static YouTubeMetadataResolver errorMetadataResolver() {
        return new YouTubeMetadataResolver(new YouTubeMediaModels.HttpRequester() {

            @Override
            public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
                int timeoutMillis, long maximumBytes) {
                byte[] response = "{\"playabilityStatus\":{\"status\":\"ERROR\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new YouTubeMediaModels.HttpResponse(
                    url,
                    200,
                    "application/json",
                    response.length,
                    new ByteArrayInputStream(response));
            }

            @Override
            public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
                long maximumBytes) {
                throw new AssertionError("metadata must not perform GET requests");
            }
        });
    }

    private static final class RejectingExecutor extends AbstractExecutorService {

        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable task) {
            throw new RejectedExecutionException("test executor is saturated");
        }
    }

    private static final class DirectExecutor extends AbstractExecutorService {

        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable task) {
            if (shutdown) {
                throw new RejectedExecutionException("executor is shut down");
            }
            task.run();
        }
    }

    private static void writeCanonicalWave(Path destination) throws IOException {
        byte[] wave = new byte[48];
        ascii(wave, 0, "RIFF");
        leInt(wave, 4, 40);
        ascii(wave, 8, "WAVEfmt ");
        leInt(wave, 16, 16);
        leShort(wave, 20, 1);
        leShort(wave, 22, 2);
        leInt(wave, 24, 44100);
        leInt(wave, 28, 176400);
        leShort(wave, 32, 4);
        leShort(wave, 34, 16);
        ascii(wave, 36, "data");
        leInt(wave, 40, 4);
        Files.write(destination, wave);
    }

    private static void ascii(byte[] bytes, int offset, String value) {
        for (int i = 0; i < value.length(); i++) bytes[offset + i] = (byte) value.charAt(i);
    }

    private static void leShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void leInt(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        try {
            for (Path entry : stream) {
                Files.deleteIfExists(entry);
            }
        } finally {
            stream.close();
        }
        Files.deleteIfExists(directory);
    }
}
