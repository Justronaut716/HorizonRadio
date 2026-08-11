package com.horizonradio.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URL;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.server.media.RadioInputSession;

public class RadioStreamServiceTest {

    private static final RadioStation STATION = new RadioStation(
        "station",
        "Test station",
        "https://stream.example/radio",
        true,
        false);

    @Test
    public void firstDataCallbackStartsAtSequenceZero() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        RadioStreamService service = new RadioStreamService(sessions);
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 5L, listener);
            FakeSession session = sessions.awaitSession(0);

            session.emit(new byte[] { 1, 2, 3, 4 });

            assertTrue(listener.awaitReady());
            assertEquals(5L, listener.readyGeneration);
            assertEquals(STATION, listener.readyStation);
            assertEquals(0L, listener.firstSequence);
            assertArrayEquals(new byte[] { 1, 2, 3, 4 }, listener.readyData);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void candidateDoesNotPublishPartialPcmFrames() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        RadioStreamService service = new RadioStreamService(sessions);
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 6L, listener);
            FakeSession session = sessions.awaitSession(0);

            session.emit(new byte[] { 1, 2, 3 });
            assertFalse(listener.awaitReadyFor(100L));
            session.emit(new byte[] { 4, 5 });

            assertTrue(listener.awaitReady());
            assertArrayEquals(new byte[] { 1, 2, 3, 4 }, listener.readyData);

            service.promoteCandidate(6L);
            session.emit(new byte[] { 6, 7, 8 });
            assertTrue(listener.awaitChunks(1));
            assertArrayEquals(new byte[] { 5, 6, 7, 8 }, listener.chunks.get(0));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void promotedCandidateEmitsFollowingChunksInOrder() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        RadioStreamService service = new RadioStreamService(sessions);
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 3L, listener);
            FakeSession session = sessions.awaitSession(0);
            session.emit(new byte[] { 1, 2, 3, 4 });
            assertTrue(listener.awaitReady());
            service.promoteCandidate(3L);

            session.emit(new byte[] { 5, 6, 7, 8 });
            session.emit(new byte[] { 9, 10, 11, 12 });
            assertTrue(listener.awaitChunks(2));
            assertEquals(Arrays.asList(Long.valueOf(1L), Long.valueOf(2L)), listener.chunkSequences);
            assertArrayEquals(new byte[] { 5, 6, 7, 8 }, listener.chunks.get(0));
            assertArrayEquals(new byte[] { 9, 10, 11, 12 }, listener.chunks.get(1));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void failedCandidateLeavesPublishedSessionAlive() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        RadioStreamService service = new RadioStreamService(sessions);
        RecordingListener publishedListener = new RecordingListener();
        RecordingListener candidateListener = new RecordingListener();
        try {
            service.startCandidate(STATION, 1L, publishedListener);
            FakeSession published = sessions.awaitSession(0);
            published.emit(new byte[] { 1, 2, 3, 4 });
            assertTrue(publishedListener.awaitReady());
            service.promoteCandidate(1L);

            service.startCandidate(STATION, 2L, candidateListener);
            FakeSession candidate = sessions.awaitSession(1);
            candidate.fail("candidate failed");

            assertTrue(candidateListener.awaitFailure());
            published.emit(new byte[] { 5, 6, 7, 8 });
            assertTrue(publishedListener.awaitChunks(1));
            assertFalse(published.closed);
            assertTrue(candidate.closed);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void candidateDoesNotReadPastReadyUntilPromotionKeepsSequenceContinuous() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        RadioStreamService service = new RadioStreamService(sessions);
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 11L, listener);
            FakeSession session = sessions.awaitSession(0);
            session.emit(new byte[] { 1, 2, 3, 4 });
            assertTrue(listener.awaitReady());

            session.emit(new byte[] { 5, 6, 7, 8 });
            assertTrue(session.awaitSecondEmitAttempt());

            service.promoteCandidate(11L);

            assertTrue(listener.awaitChunks(1));
            assertEquals(1L, listener.chunkSequences.get(0).longValue());
            assertArrayEquals(new byte[] { 5, 6, 7, 8 }, listener.chunks.get(0));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void publishedSessionFailsWhenItStopsProducingPcm() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        ScheduledExecutorService deadlines = new ScheduledThreadPoolExecutor(1);
        RadioStreamService service = new RadioStreamService(sessions, deadlines, 1000L, 50L);
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 7L, listener);
            FakeSession session = sessions.awaitSession(0);
            session.emit(new byte[] { 1, 2, 3, 4 });
            assertTrue(listener.awaitReady());
            service.promoteCandidate(7L);

            assertTrue(listener.awaitFailure());
            assertEquals(7L, listener.failureGeneration);
            assertTrue(listener.failureMessage.contains("stopped producing PCM"));
            assertTrue(session.closed);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void timeoutReleasedAfterReadyPromotionDoesNotRemovePublishedSession() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        ScheduledExecutorService deadlines = new ScheduledThreadPoolExecutor(1);
        BlockingTimeoutHook timeoutHook = new BlockingTimeoutHook();
        RadioStreamService service = new RadioStreamService(
            sessions,
            deadlines,
            1L,
            1000L,
            timeoutHook);
        RecordingListener listener = new RecordingListener();
        try {
            service.startCandidate(STATION, 12L, listener);
            assertTrue(timeoutHook.awaitCheck());
            FakeSession session = sessions.awaitSession(0);
            session.emit(new byte[] { 1, 2, 3, 4 });
            assertTrue(listener.awaitReady());
            service.promoteCandidate(12L);
            timeoutHook.releaseCheck();

            assertFalse(listener.awaitFailureFor(200L));
            session.emit(new byte[] { 5, 6, 7, 8 });
            assertTrue(listener.awaitChunks(1));
            assertEquals(1L, listener.chunkSequences.get(0).longValue());
        } finally {
            timeoutHook.releaseCheck();
            service.shutdown();
        }
    }

    @Test
    public void staleGenerationCallbacksAreIgnoredAfterReplacement() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        RadioStreamService service = new RadioStreamService(sessions);
        RecordingListener staleListener = new RecordingListener();
        RecordingListener currentListener = new RecordingListener();
        try {
            service.startCandidate(STATION, 20L, staleListener);
            FakeSession stale = sessions.awaitSession(0);
            service.startCandidate(STATION, 21L, currentListener);
            FakeSession current = sessions.awaitSession(1);

            stale.emit(new byte[] { 1, 2, 3, 4 });
            assertFalse(staleListener.hasCallbacks());
            current.emit(new byte[] { 5, 6, 7, 8 });
            assertTrue(currentListener.awaitReady());
            assertEquals(21L, currentListener.readyGeneration);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void stopAllClosesPublishedAndCandidateSessions() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        RadioStreamService service = new RadioStreamService(sessions);
        RecordingListener publishedListener = new RecordingListener();
        try {
            service.startCandidate(STATION, 30L, publishedListener);
            FakeSession published = sessions.awaitSession(0);
            published.emit(new byte[] { 1, 2, 3, 4 });
            assertTrue(publishedListener.awaitReady());
            service.promoteCandidate(30L);
            service.startCandidate(STATION, 31L, new RecordingListener());
            FakeSession candidate = sessions.awaitSession(1);

            service.stopAll();

            assertTrue(published.closed);
            assertTrue(candidate.closed);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void stopWaitsForAnAdmittedReadyCallbackBeforeInvalidatingSession() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        RadioStreamService service = new RadioStreamService(sessions);
        BlockingReadyListener listener = new BlockingReadyListener();
        final CountDownLatch stopReturned = new CountDownLatch(1);
        try {
            service.startCandidate(STATION, 13L, listener);
            FakeSession session = sessions.awaitSession(0);
            Thread emitter = new Thread(new Runnable() {

                @Override
                public void run() {
                    session.emit(new byte[] { 1, 2, 3, 4 });
                }
            });
            emitter.start();
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
    public void shutdownRejectionDoesNotInvokeFailureListenerWhileHoldingLifecycleLock() throws Exception {
        FakeSessionFactory sessions = new FakeSessionFactory();
        RadioStreamService service = new RadioStreamService(sessions);
        CrossThreadFailureListener listener = new CrossThreadFailureListener(service);
        try {
            service.shutdown();
            service.startCandidate(STATION, 15L, listener);

            assertTrue(listener.awaitWorker());
        } finally {
            service.shutdown();
        }
    }

    private static final class FakeSessionFactory implements RadioStreamService.SessionFactory {

        private final List<FakeSession> sessions = new ArrayList<FakeSession>();

        @Override
        public synchronized RadioStreamService.SessionHandle create(
            String streamUrl,
            RadioInputSession.RadioPcmListener listener) {
            FakeSession session = new FakeSession(listener);
            sessions.add(session);
            notifyAll();
            return session;
        }

        private synchronized FakeSession awaitSession(int index) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 1000L;
            while (sessions.size() <= index && System.currentTimeMillis() < deadline) {
                wait(Math.max(1L, deadline - System.currentTimeMillis()));
            }
            assertTrue("session was not created", sessions.size() > index);
            return sessions.get(index);
        }
    }

    private static final class FakeSession implements RadioStreamService.SessionHandle {

        private final RadioInputSession.RadioPcmListener listener;
        private final CountDownLatch secondEmitAttempt = new CountDownLatch(1);
        private final ExecutorService callbacks = Executors.newSingleThreadExecutor(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "HorizonRadio-RadioStreamServiceTest-Input");
                thread.setDaemon(true);
                return thread;
            }
        });
        private volatile boolean closed;
        private volatile int emitCount;

        private FakeSession(RadioInputSession.RadioPcmListener listener) {
            this.listener = listener;
        }

        @Override
        public void start() {}

        @Override
        public void close() {
            closed = true;
            callbacks.shutdownNow();
        }

        private void emit(final byte[] data) {
            if (closed) {
                return;
            }
            final boolean second = ++emitCount > 1;
            if (second) {
                secondEmitAttempt.countDown();
            }
            executeCallback(new Runnable() {

                @Override
                public void run() {
                    listener.onPcm(data);
                }
            });
        }

        private void fail(final String message) {
            if (closed) {
                return;
            }
            executeCallback(new Runnable() {

                @Override
                public void run() {
                    listener.onFailure(message);
                }
            });
        }

        private void executeCallback(Runnable callback) {
            try {
                callbacks.execute(callback);
            } catch (java.util.concurrent.RejectedExecutionException exception) {
                if (!closed) {
                    throw exception;
                }
            }
        }

        private boolean awaitSecondEmitAttempt() throws InterruptedException {
            return secondEmitAttempt.await(1L, TimeUnit.SECONDS);
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
            readyGeneration = generation;
            readyStation = station;
            this.firstSequence = firstSequence;
            readyData = Arrays.copyOf(data, data.length);
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

        private synchronized boolean awaitReady() throws InterruptedException {
            return awaitReadyFor(1000L);
        }

        private synchronized boolean awaitReadyFor(long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (readyData == null && System.currentTimeMillis() < deadline) {
                wait(Math.max(1L, deadline - System.currentTimeMillis()));
            }
            return readyData != null;
        }

        private synchronized boolean awaitChunks(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 1000L;
            while (chunkSequences.size() < count && System.currentTimeMillis() < deadline) {
                wait(Math.max(1L, deadline - System.currentTimeMillis()));
            }
            return chunkSequences.size() >= count;
        }

        private synchronized boolean awaitFailure() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 1000L;
            while (failureMessage == null && System.currentTimeMillis() < deadline) {
                wait(Math.max(1L, deadline - System.currentTimeMillis()));
            }
            return failureMessage != null;
        }

        private synchronized boolean awaitFailureFor(long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (failureMessage == null && System.currentTimeMillis() < deadline) {
                wait(Math.max(1L, deadline - System.currentTimeMillis()));
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
                Thread.currentThread().interrupt();
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
                Thread.currentThread().interrupt();
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
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitWorker() throws InterruptedException {
            return workerFinished.await(1L, TimeUnit.SECONDS);
        }
    }
}
