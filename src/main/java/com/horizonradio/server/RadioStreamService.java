package com.horizonradio.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.horizonradio.core.model.RadioStation;

/**
 * Relays one published FFmpeg PCM stream and, during handover, one candidate stream.
 */
public class RadioStreamService {

    private static final Logger LOGGER = Logger.getLogger(RadioStreamService.class.getName());
    private static final int PCM_CHUNK_BYTES = 30 * 1024;
    private static final int PCM_FRAME_BYTES = 4;
    private static final long FIRST_DATA_TIMEOUT_MILLIS = 15000L;
    private static final long INACTIVITY_TIMEOUT_MILLIS = 15000L;

    private final Object lock = new Object();
    private final ProcessFactory processFactory;
    private final ExecutorService relayExecutor;
    private final ScheduledExecutorService deadlineExecutor;
    private final TimeoutRaceHook timeoutRaceHook;
    private Session published;
    private Session candidate;
    private boolean shutDown;

    public RadioStreamService() {
        this(new ProcessFactory() {

            @Override
            public Process start(List<String> command) throws IOException {
                return new ProcessBuilder(command).start();
            }
        });
    }

    RadioStreamService(ProcessFactory processFactory) {
        this(
            processFactory,
            newDaemonExecutor("HorizonRadio-Relay"),
            newDaemonDeadlineExecutor(),
            FIRST_DATA_TIMEOUT_MILLIS,
            INACTIVITY_TIMEOUT_MILLIS,
            TimeoutRaceHook.NONE);
    }

    RadioStreamService(ProcessFactory processFactory, ExecutorService relayExecutor,
        ScheduledExecutorService deadlineExecutor, long firstDataTimeoutMillis) {
        this(
            processFactory,
            relayExecutor,
            deadlineExecutor,
            firstDataTimeoutMillis,
            INACTIVITY_TIMEOUT_MILLIS,
            TimeoutRaceHook.NONE);
    }

    RadioStreamService(ProcessFactory processFactory, ExecutorService relayExecutor,
        ScheduledExecutorService deadlineExecutor, long firstDataTimeoutMillis, TimeoutRaceHook timeoutRaceHook) {
        this(
            processFactory,
            relayExecutor,
            deadlineExecutor,
            firstDataTimeoutMillis,
            INACTIVITY_TIMEOUT_MILLIS,
            timeoutRaceHook);
    }

    RadioStreamService(ProcessFactory processFactory, ExecutorService relayExecutor,
        ScheduledExecutorService deadlineExecutor, long firstDataTimeoutMillis, long inactivityTimeoutMillis,
        TimeoutRaceHook timeoutRaceHook) {
        if (processFactory == null || relayExecutor == null
            || deadlineExecutor == null
            || firstDataTimeoutMillis <= 0L
            || inactivityTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("Radio relay dependencies must be provided");
        }
        this.processFactory = processFactory;
        this.relayExecutor = relayExecutor;
        this.deadlineExecutor = deadlineExecutor;
        this.firstDataTimeoutMillis = firstDataTimeoutMillis;
        this.inactivityTimeoutMillis = inactivityTimeoutMillis;
        this.timeoutRaceHook = timeoutRaceHook == null ? TimeoutRaceHook.NONE : timeoutRaceHook;
    }

    private final long firstDataTimeoutMillis;
    private final long inactivityTimeoutMillis;

    /**
     * Starts an unpublished candidate. The current published session stays active until promotion.
     */
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

        Session session = new Session(station, generation, listener);
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
            safeRejectedFailure(session, "Radio relay is shut down");
            return;
        }
        closeSession(replaced);
        try {
            relayExecutor.execute(new Runnable() {

                @Override
                public void run() {
                    relay(session);
                }
            });
        } catch (RuntimeException exception) {
            failSession(session, "Radio relay could not start: " + exception.getMessage());
        }
    }

    /**
     * Publishes a ready candidate and closes the previously published source.
     */
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

    /** Stops active relays and releases the daemon executors. */
    public void shutdown() {
        synchronized (lock) {
            shutDown = true;
        }
        stopAll();
        relayExecutor.shutdownNow();
        deadlineExecutor.shutdownNow();
    }

    /** Builds the FFmpeg invocation that writes 44.1 kHz stereo signed little-endian PCM to stdout. */
    public static List<String> buildFfmpegCommand(String streamUrl) {
        if (streamUrl == null || streamUrl.trim()
            .length() == 0) {
            throw new IllegalArgumentException("streamUrl must not be empty");
        }
        return new ArrayList<String>(
            Arrays.asList(
                "ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "warning",
                "-i",
                streamUrl,
                "-vn",
                "-ac",
                "2",
                "-ar",
                "44100",
                "-acodec",
                "pcm_s16le",
                "-f",
                "s16le",
                "pipe:1"));
    }

    private void relay(Session session) {
        Process process = null;
        try {
            if (!isCurrent(session)) {
                return;
            }
            process = processFactory.start(buildFfmpegCommand(session.station.getStreamUrl()));
            if (process == null) {
                failSession(session, "FFmpeg did not start a process");
                return;
            }
            synchronized (lock) {
                if (!isCurrentLocked(session)) {
                    closeProcess(process);
                    return;
                }
                session.process = process;
                session.input = process.getInputStream();
                session.error = process.getErrorStream();
                session.output = process.getOutputStream();
            }
            drainError(session);
            readPcm(session);
        } catch (IOException exception) {
            failSession(session, "FFmpeg relay failed: " + exception.getMessage());
        } catch (RuntimeException exception) {
            failSession(session, "FFmpeg relay failed: " + exception.getMessage());
        } finally {
            if (process != null && session.process != process) {
                closeProcess(process);
            }
        }
    }

    private void drainError(final Session session) {
        relayExecutor.execute(new Runnable() {

            @Override
            public void run() {
                InputStream error = session.error;
                if (error == null) {
                    return;
                }
                byte[] buffer = new byte[1024];
                try {
                    while (!session.closed && error.read(buffer) != -1) {
                        // Draining prevents FFmpeg stderr backpressure.
                    }
                } catch (IOException ignored) {
                    // Session cleanup closes this stream to unblock the drainer.
                }
            }
        });
    }

    private void readPcm(Session session) throws IOException {
        byte[] readBuffer = new byte[PCM_CHUNK_BYTES];
        int count;
        while (!session.closed && (count = session.input.read(readBuffer, 0, readBuffer.length)) != -1) {
            if (count == 0) {
                continue;
            }
            byte[] chunk = completePcmFrames(session, readBuffer, count);
            if (chunk.length == 0) {
                continue;
            }
            DispatchResult result = dispatchChunk(session, chunk);
            if (result == DispatchResult.STOPPED) {
                return;
            }
            if (result == DispatchResult.READY && !awaitPublication(session)) {
                return;
            }
        }
        if (!session.closed) {
            failSession(
                session,
                session.receivedFirstData ? "FFmpeg stream ended"
                    : "FFmpeg stream ended before first PCM data (exit " + exitCode(session.process) + ")");
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
            invokeFailure(session, "FFmpeg did not provide PCM data within 15 seconds");
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

    private boolean isCurrent(Session session) {
        synchronized (lock) {
            return isCurrentLocked(session);
        }
    }

    private boolean isCurrentLocked(Session session) {
        return published == session || candidate == session;
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
        closeQuietly(session.input);
        closeQuietly(session.error);
        closeQuietly(session.output);
        closeProcess(session.process);
    }

    private static void closeProcess(Process process) {
        if (process == null) {
            return;
        }
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        boolean interrupted = false;
        process.destroy();
        try {
            if (!process.waitFor(1L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(1L, TimeUnit.SECONDS)) {
                    LOGGER.warning("FFmpeg process remained alive after forced termination");
                }
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            process.destroyForcibly();
            try {
                if (!process.waitFor(1L, TimeUnit.SECONDS)) {
                    LOGGER.warning("FFmpeg process remained alive after forced termination");
                }
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread()
                    .interrupt();
            }
        }
    }

    private static void closeQuietly(Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing process streams is best effort.
        }
    }

    private static int exitCode(Process process) {
        if (process == null) {
            return -1;
        }
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException ignored) {
            return -1;
        }
    }

    private static byte[] completePcmFrames(Session session, byte[] data, int count) {
        int previousBytes = session.pcmRemainder.length;
        byte[] combined = new byte[previousBytes + count];
        System.arraycopy(session.pcmRemainder, 0, combined, 0, previousBytes);
        System.arraycopy(data, 0, combined, previousBytes, count);
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
            if (!isCurrent(session) || session.closed) {
                return;
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
            if (!isCurrent(session) || session.closed) {
                return;
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

    private void invokeFailure(Session session, String message) {
        try {
            session.listener.onFailure(session.generation, message);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Radio failure listener failed", exception);
        }
    }

    private static void notifyPromotion(Session session) {
        if (session == null) {
            return;
        }
        synchronized (session.promotionGate) {
            session.promotionGate.notifyAll();
        }
    }

    private static ExecutorService newDaemonExecutor(final String name) {
        return Executors.newCachedThreadPool(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        });
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

    interface ProcessFactory {

        Process start(List<String> command) throws IOException;
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
        private Process process;
        private InputStream input;
        private InputStream error;
        private OutputStream output;
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
