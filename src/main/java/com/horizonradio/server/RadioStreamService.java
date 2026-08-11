package com.horizonradio.server;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.server.media.RadioInputSession;

/**
 * Relays one published Java radio input session and, during handover, one
 * candidate session.
 */
public class RadioStreamService {

    private static final Logger LOGGER = Logger.getLogger(RadioStreamService.class.getName());
    private static final int PCM_FRAME_BYTES = 4;
    private static final long FIRST_DATA_TIMEOUT_MILLIS = 15000L;
    private static final long INACTIVITY_TIMEOUT_MILLIS = 15000L;

    private final Object lock = new Object();
    private final SessionFactory sessionFactory;
    private final ScheduledExecutorService deadlineExecutor;
    private final TimeoutRaceHook timeoutRaceHook;
    private final long firstDataTimeoutMillis;
    private final long inactivityTimeoutMillis;
    private Session published;
    private Session candidate;
    private boolean shutDown;

    public RadioStreamService() {
        this(new SessionFactory() {

            @Override
            public SessionHandle create(String streamUrl, RadioInputSession.RadioPcmListener listener) {
                final RadioInputSession input = new RadioInputSession(streamUrl, listener);
                return new SessionHandle() {

                    @Override
                    public void start() {
                        input.start();
                    }

                    @Override
                    public void close() {
                        input.close();
                    }
                };
            }
        }, newDaemonDeadlineExecutor(), FIRST_DATA_TIMEOUT_MILLIS, INACTIVITY_TIMEOUT_MILLIS, TimeoutRaceHook.NONE);
    }

    RadioStreamService(SessionFactory sessionFactory) {
        this(
            sessionFactory,
            newDaemonDeadlineExecutor(),
            FIRST_DATA_TIMEOUT_MILLIS,
            INACTIVITY_TIMEOUT_MILLIS,
            TimeoutRaceHook.NONE);
    }

    RadioStreamService(SessionFactory sessionFactory, ScheduledExecutorService deadlineExecutor,
        long firstDataTimeoutMillis, long inactivityTimeoutMillis) {
        this(sessionFactory, deadlineExecutor, firstDataTimeoutMillis, inactivityTimeoutMillis, TimeoutRaceHook.NONE);
    }

    RadioStreamService(SessionFactory sessionFactory, ScheduledExecutorService deadlineExecutor,
        long firstDataTimeoutMillis, long inactivityTimeoutMillis, TimeoutRaceHook timeoutRaceHook) {
        if (sessionFactory == null || deadlineExecutor == null
            || firstDataTimeoutMillis <= 0L
            || inactivityTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("Radio relay dependencies must be provided");
        }
        this.sessionFactory = sessionFactory;
        this.deadlineExecutor = deadlineExecutor;
        this.firstDataTimeoutMillis = firstDataTimeoutMillis;
        this.inactivityTimeoutMillis = inactivityTimeoutMillis;
        this.timeoutRaceHook = timeoutRaceHook == null ? TimeoutRaceHook.NONE : timeoutRaceHook;
    }

    /** Starts an unpublished candidate. The current published session stays active until promotion. */
    public void startCandidate(RadioStation station, long generation, RadioStreamListener listener) {
        if (station == null || station.getStreamUrl() == null
            || station.getStreamUrl()
                .trim()
                .length() == 0
            || listener == null) {
            if (listener != null) {
                safeRejectedFailure(new Session(station, generation, listener), "Radio station stream is unavailable");
            }
            return;
        }

        final Session session = new Session(station, generation, listener);
        try {
            session.input = sessionFactory.create(station.getStreamUrl(), new RadioInputSession.RadioPcmListener() {

                @Override
                public void onPcm(byte[] pcm) {
                    dispatchPcm(session, pcm);
                }

                @Override
                public void onFailure(String message) {
                    failSession(session, message == null ? "Radio input session failed" : message);
                }
            });
        } catch (RuntimeException exception) {
            safeRejectedFailure(session, "Radio input could not be created: " + exception.getMessage());
            return;
        }
        if (session.input == null) {
            safeRejectedFailure(session, "Radio input could not be created");
            return;
        }

        Session replaced;
        boolean rejected;
        synchronized (lock) {
            rejected = shutDown;
            if (rejected) {
                replaced = null;
            } else {
                replaced = candidate;
                candidate = session;
                session.firstDataDeadline = deadlineExecutor.schedule(new Runnable() {

                    @Override
                    public void run() {
                        failForFirstDataDeadline(session);
                    }
                }, firstDataTimeoutMillis, TimeUnit.MILLISECONDS);
            }
        }
        if (rejected) {
            closeSessionResources(session);
            safeRejectedFailure(session, "Radio relay is shut down");
            return;
        }
        closeSession(replaced);
        try {
            session.input.start();
        } catch (RuntimeException exception) {
            failSession(session, "Radio input could not start: " + exception.getMessage());
        }
    }

    /** Publishes a ready candidate and closes the previously published source. */
    public void promoteCandidate(long generation) {
        Session previous;
        Session promoted;
        synchronized (lock) {
            if (candidate == null || candidate.generation != generation
                || !candidate.receivedFirstData
                || candidate.closed) {
                return;
            }
            previous = published;
            published = candidate;
            promoted = candidate;
            candidate = null;
            scheduleInactivityDeadlineLocked(promoted);
        }
        notifyPromotion(promoted);
        closeSession(previous);
    }

    /** Closes the published or candidate source matching the supplied generation. */
    public void stopGeneration(long generation) {
        Session publishedToStop = null;
        Session candidateToStop = null;
        synchronized (lock) {
            if (published != null && published.generation == generation) {
                publishedToStop = published;
                published = null;
            }
            if (candidate != null && candidate.generation == generation) {
                candidateToStop = candidate;
                candidate = null;
            }
        }
        closeSession(publishedToStop);
        closeSession(candidateToStop);
    }

    /** Closes both the published source and any in-progress handover candidate. */
    public void stopAll() {
        Session publishedToStop;
        Session candidateToStop;
        synchronized (lock) {
            publishedToStop = published;
            candidateToStop = candidate;
            published = null;
            candidate = null;
        }
        closeSession(publishedToStop);
        closeSession(candidateToStop);
    }

    /** Stops active inputs and releases the deadline executor. */
    public void shutdown() {
        synchronized (lock) {
            shutDown = true;
        }
        stopAll();
        deadlineExecutor.shutdownNow();
    }

    private void dispatchPcm(Session session, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        byte[] complete;
        synchronized (lock) {
            if (!isCurrentLocked(session) || session.closed) {
                return;
            }
            complete = completePcmFrames(session, data);
        }
        if (complete.length == 0) {
            return;
        }
        DispatchResult result = dispatchChunk(session, complete);
        if (result == DispatchResult.READY && !awaitPublication(session)) {
            return;
        }
    }

    private DispatchResult dispatchChunk(Session session, byte[] data) {
        boolean ready = false;
        boolean publishedChunk = false;
        long sequence;
        synchronized (lock) {
            if (!isCurrentLocked(session) || session.closed) {
                return DispatchResult.STOPPED;
            }
            sequence = session.nextSequence++;
            if (!session.receivedFirstData) {
                session.receivedFirstData = true;
                ready = true;
                cancelFirstDataDeadline(session);
            } else {
                publishedChunk = published == session;
                if (publishedChunk) {
                    scheduleInactivityDeadlineLocked(session);
                }
            }
        }
        if (ready) {
            safeReady(session, sequence, data);
            return DispatchResult.READY;
        } else if (publishedChunk) {
            safeChunk(session, sequence, data);
        }
        return DispatchResult.PUBLISHED;
    }

    private boolean awaitPublication(Session session) {
        synchronized (session.promotionGate) {
            while (true) {
                synchronized (lock) {
                    if (published == session && !session.closed) {
                        return true;
                    }
                    if (candidate != session || session.closed) {
                        return false;
                    }
                }
                try {
                    session.promotionGate.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                    return false;
                }
            }
        }
    }

    private void failForFirstDataDeadline(Session session) {
        timeoutRaceHook.beforeTimeoutCheck(session.generation);
        synchronized (session.callbackGate) {
            synchronized (lock) {
                if (candidate != session || session.receivedFirstData || session.closed) {
                    return;
                }
                candidate = null;
                session.closed = true;
                cancelDeadlines(session);
            }
            closeSessionResources(session);
            notifyPromotion(session);
            invokeFailure(
                session,
                "Radio input did not provide PCM data within " + firstDataTimeoutMillis + " milliseconds");
        }
    }

    private void failSession(Session session, String message) {
        synchronized (session.callbackGate) {
            synchronized (lock) {
                if (!isCurrentLocked(session) || session.closed) {
                    return;
                }
                if (published == session) {
                    published = null;
                }
                if (candidate == session) {
                    candidate = null;
                }
                session.closed = true;
                cancelDeadlines(session);
            }
            closeSessionResources(session);
            notifyPromotion(session);
            invokeFailure(session, message);
        }
    }

    private void closeSession(Session session) {
        if (session == null) {
            return;
        }
        synchronized (session.callbackGate) {
            if (session.closed) {
                return;
            }
            session.closed = true;
            cancelDeadlines(session);
            closeSessionResources(session);
        }
        notifyPromotion(session);
    }

    private static void closeSessionResources(Session session) {
        if (session.input != null) {
            session.input.close();
        }
    }

    private boolean isCurrentLocked(Session session) {
        return published == session || candidate == session;
    }

    private byte[] completePcmFrames(Session session, byte[] data) {
        int previousBytes = session.pcmRemainder.length;
        byte[] combined = new byte[previousBytes + data.length];
        System.arraycopy(session.pcmRemainder, 0, combined, 0, previousBytes);
        System.arraycopy(data, 0, combined, previousBytes, data.length);
        int completeBytes = combined.length - combined.length % PCM_FRAME_BYTES;
        session.pcmRemainder = Arrays.copyOfRange(combined, completeBytes, combined.length);
        return Arrays.copyOf(combined, completeBytes);
    }

    private void scheduleInactivityDeadlineLocked(final Session session) {
        cancelInactivityDeadline(session);
        final long deadlineToken = ++session.inactivityDeadlineToken;
        session.inactivityDeadline = deadlineExecutor.schedule(new Runnable() {

            @Override
            public void run() {
                failForInactivityDeadline(session, deadlineToken);
            }
        }, inactivityTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void failForInactivityDeadline(Session session, long deadlineToken) {
        synchronized (session.callbackGate) {
            synchronized (lock) {
                if (published != session || session.closed || session.inactivityDeadlineToken != deadlineToken) {
                    return;
                }
                published = null;
                session.closed = true;
                cancelDeadlines(session);
            }
            closeSessionResources(session);
            notifyPromotion(session);
            invokeFailure(session, "Radio stream stopped producing PCM data");
        }
    }

    private void cancelFirstDataDeadline(Session session) {
        ScheduledFuture<?> deadline = session.firstDataDeadline;
        if (deadline != null) {
            deadline.cancel(false);
            session.firstDataDeadline = null;
        }
    }

    private void cancelInactivityDeadline(Session session) {
        ScheduledFuture<?> deadline = session.inactivityDeadline;
        if (deadline != null) {
            deadline.cancel(false);
            session.inactivityDeadline = null;
        }
    }

    private void cancelDeadlines(Session session) {
        cancelFirstDataDeadline(session);
        cancelInactivityDeadline(session);
    }

    private void safeReady(Session session, long firstSequence, byte[] data) {
        synchronized (session.callbackGate) {
            synchronized (lock) {
                if (!isCurrentLocked(session) || session.closed) {
                    return;
                }
            }
            try {
                session.listener
                    .onReady(session.generation, session.station, firstSequence, Arrays.copyOf(data, data.length));
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "Radio ready listener failed", exception);
            }
        }
    }

    private void safeChunk(Session session, long sequence, byte[] data) {
        synchronized (session.callbackGate) {
            synchronized (lock) {
                if (!isCurrentLocked(session) || session.closed) {
                    return;
                }
            }
            try {
                session.listener.onChunk(session.generation, sequence, Arrays.copyOf(data, data.length));
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "Radio chunk listener failed", exception);
            }
        }
    }

    private void safeRejectedFailure(Session session, String message) {
        synchronized (session.callbackGate) {
            invokeFailure(session, message);
        }
    }

    private static void invokeFailure(Session session, String message) {
        try {
            session.listener.onFailure(session.generation, message);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Radio failure listener failed", exception);
        }
    }

    private static void notifyPromotion(Session session) {
        synchronized (session.promotionGate) {
            session.promotionGate.notifyAll();
        }
    }

    private static ScheduledExecutorService newDaemonDeadlineExecutor() {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "HorizonRadio-Relay-Deadline");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    interface SessionFactory {

        SessionHandle create(String streamUrl, RadioInputSession.RadioPcmListener listener);
    }

    interface SessionHandle {

        void start();

        void close();
    }

    interface TimeoutRaceHook {

        TimeoutRaceHook NONE = new TimeoutRaceHook() {

            @Override
            public void beforeTimeoutCheck(long generation) {}
        };

        void beforeTimeoutCheck(long generation);
    }

    public interface RadioStreamListener {

        void onReady(long generation, RadioStation station, long firstSequence, byte[] data);

        void onChunk(long generation, long sequence, byte[] data);

        void onFailure(long generation, String message);
    }

    private static final class Session {

        private final Object callbackGate = new Object();
        private final Object promotionGate = new Object();
        private final RadioStation station;
        private final long generation;
        private final RadioStreamListener listener;
        private long nextSequence;
        private boolean receivedFirstData;
        private byte[] pcmRemainder = new byte[0];
        private long inactivityDeadlineToken;
        private volatile boolean closed;
        private SessionHandle input;
        private ScheduledFuture<?> firstDataDeadline;
        private ScheduledFuture<?> inactivityDeadline;

        private Session(RadioStation station, long generation, RadioStreamListener listener) {
            this.station = station;
            this.generation = generation;
            this.listener = listener;
        }
    }

    private enum DispatchResult {
        READY,
        PUBLISHED,
        STOPPED
    }
}
