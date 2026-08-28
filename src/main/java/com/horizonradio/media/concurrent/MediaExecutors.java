package com.horizonradio.media.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Creates and shuts down the bounded executors used for client media work. */
public final class MediaExecutors {

    private static final Logger LOGGER = Logger.getLogger(MediaExecutors.class.getName());
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 2000L;

    private MediaExecutors() {}

    public static ExecutorService newDiscoveryExecutor() {
        return fixedBounded("HorizonRadio-Discovery-", 4, 64);
    }

    public static ExecutorService newDownloadExecutor() {
        return fixedBounded("HorizonRadio-Download-", 2, 16);
    }

    public static void shutdown(ExecutorService executor) {
        shutdown(executor, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    static void shutdown(ExecutorService executor, long timeout, TimeUnit timeoutUnit) {
        if (executor == null) {
            return;
        }
        if (timeout < 0L || timeoutUnit == null) {
            throw new IllegalArgumentException("shutdown timeout and unit are required");
        }
        long deadline = System.nanoTime() + timeoutUnit.toNanos(timeout);
        executor.shutdown();
        boolean interrupted = awaitTermination(executor, deadline);
        if (!executor.isTerminated()) {
            executor.shutdownNow();
            interrupted |= awaitTermination(executor, deadline);
        }
        if (interrupted) {
            Thread.currentThread()
                .interrupt();
        }
        if (!executor.isTerminated()) {
            LOGGER.warning("HorizonRadio: Media executor did not terminate within " + SHUTDOWN_TIMEOUT_MILLIS + " ms");
        }
    }

    private static boolean awaitTermination(ExecutorService executor, long deadline) {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static ExecutorService fixedBounded(String prefix, int workerCount, int queueSize) {
        return new ThreadPoolExecutor(
            workerCount,
            workerCount,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(queueSize),
            daemonFactory(prefix),
            new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        final AtomicInteger nextId = new AtomicInteger(1);
        return new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + nextId.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
