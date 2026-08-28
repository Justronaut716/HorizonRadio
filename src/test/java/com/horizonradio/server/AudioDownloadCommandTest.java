package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.horizonradio.media.concurrent.MediaExecutors;
import com.horizonradio.server.media.MediaException;
import com.horizonradio.server.media.PcmSink;
import com.horizonradio.server.media.YouTubeMediaModels;

public class AudioDownloadCommandTest {

    @Test
    public void returnsAnExistingWavAsACacheHitWithoutCallingTheBackend() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-cache");
        RecordingBackend backend = new RecordingBackend();
        AudioDownloadService service = new AudioDownloadService(
            directory,
            backend,
            MediaExecutors.newDiscoveryExecutor(),
            MediaExecutors.newDownloadExecutor());
        try {
            Path expected = directory.resolve("dQw4w9WgXcQ.wav");
            writeCanonicalWave(expected);
            assertEquals(
                expected,
                service.download("dQw4w9WgXcQ")
                    .get(2, TimeUnit.SECONDS));
            assertEquals(0, backend.calls.get());
        } finally {
            service.shutdown();
            Files.deleteIfExists(directory.resolve("dQw4w9WgXcQ.wav"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void deletesZeroCorruptAndStaleCacheEntriesBeforeRedownloading() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-invalid-cache");
        RecordingBackend backend = new RecordingBackend();
        backend.release();
        AudioDownloadService service = new AudioDownloadService(
            directory,
            backend,
            MediaExecutors.newDiscoveryExecutor(),
            MediaExecutors.newDownloadExecutor());
        String[] ids = { "dQw4w9WgXcQ", "a234567890_", "b234567890_" };
        try {
            Files.createFile(directory.resolve(ids[0] + ".wav"));
            Files.write(directory.resolve(ids[1] + ".wav"), new byte[] { 'R', 'I', 'F', 'F' });
            writeStaleWave(directory.resolve(ids[2] + ".wav"));
            for (String id : ids) {
                assertEquals(
                    directory.resolve(id + ".wav"),
                    service.download(id)
                        .get(2, TimeUnit.SECONDS));
            }
            assertEquals(3, backend.calls.get());
            for (String id : ids) {
                assertTrue(Files.size(directory.resolve(id + ".wav")) > 44L);
            }
        } finally {
            service.shutdown();
            for (String id : ids) Files.deleteIfExists(directory.resolve(id + ".wav"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void sharesOneInFlightDownloadAndCancellationRemovesIncompleteOutput() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-active");
        RecordingBackend backend = new RecordingBackend();
        AudioDownloadService service = new AudioDownloadService(
            directory,
            backend,
            MediaExecutors.newDiscoveryExecutor(),
            MediaExecutors.newDownloadExecutor());
        try {
            CompletableFuture<Path> first = service.download("dQw4w9WgXcQ");
            CompletableFuture<Path> second = service.download("dQw4w9WgXcQ");
            assertSame(first, second);
            assertTrue(backend.started.await(1, TimeUnit.SECONDS));
            service.cancelDownload("dQw4w9WgXcQ");
            assertTrue(first.isCancelled());
            assertEquals(1, backend.calls.get());
            assertFalse(Files.exists(directory.resolve("dQw4w9WgXcQ.wav")));
        } finally {
            backend.release();
            service.shutdown();
            Files.deleteIfExists(directory.resolve("dQw4w9WgXcQ.wav"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void cancellationCannotTargetAReplacementInstalledDuringTheOldOperationInterleaving() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-generation");
        RecordingBackend backend = new RecordingBackend();
        CountDownLatch cancellationEntered = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        AudioDownloadService service = new AudioDownloadService(directory, backend, () -> {
            cancellationEntered.countDown();
            try {
                releaseCancellation.await();
            } catch (InterruptedException exception) {
                Thread.currentThread()
                    .interrupt();
            }
        }, MediaExecutors.newDiscoveryExecutor(), MediaExecutors.newDownloadExecutor());
        AtomicReference<CompletableFuture<Path>> replacement = new AtomicReference<CompletableFuture<Path>>();
        Thread cancelling = new Thread(() -> service.cancelDownload("dQw4w9WgXcQ"), "cancel-old-generation");
        Thread replacing = new Thread(
            () -> replacement.set(service.download("dQw4w9WgXcQ")),
            "start-replacement-generation");
        try {
            CompletableFuture<Path> first = service.download("dQw4w9WgXcQ");
            assertTrue(backend.started.await(1, TimeUnit.SECONDS));
            cancelling.start();
            assertTrue(cancellationEntered.await(1, TimeUnit.SECONDS));
            replacing.start();
            releaseCancellation.countDown();
            cancelling.join(1000L);
            replacing.join(1000L);
            assertTrue(first.isCancelled());
            assertTrue(replacement.get() != null);
            backend.release();
            assertEquals(
                directory.resolve("dQw4w9WgXcQ.wav"),
                replacement.get()
                    .get(2, TimeUnit.SECONDS));
            assertEquals(2, backend.calls.get());
        } finally {
            releaseCancellation.countDown();
            backend.release();
            cancelling.join(1000L);
            replacing.join(1000L);
            service.shutdown();
            Files.deleteIfExists(directory.resolve("dQw4w9WgXcQ.wav"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void commitWinningTheOperationLockCompletesWithoutCancellationOrReplacementDeletion() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-service-commit-race");
        CommitRaceBackend backend = new CommitRaceBackend();
        CountDownLatch cancellationEntered = new CountDownLatch(1);
        AudioDownloadService service = new AudioDownloadService(
            directory,
            backend,
            new AudioDownloadService.CancellationInterleavingHook() {

                @Override
                public void afterOperationRemoved() {}

                @Override
                public void beforeOperationCancellation() {
                    cancellationEntered.countDown();
                }
            },
            MediaExecutors.newDiscoveryExecutor(),
            MediaExecutors.newDownloadExecutor());
        Thread cancelling = new Thread(() -> service.cancelDownload("dQw4w9WgXcQ"), "cancel-during-commit");
        try {
            CompletableFuture<Path> future = service.download("dQw4w9WgXcQ");
            assertTrue(backend.commitEntered.await(1, TimeUnit.SECONDS));
            cancelling.start();
            assertTrue(cancellationEntered.await(1, TimeUnit.SECONDS));
            backend.releaseCommit();
            cancelling.join(1000L);

            Path destination = directory.resolve("dQw4w9WgXcQ.wav");
            assertEquals(destination, future.get(2, TimeUnit.SECONDS));
            assertFalse(future.isCancelled());
            assertTrue(Files.exists(destination));
            assertEquals(
                destination,
                service.download("dQw4w9WgXcQ")
                    .get(2, TimeUnit.SECONDS));
            assertTrue(Files.exists(destination));
        } finally {
            backend.releaseCommit();
            cancelling.join(1000L);
            service.shutdown();
            Files.deleteIfExists(directory.resolve("dQw4w9WgXcQ.wav"));
            Files.deleteIfExists(directory);
        }
    }

    private static final class RecordingBackend implements YouTubeMediaModels.AudioDownloadBackend {

        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final Object monitor = new Object();
        private boolean released;

        @Override
        public Path download(String videoId, Path destination, YouTubeMediaModels.CancellationToken token)
            throws java.io.IOException {
            calls.incrementAndGet();
            started.countDown();
            synchronized (monitor) {
                while (!released && !token.isCancelled()) {
                    try {
                        monitor.wait(10L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread()
                            .interrupt();
                        throw new MediaException("download cancelled", exception);
                    }
                }
            }
            if (token.isCancelled()) {
                throw new MediaException("download cancelled");
            }
            writeCanonicalWave(destination);
            return destination;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        private void release() {
            synchronized (monitor) {
                released = true;
                monitor.notifyAll();
            }
        }
    }

    private static final class CommitRaceBackend implements YouTubeMediaModels.AudioDownloadBackend {

        private final CountDownLatch commitEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCommit = new CountDownLatch(1);

        @Override
        public Path download(final String videoId, final Path destination, YouTubeMediaModels.CancellationToken token)
            throws java.io.IOException {
            token.finish(new PcmSink() {

                @Override
                public void write(byte[] data, int offset, int length) {}

                @Override
                public void abort() {}

                @Override
                public void finish() throws java.io.IOException {
                    commitEntered.countDown();
                    try {
                        if (!releaseCommit.await(1, TimeUnit.SECONDS))
                            throw new java.io.IOException("commit release timed out");
                    } catch (InterruptedException exception) {
                        Thread.currentThread()
                            .interrupt();
                        throw new java.io.IOException("commit interrupted", exception);
                    }
                    writeCanonicalWave(destination);
                }
            });
            return destination;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        private void releaseCommit() {
            releaseCommit.countDown();
        }
    }

    private static void writeCanonicalWave(Path destination) throws java.io.IOException {
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

    private static void writeStaleWave(Path destination) throws java.io.IOException {
        byte[] wave = new byte[48];
        ascii(wave, 0, "RIFF");
        leInt(wave, 4, 36);
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
}
