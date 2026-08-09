package com.horizonradio.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.horizonradio.core.model.RadioStation;

public class RadioStreamServiceTest {

    private static final RadioStation STATION = new RadioStation(
        "station",
        "Test station",
        "https://stream.example/radio",
        true,
        false);

    @Test
    public void buildsFfmpegCommandForFixedPcmOutput() {
        assertEquals(
            Arrays.asList(
                "ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "warning",
                "-i",
                "https://stream.example/radio",
                "-vn",
                "-ac",
                "2",
                "-ar",
                "44100",
                "-acodec",
                "pcm_s16le",
                "-f",
                "s16le",
                "pipe:1"),
            RadioStreamService.buildFfmpegCommand("https://stream.example/radio"));
    }

    @Test
    public void firstDataCallbackStartsAtSequenceZero() throws Exception {
        ControlledInputStream output = new ControlledInputStream(new byte[] { 1, 2, 3, 4 });
        FakeProcess process = new FakeProcess(output);
        RadioStreamService service = new RadioStreamService(new FakeProcessFactory(process));
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 5L, listener);

            assertTrue(listener.awaitReady(1));
            assertEquals(5L, listener.readyGeneration);
            assertEquals(STATION, listener.readyStation);
            assertEquals(0L, listener.firstSequence);
            assertArrayEquals(new byte[] { 1, 2, 3, 4 }, listener.readyData);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void partialPcmWaitsForACompleteFrameAndPreservesRemainder() throws Exception {
        ControlledInputStream output = new ControlledInputStream(new byte[] { 1, 2, 3 });
        RadioStreamService service = new RadioStreamService(new FakeProcessFactory(new FakeProcess(output)));
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 6L, listener);

            assertFalse(listener.awaitReadyFor(100L));
            output.offer(new byte[] { 4, 5 });
            assertTrue(listener.awaitReady(1));
            assertArrayEquals(new byte[] { 1, 2, 3, 4 }, listener.readyData);

            service.promoteCandidate(6L);
            output.offer(new byte[] { 6, 7, 8 });
            assertTrue(listener.awaitChunks(1));
            assertArrayEquals(new byte[] { 5, 6, 7, 8 }, listener.chunks.get(0));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void promotedCandidateEmitsFollowingChunksInOrder() throws Exception {
        ControlledInputStream output = new ControlledInputStream(new byte[] { 1, 2, 3, 4 });
        RadioStreamService service = new RadioStreamService(new FakeProcessFactory(new FakeProcess(output)));
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 3L, listener);
            assertTrue(listener.awaitReady(1));
            service.promoteCandidate(3L);

            output.offer(new byte[] { 5, 6, 7, 8 });
            output.offer(new byte[] { 9, 10, 11, 12 });
            assertTrue(listener.awaitChunks(2));
            assertEquals(Arrays.asList(Long.valueOf(1L), Long.valueOf(2L)), listener.chunkSequences);
            assertArrayEquals(new byte[] { 5, 6, 7, 8 }, listener.chunks.get(0));
            assertArrayEquals(new byte[] { 9, 10, 11, 12 }, listener.chunks.get(1));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void publishedSessionFailsWhenPcmReadHangsAfterReady() throws Exception {
        ControlledInputStream output = new ControlledInputStream(new byte[] { 1, 2, 3, 4 });
        FakeProcess process = new FakeProcess(output);
        ExecutorService relayExecutor = Executors.newCachedThreadPool();
        ScheduledExecutorService deadlineExecutor = new ScheduledThreadPoolExecutor(1);
        RadioStreamService service = new RadioStreamService(
            new FakeProcessFactory(process),
            relayExecutor,
            deadlineExecutor,
            1000L,
            50L,
            RadioStreamService.TimeoutRaceHook.NONE);
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 7L, listener);
            assertTrue(listener.awaitReady(1));
            service.promoteCandidate(7L);

            assertTrue(listener.awaitFailure(1));
            assertEquals(7L, listener.failureGeneration);
            assertTrue(listener.failureMessage.contains("stopped producing PCM data"));
            assertTrue(waitForDestroyed(process));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void processEndingBeforeFirstDataReportsFailure() throws Exception {
        ControlledInputStream output = new ControlledInputStream();
        output.finish();
        RadioStreamService service = new RadioStreamService(new FakeProcessFactory(new FakeProcess(output)));
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 8L, listener);

            assertTrue(listener.awaitFailure(1));
            assertEquals(8L, listener.failureGeneration);
            assertTrue(listener.failureMessage.contains("ended"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void stoppingCandidateLeavesPublishedSessionRunningAndSuppressesStaleCallbacks() throws Exception {
        ControlledInputStream publishedOutput = new ControlledInputStream(new byte[] { 1, 2, 3, 4 });
        FakeProcess publishedProcess = new FakeProcess(publishedOutput);
        ControlledInputStream candidateOutput = new ControlledInputStream();
        FakeProcess candidateProcess = new FakeProcess(candidateOutput);
        FakeProcessFactory processFactory = new FakeProcessFactory(publishedProcess, candidateProcess);
        RadioStreamService service = new RadioStreamService(processFactory);
        RecordingListener publishedListener = new RecordingListener();
        RecordingListener candidateListener = new RecordingListener();
        try {
            service.startCandidate(STATION, 1L, publishedListener);
            assertTrue(publishedListener.awaitReady(1));
            service.promoteCandidate(1L);

            service.startCandidate(STATION, 2L, candidateListener);
            assertTrue(processFactory.awaitStarts(2));
            service.stopGeneration(2L);
            assertTrue(waitForDestroyed(candidateProcess));
            assertFalse(publishedProcess.destroyed);

            publishedOutput.offer(new byte[] { 5, 6, 7, 8 });
            assertTrue(publishedListener.awaitChunks(1));
            assertEquals(
                1L,
                publishedListener.chunkSequences.get(0)
                    .longValue());
            assertFalse(candidateListener.hasCallbacks());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void candidateDoesNotReadPastReadyUntilPromotionKeepsSequenceContinuous() throws Exception {
        PromotionGateInputStream output = new PromotionGateInputStream(
            new byte[] { 1, 2, 3, 4 },
            new byte[] { 5, 6, 7, 8 });
        RadioStreamService service = new RadioStreamService(new FakeProcessFactory(new FakeProcess(output)));
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 11L, listener);
            assertTrue(listener.awaitReady(1));

            assertFalse(output.awaitSecondReadAttempt(200L));
            service.promoteCandidate(11L);

            assertTrue(listener.awaitChunks(1));
            assertEquals(
                1L,
                listener.chunkSequences.get(0)
                    .longValue());
            assertArrayEquals(new byte[] { 5, 6, 7, 8 }, listener.chunks.get(0));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void timeoutReleasedAfterReadyPromotionDoesNotRemovePublishedSession() throws Exception {
        ControlledInputStream output = new ControlledInputStream();
        ExecutorService relayExecutor = Executors.newCachedThreadPool();
        ScheduledExecutorService deadlineExecutor = new ScheduledThreadPoolExecutor(1);
        BlockingTimeoutHook timeoutHook = new BlockingTimeoutHook();
        RadioStreamService service = new RadioStreamService(
            new FakeProcessFactory(new FakeProcess(output)),
            relayExecutor,
            deadlineExecutor,
            1L,
            timeoutHook);
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 12L, listener);
            assertTrue(timeoutHook.awaitCheck());

            output.offer(new byte[] { 1, 2, 3, 4 });
            assertTrue(listener.awaitReady(1));
            service.promoteCandidate(12L);
            timeoutHook.releaseCheck();

            assertFalse(listener.awaitFailureFor(200L));
            output.offer(new byte[] { 5, 6, 7, 8 });
            assertTrue(listener.awaitChunks(1));
            assertEquals(
                1L,
                listener.chunkSequences.get(0)
                    .longValue());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void stopWaitsForAdmittedReadyCallbackBeforeInvalidatingSession() throws Exception {
        ControlledInputStream output = new ControlledInputStream(new byte[] { 1, 2, 3, 4 });
        RadioStreamService service = new RadioStreamService(new FakeProcessFactory(new FakeProcess(output)));
        BlockingReadyListener listener = new BlockingReadyListener();
        final CountDownLatch stopReturned = new CountDownLatch(1);
        try {
            service.startCandidate(STATION, 13L, listener);
            assertTrue(listener.awaitReadyEntry());

            Thread stopper = new Thread(new Runnable() {

                @Override
                public void run() {
                    service.stopGeneration(13L);
                    stopReturned.countDown();
                }
            });
            stopper.start();
            assertFalse(stopReturned.await(200L, TimeUnit.MILLISECONDS));

            listener.releaseReady();
            assertTrue(stopReturned.await(1L, TimeUnit.SECONDS));
            assertEquals(1, listener.readyCalls);
        } finally {
            listener.releaseReady();
            service.shutdown();
        }
    }

    @Test
    public void forcedTerminationWaitsForTheForcedProcessExit() throws Exception {
        ControlledInputStream output = new ControlledInputStream(new byte[] { 1, 2, 3, 4 });
        FakeProcess process = new FakeProcess(output, true);
        RadioStreamService service = new RadioStreamService(new FakeProcessFactory(process));
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 14L, listener);
            assertTrue(listener.awaitReady(1));
            service.promoteCandidate(14L);

            service.stopGeneration(14L);

            assertTrue(process.forceDestroyCalled);
            assertTrue(process.waitAfterForceCalled);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void shutdownRejectionDoesNotInvokeFailureListenerWhileHoldingLifecycleLock() throws Exception {
        RadioStreamService service = new RadioStreamService(new FakeProcessFactory());
        CrossThreadFailureListener listener = new CrossThreadFailureListener(service);
        try {
            service.shutdown();
            service.startCandidate(STATION, 15L, listener);

            assertTrue(listener.awaitWorker());
        } finally {
            service.shutdown();
        }
    }

    private boolean waitForDestroyed(FakeProcess process) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000L;
        while (!process.destroyed && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        return process.destroyed;
    }

    private static final class FakeProcessFactory implements RadioStreamService.ProcessFactory {

        private final Deque<FakeProcess> processes = new ArrayDeque<FakeProcess>();
        private int starts;

        private FakeProcessFactory(FakeProcess... processes) {
            this.processes.addAll(Arrays.asList(processes));
        }

        @Override
        public synchronized Process start(List<String> command) throws IOException {
            if (processes.isEmpty()) {
                throw new IOException("No fake process configured");
            }
            starts++;
            notifyAll();
            return processes.removeFirst();
        }

        private synchronized boolean awaitStarts(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 1000L;
            while (starts < count && System.currentTimeMillis() < deadline) {
                wait(Math.max(1L, deadline - System.currentTimeMillis()));
            }
            return starts >= count;
        }
    }

    private static final class FakeProcess extends Process {

        private final InputStream input;
        private final InputStream error = new ByteArrayInputStream(new byte[0]);
        private final OutputStream output = new ByteArrayOutputStream();
        private final boolean requiresForce;
        private volatile boolean destroyed;
        private volatile boolean forceDestroyCalled;
        private volatile boolean waitAfterForceCalled;

        private FakeProcess(InputStream input) {
            this(input, false);
        }

        private FakeProcess(InputStream input, boolean requiresForce) {
            this.input = input;
            this.requiresForce = requiresForce;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return error;
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (!destroyed) {
                Thread.sleep(10L);
            }
            return 143;
        }

        @Override
        public int exitValue() {
            if (!destroyed) {
                throw new IllegalThreadStateException("Fake process is still running");
            }
            return 143;
        }

        @Override
        public void destroy() {
            if (requiresForce) {
                return;
            }
            destroyed = true;
            closeStreams();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            if (requiresForce && !forceDestroyCalled) {
                return false;
            }
            if (forceDestroyCalled) {
                waitAfterForceCalled = true;
            }
            return destroyed;
        }

        @Override
        public Process destroyForcibly() {
            forceDestroyCalled = true;
            destroyed = true;
            closeStreams();
            return this;
        }

        private void closeStreams() {
            try {
                input.close();
                error.close();
                output.close();
            } catch (IOException ignored) {
                // Test process cleanup is best effort like a real process cleanup.
            }
        }
    }

    private static final class PromotionGateInputStream extends InputStream {

        private final byte[] first;
        private final byte[] second;
        private final CountDownLatch secondReadAttempt = new CountDownLatch(1);
        private int reads;
        private boolean closed;

        private PromotionGateInputStream(byte[] first, byte[] second) {
            this.first = Arrays.copyOf(first, first.length);
            this.second = Arrays.copyOf(second, second.length);
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            if (closed) {
                return -1;
            }
            byte[] next;
            if (reads == 0) {
                next = first;
            } else if (reads == 1) {
                secondReadAttempt.countDown();
                next = second;
            } else {
                return -1;
            }
            if (next.length > length) {
                throw new IOException("Test chunk exceeds requested buffer");
            }
            reads++;
            System.arraycopy(next, 0, bytes, offset, next.length);
            return next.length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
        }

        private boolean awaitSecondReadAttempt(long timeoutMillis) throws InterruptedException {
            return secondReadAttempt.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public synchronized void close() {
            closed = true;
        }
    }

    private static final class ControlledInputStream extends InputStream {

        private final Deque<byte[]> chunks = new ArrayDeque<byte[]>();
        private boolean finished;

        private ControlledInputStream(byte[]... initialChunks) {
            for (byte[] chunk : initialChunks) {
                offer(chunk);
            }
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            while (chunks.isEmpty() && !finished) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IOException("Interrupted while waiting for PCM", exception);
                }
            }
            if (chunks.isEmpty()) {
                return -1;
            }
            byte[] chunk = chunks.removeFirst();
            if (chunk.length > length) {
                throw new IOException("Test chunk exceeds requested buffer");
            }
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            return chunk.length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
        }

        private synchronized void offer(byte[] chunk) {
            if (finished) {
                throw new IllegalStateException("Input stream is closed");
            }
            chunks.add(Arrays.copyOf(chunk, chunk.length));
            notifyAll();
        }

        private synchronized void finish() {
            finished = true;
            notifyAll();
        }

        @Override
        public void close() {
            finish();
        }
    }

    private static final class RecordingListener implements RadioStreamService.RadioStreamListener {

        private long readyGeneration;
        private RadioStation readyStation;
        private long firstSequence;
        private byte[] readyData;
        private long failureGeneration;
        private String failureMessage;
        private final List<Long> chunkSequences = new ArrayList<Long>();
        private final List<byte[]> chunks = new ArrayList<byte[]>();

        @Override
        public synchronized void onReady(long generation, RadioStation station, long firstSequence, byte[] data) {
            this.readyGeneration = generation;
            this.readyStation = station;
            this.firstSequence = firstSequence;
            this.readyData = Arrays.copyOf(data, data.length);
            notifyAll();
        }

        @Override
        public synchronized void onChunk(long generation, long sequence, byte[] data) {
            chunkSequences.add(Long.valueOf(sequence));
            chunks.add(Arrays.copyOf(data, data.length));
            notifyAll();
        }

        @Override
        public synchronized void onFailure(long generation, String message) {
            failureGeneration = generation;
            failureMessage = message;
            notifyAll();
        }

        private synchronized boolean awaitReady(int count) throws InterruptedException {
            return await(count, 0);
        }

        private synchronized boolean awaitReadyFor(long timeoutMillis) throws InterruptedException {
            if (readyData != null) {
                return true;
            }
            wait(timeoutMillis);
            return readyData != null;
        }

        private synchronized boolean awaitChunks(int count) throws InterruptedException {
            return await(count, 1);
        }

        private synchronized boolean awaitFailure(int count) throws InterruptedException {
            return await(count, 2);
        }

        private synchronized boolean awaitFailureFor(long timeoutMillis) throws InterruptedException {
            if (failureMessage != null) {
                return true;
            }
            wait(timeoutMillis);
            return failureMessage != null;
        }

        private boolean await(int count, int type) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 1000L;
            while (!hasCount(count, type) && System.currentTimeMillis() < deadline) {
                wait(Math.max(1L, deadline - System.currentTimeMillis()));
            }
            return hasCount(count, type);
        }

        private boolean hasCount(int count, int type) {
            if (type == 0) {
                return readyData != null;
            }
            if (type == 1) {
                return chunkSequences.size() >= count;
            }
            return failureMessage != null;
        }

        private synchronized boolean hasCallbacks() {
            return readyData != null || failureMessage != null || !chunkSequences.isEmpty();
        }
    }

    private static final class BlockingTimeoutHook implements RadioStreamService.TimeoutRaceHook {

        private final CountDownLatch checkEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCheck = new CountDownLatch(1);

        @Override
        public void beforeTimeoutCheck(long generation) {
            checkEntered.countDown();
            try {
                releaseCheck.await();
            } catch (InterruptedException exception) {
                Thread.currentThread()
                    .interrupt();
            }
        }

        private boolean awaitCheck() throws InterruptedException {
            return checkEntered.await(1L, TimeUnit.SECONDS);
        }

        private void releaseCheck() {
            releaseCheck.countDown();
        }
    }

    private static final class BlockingReadyListener implements RadioStreamService.RadioStreamListener {

        private final CountDownLatch readyEntered = new CountDownLatch(1);
        private final CountDownLatch releaseReady = new CountDownLatch(1);
        private volatile int readyCalls;

        @Override
        public void onReady(long generation, RadioStation station, long firstSequence, byte[] data) {
            readyCalls++;
            readyEntered.countDown();
            try {
                releaseReady.await();
            } catch (InterruptedException exception) {
                Thread.currentThread()
                    .interrupt();
            }
        }

        @Override
        public void onChunk(long generation, long sequence, byte[] data) {}

        @Override
        public void onFailure(long generation, String message) {}

        private boolean awaitReadyEntry() throws InterruptedException {
            return readyEntered.await(1L, TimeUnit.SECONDS);
        }

        private void releaseReady() {
            releaseReady.countDown();
        }
    }

    private static final class CrossThreadFailureListener implements RadioStreamService.RadioStreamListener {

        private final RadioStreamService service;
        private final CountDownLatch workerFinished = new CountDownLatch(1);

        private CrossThreadFailureListener(RadioStreamService service) {
            this.service = service;
        }

        @Override
        public void onReady(long generation, RadioStation station, long firstSequence, byte[] data) {}

        @Override
        public void onChunk(long generation, long sequence, byte[] data) {}

        @Override
        public void onFailure(long generation, String message) {
            Thread worker = new Thread(new Runnable() {

                @Override
                public void run() {
                    service.stopAll();
                    workerFinished.countDown();
                }
            });
            worker.start();
            try {
                worker.join(500L);
            } catch (InterruptedException exception) {
                Thread.currentThread()
                    .interrupt();
            }
        }

        private boolean awaitWorker() throws InterruptedException {
            return workerFinished.await(1L, TimeUnit.SECONDS);
        }
    }
}
