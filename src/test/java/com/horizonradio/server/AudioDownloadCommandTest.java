package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class AudioDownloadCommandTest {

    @Test
    public void buildsTheExactActiveYtDlpCommandAndWavPath() {
        Path wavPath = Paths.get("audio-cache", "abc123.wav");

        List<String> command = AudioDownloadService.buildDownloadCommand(wavPath, "abc123");

        assertEquals(
            Arrays.asList(
                "yt-dlp",
                "--extractor-args",
                "youtube:player_client=android",
                "-f",
                "bestaudio[ext=m4a]/bestaudio/best",
                "--retries",
                "3",
                "--fragment-retries",
                "3",
                "--sleep-requests",
                "1",
                "-x",
                "--audio-format",
                "wav",
                "--audio-quality",
                "0",
                "-o",
                wavPath.toString(),
                "--no-playlist",
                "https://www.youtube.com/watch?v=abc123"),
            command);
    }

    @Test
    public void buildsTheMetadataOnlyPlaylistCommand() {
        String playlistUrl = "https://www.youtube.com/watch?v=abc123&list=PLtest";

        assertEquals(
            Arrays.asList(
                "yt-dlp",
                "--extractor-args",
                "youtube:player_client=android",
                "--flat-playlist",
                "--dump-single-json",
                "--skip-download",
                "--quiet",
                "--no-warnings",
                "--yes-playlist",
                playlistUrl),
            AudioDownloadService.buildPlaylistCommand(playlistUrl));
    }

    @Test
    public void buildsTheMetadataOnlyVideoCommand() {
        String videoUrl = "https://youtu.be/abc123";

        assertEquals(
            Arrays.asList(
                "yt-dlp",
                "--extractor-args",
                "youtube:player_client=android",
                "--dump-single-json",
                "--skip-download",
                "--quiet",
                "--no-warnings",
                "--no-playlist",
                videoUrl),
            AudioDownloadService.buildVideoMetadataCommand(videoUrl));
    }

    @Test
    public void buildsTheChartDurationCommand() {
        assertTrue(
            AudioDownloadService.buildVideoDurationCommand(Arrays.asList("one", "two"))
                .contains("%(id)s\\t%(duration_string)s"));
    }

    @Test
    public void addsConfiguredBrowserCookiesToDownloadCommand() {
        List<String> command = AudioDownloadService
            .buildDownloadCommand(Paths.get("audio-cache", "abc123.wav"), "abc123", "chrome", "");

        assertTrue(command.contains("--cookies-from-browser"));
        assertTrue(command.contains("chrome"));
    }

    @Test
    public void returnsExistingWavAsCacheHitWithoutStartingDownload() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-audio-test");
        AudioDownloadService service = new AudioDownloadService(directory, false);
        Path expected = directory.resolve("cached-video.wav");
        Files.createFile(expected);

        try {
            assertEquals(expected, service.getFilePath("cached-video"));
            assertEquals(
                expected,
                service.download("cached-video")
                    .get(2, TimeUnit.SECONDS));
        } finally {
            service.shutdown();
            Files.deleteIfExists(expected);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void boundsStoredOutputWhileDrainingTheEntireStream() throws IOException {
        FullyReadInputStream input = new FullyReadInputStream("0123456789".getBytes(StandardCharsets.UTF_8));

        String output = AudioDownloadService.collectProcessOutput(input, 4);

        assertEquals("0123", output);
        assertTrue(input.isFullyRead());
        assertFalse(output.length() > 4);
    }

    @Test
    public void deletesTheWavAndReportsUnavailableDependenciesWhenChecksAreSkipped() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-audio-delete-test");
        AudioDownloadService service = new AudioDownloadService(directory, false);
        Path expected = directory.resolve("delete-me.wav");
        Files.createFile(expected);

        try {
            assertFalse(service.isDependenciesAvailable());
            service.delete("delete-me");
            assertFalse(Files.exists(expected));
        } finally {
            service.shutdown();
            Files.deleteIfExists(expected);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void waitsForForcedTerminationAndClosesTheOutputCollectorInput() throws Exception {
        TrackingProcess process = new TrackingProcess();
        Method runProcess = AudioDownloadService.class
            .getDeclaredMethod("runProcess", Process.class, long.class, TimeUnit.class);
        runProcess.setAccessible(true);

        runProcess.invoke(null, process, 1L, TimeUnit.MILLISECONDS);

        assertTrue(process.destroyed.get());
        assertTrue(process.destroyedForcibly.get());
        assertTrue(process.waitedAfterForcedDestroy.get());
        assertTrue(process.input.closed.get());
        assertNoOutputCollectorThreadRemains();
    }

    @Test
    public void returnsWhenForcedProcessStillRefusesTerminationAfterTheDeadline() throws Exception {
        StubbornProcess process = new StubbornProcess(new ByteArrayInputStream(new byte[0]));
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread runner = startRunProcess(process, completed, failure);

        try {
            assertTrue(process.destroyedForcibly.await(1L, TimeUnit.SECONDS));
            assertTrue("process cleanup exceeded its bounded deadline", completed.await(2500L, TimeUnit.MILLISECONDS));
            assertNoFailure(failure);
        } finally {
            process.release();
            runner.join(1000L);
        }
    }

    @Test
    public void returnsWhenOutputCollectorStillRefusesTerminationAfterTheDeadline() throws Exception {
        StubbornInputStream input = new StubbornInputStream();
        FinishedProcess process = new FinishedProcess(input);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread runner = startRunProcess(process, completed, failure);

        try {
            assertTrue("output collector exceeded its bounded deadline", completed.await(2500L, TimeUnit.MILLISECONDS));
            assertNoFailure(failure);
        } finally {
            input.release();
            runner.join(1000L);
        }
    }

    @Test
    public void drainsNormalProcessOutputBeforeClosingProcessStreams() throws Exception {
        final String diagnostic = "ffmpeg not found: final diagnostic";
        final NormalOutputProcess process = new NormalOutputProcess(diagnostic);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Object> result = new AtomicReference<Object>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread runner = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    Method runProcess = AudioDownloadService.class
                        .getDeclaredMethod("runProcess", Process.class, long.class, TimeUnit.class);
                    runProcess.setAccessible(true);
                    result.set(runProcess.invoke(null, process, 1L, TimeUnit.SECONDS));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    completed.countDown();
                }
            }
        }, "HorizonRadio-Test-Normal-Process");
        runner.setDaemon(true);
        runner.start();

        try {
            assertTrue(process.input.readStarted.await(1L, TimeUnit.SECONDS));
            process.input.release();
            assertTrue(completed.await(1L, TimeUnit.SECONDS));
            assertNoFailure(failure);

            Field outputField = result.get()
                .getClass()
                .getDeclaredField("output");
            outputField.setAccessible(true);
            assertEquals(diagnostic, outputField.get(result.get()));
            assertTrue(process.input.closed.get());
        } finally {
            process.input.release();
            runner.join(1000L);
        }
    }

    @Test
    public void shutdownWaitsForAnActiveDownloadTaskToFinish() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-audio-shutdown-test");
        AudioDownloadService service = new AudioDownloadService(directory, false);
        Field executorField = AudioDownloadService.class.getDeclaredField("downloadExecutor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(service);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        try {
            executor.submit(new Runnable() {

                @Override
                public void run() {
                    started.countDown();
                    try {
                        while (!release.await(50L, TimeUnit.MILLISECONDS)) {
                            // Keep the task active until shutdown has interrupted it.
                        }
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        try {
                            release.await(2L, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread()
                                .interrupt();
                        }
                    } finally {
                        finished.countDown();
                    }
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));

            service.shutdown();

            assertTrue(interrupted.await(1L, TimeUnit.SECONDS));
            assertTrue(finished.await(1L, TimeUnit.SECONDS));
            assertTrue(executor.isTerminated());
        } finally {
            release.countDown();
            service.shutdown();
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void shutdownReturnsAfterTheExecutorDeadlineWhenTaskRefusesInterruption() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-audio-bounded-shutdown-test");
        AudioDownloadService service = new AudioDownloadService(directory, false);
        Field executorField = AudioDownloadService.class.getDeclaredField("downloadExecutor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(service);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch shutdownCompleted = new CountDownLatch(1);
        Thread shutdownThread = new Thread(new Runnable() {

            @Override
            public void run() {
                service.shutdown();
                shutdownCompleted.countDown();
            }
        }, "HorizonRadio-Test-Shutdown");
        shutdownThread.setDaemon(true);

        try {
            executor.submit(new Runnable() {

                @Override
                public void run() {
                    started.countDown();
                    try {
                        while (!release.await(50L, TimeUnit.MILLISECONDS)) {
                            // Keep the task active until the test releases it.
                        }
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        try {
                            while (!release.await(50L, TimeUnit.MILLISECONDS)) {
                                // Ignore interruption until the test releases the task.
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread()
                                .interrupt();
                        }
                    }
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));

            shutdownThread.start();

            assertTrue(
                "executor shutdown exceeded its bounded deadline",
                shutdownCompleted.await(3500L, TimeUnit.MILLISECONDS));
            assertTrue(interrupted.await(1L, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            shutdownThread.join(1000L);
            service.shutdown();
            Files.deleteIfExists(directory);
        }
    }

    private static Thread startRunProcess(final Process process, final CountDownLatch completed,
        final AtomicReference<Throwable> failure) {
        Thread runner = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    Method runProcess = AudioDownloadService.class
                        .getDeclaredMethod("runProcess", Process.class, long.class, TimeUnit.class);
                    runProcess.setAccessible(true);
                    runProcess.invoke(null, process, 1L, TimeUnit.MILLISECONDS);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    completed.countDown();
                }
            }
        }, "HorizonRadio-Test-Process");
        runner.setDaemon(true);
        runner.start();
        return runner;
    }

    private static void assertNoFailure(AtomicReference<Throwable> failure) {
        if (failure.get() != null) {
            throw new AssertionError("process test failed", failure.get());
        }
    }

    private static void assertNoOutputCollectorThreadRemains() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000L;
        while (System.currentTimeMillis() < deadline) {
            boolean collectorAlive = false;
            for (Thread thread : Thread.getAllStackTraces()
                .keySet()) {
                if ("HorizonRadio-Downloader-Output".equals(thread.getName()) && thread.isAlive()) {
                    collectorAlive = true;
                    break;
                }
            }
            if (!collectorAlive) {
                return;
            }
            Thread.sleep(10L);
        }
        assertFalse("output collector thread is still alive", hasLiveOutputCollectorThread());
    }

    private static boolean hasLiveOutputCollectorThread() {
        for (Thread thread : Thread.getAllStackTraces()
            .keySet()) {
            if ("HorizonRadio-Downloader-Output".equals(thread.getName()) && thread.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static final class TrackingProcess extends Process {

        private final TrackingInputStream input = new TrackingInputStream();
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final AtomicBoolean destroyedForcibly = new AtomicBoolean();
        private final AtomicBoolean waitedAfterForcedDestroy = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStreamAdapter();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            if (destroyedForcibly.get()) {
                waitedAfterForcedDestroy.set(true);
                terminated.set(true);
            }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            if (destroyedForcibly.get()) {
                waitedAfterForcedDestroy.set(true);
                terminated.set(true);
                return true;
            }
            return false;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public Process destroyForcibly() {
            destroyedForcibly.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            return !terminated.get();
        }
    }

    private static final class StubbornProcess extends Process {

        private final InputStream input;
        private final CountDownLatch destroyedForcibly = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private StubbornProcess(InputStream input) {
            this.input = input;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStreamAdapter();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            released.await();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (destroyedForcibly.getCount() > 0L) {
                return false;
            }
            return released.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // This process refuses orderly destruction.
        }

        @Override
        public Process destroyForcibly() {
            destroyedForcibly.countDown();
            return this;
        }

        @Override
        public boolean isAlive() {
            return released.getCount() > 0L;
        }

        private void release() {
            released.countDown();
        }
    }

    private static final class FinishedProcess extends Process {

        private final InputStream input;

        private FinishedProcess(InputStream input) {
            this.input = input;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStreamAdapter();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // This process has already finished.
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }

    private static final class NormalOutputProcess extends Process {

        private final NormalOutputInputStream input;

        private NormalOutputProcess(String output) {
            this.input = new NormalOutputInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStreamAdapter();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // This process has already finished.
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }

    private static final class NormalOutputInputStream extends InputStream {

        private final byte[] output;
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private int offset;

        private NormalOutputInputStream(byte[] output) {
            this.output = output;
        }

        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int count = read(buffer, 0, 1);
            return count == -1 ? -1 : buffer[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            readStarted.countDown();
            try {
                released.await();
            } catch (InterruptedException exception) {
                Thread.currentThread()
                    .interrupt();
                throw new IOException("interrupted", exception);
            }
            if (closed.get() || this.offset == output.length) {
                return -1;
            }
            int count = Math.min(length, output.length - this.offset);
            System.arraycopy(output, this.offset, buffer, offset, count);
            this.offset += count;
            return count;
        }

        @Override
        public void close() {
            closed.set(true);
            released.countDown();
        }

        private void release() {
            released.countDown();
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            while (!closed.get()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IOException("interrupted", exception);
                }
            }
            return -1;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class StubbornInputStream extends InputStream {

        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            while (released.getCount() > 0L) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    // Ignore interruption to model a reader that will not stop promptly.
                }
            }
            return -1;
        }

        @Override
        public void close() {
            // Ignore close until the test explicitly releases the reader.
        }

        private void release() {
            released.countDown();
        }
    }

    private static final class ByteArrayOutputStreamAdapter extends OutputStream {

        @Override
        public void write(int value) {
            // No process stdin is needed by this test double.
        }
    }

    private static final class FullyReadInputStream extends ByteArrayInputStream {

        private boolean fullyRead;

        private FullyReadInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public int read() {
            int value = super.read();
            if (value == -1) {
                fullyRead = true;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            int count = super.read(buffer, offset, length);
            if (count == -1) {
                fullyRead = true;
            }
            return count;
        }

        private boolean isFullyRead() {
            return fullyRead;
        }
    }
}
