package com.horizonradio.media.concurrent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class MediaExecutorsTest {

    @Test
    public void discoveryExecutorUsesBoundedDaemonWorkersAndRejectsOverflow() throws Exception {
        ExecutorService discovery = MediaExecutors.newDiscoveryExecutor();
        try {
            ThreadPoolExecutor pool = (ThreadPoolExecutor) discovery;
            assertEquals(4, pool.getCorePoolSize());
            assertEquals(
                64,
                pool.getQueue()
                    .remainingCapacity());

            assertSaturatesAndUsesDaemonThreads(discovery, 4, 64, "HorizonRadio-Discovery-");
        } finally {
            discovery.shutdownNow();
        }
    }

    @Test
    public void downloadExecutorUsesBoundedDaemonWorkersAndRejectsOverflow() throws Exception {
        ExecutorService download = MediaExecutors.newDownloadExecutor();
        try {
            ThreadPoolExecutor pool = (ThreadPoolExecutor) download;
            assertEquals(2, pool.getCorePoolSize());
            assertEquals(
                16,
                pool.getQueue()
                    .remainingCapacity());

            assertSaturatesAndUsesDaemonThreads(download, 2, 16, "HorizonRadio-Download-");
        } finally {
            download.shutdownNow();
        }
    }

    @Test
    public void shutdownInterruptsRunningMediaWorkAndTerminatesThePool() throws Exception {
        ExecutorService discovery = MediaExecutors.newDiscoveryExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        discovery.execute(new Runnable() {

            @Override
            public void run() {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread()
                        .interrupt();
                }
            }
        });
        assertTrue("worker did not start", started.await(2, TimeUnit.SECONDS));

        MediaExecutors.shutdown(discovery);

        assertTrue("shutdown must interrupt running media work", interrupted.await(1, TimeUnit.SECONDS));
        assertTrue("shutdown must terminate the media pool", discovery.isTerminated());
    }

    @Test
    public void shutdownLetsCooperativeWorkFinishBeforeForcingCancellation() throws Exception {
        final ExecutorService discovery = MediaExecutors.newDiscoveryExecutor();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        discovery.execute(new Runnable() {

            @Override
            public void run() {
                started.countDown();
                while (true) {
                    try {
                        finish.await();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted.set(true);
                    }
                }
                completed.countDown();
            }
        });
        assertTrue("worker did not start", started.await(2, TimeUnit.SECONDS));

        Thread shutdown = new Thread(new Runnable() {

            @Override
            public void run() {
                MediaExecutors.shutdown(discovery);
            }
        }, "media-executor-graceful-shutdown-test");
        shutdown.start();
        assertTrue("executor was not closed for new work", awaitShutdown(discovery));
        finish.countDown();
        shutdown.join(3000L);

        assertFalse("cooperative work should not be interrupted", interrupted.get());
        assertTrue("cooperative work should complete", completed.await(1, TimeUnit.SECONDS));
        assertTrue("executor should terminate after cooperative completion", discovery.isTerminated());
    }

    @Test
    public void zeroTimeoutShutdownForcesCancellationOfNonCooperativeWork() throws Exception {
        ExecutorService discovery = MediaExecutors.newDiscoveryExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        discovery.execute(nonCooperativeTask(started, release, interrupted));
        assertTrue("worker did not start", started.await(2, TimeUnit.SECONDS));

        MediaExecutors.shutdown(discovery, 0L, TimeUnit.NANOSECONDS);

        assertTrue("forced shutdown must interrupt non-cooperative work", interrupted.await(1, TimeUnit.SECONDS));
        assertTrue("forced shutdown must reject new work", discovery.isShutdown());
        assertFalse("non-cooperative work controls termination", discovery.isTerminated());
        release.countDown();
        assertTrue("released worker should terminate", discovery.awaitTermination(1, TimeUnit.SECONDS));
    }

    private static void assertSaturatesAndUsesDaemonThreads(ExecutorService executor, int workers, int queueSize,
        String prefix) throws Exception {
        CountDownLatch started = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<Thread>();
        for (int index = 0; index < workers; index++) {
            executor.execute(blockingTask(started, release, threads));
        }
        assertTrue("workers did not start", started.await(2, TimeUnit.SECONDS));
        for (int index = 0; index < queueSize; index++) {
            executor.execute(blockingTask(new CountDownLatch(0), release, threads));
        }

        try {
            try {
                executor.execute(new Runnable() {

                    @Override
                    public void run() {}
                });
                fail("expected saturated media executor to reject the task");
            } catch (RejectedExecutionException expected) {
                // Expected: media work must have a bounded queue.
            }
            synchronized (threads) {
                for (Thread thread : threads) {
                    assertTrue(thread.isDaemon());
                    assertTrue(
                        thread.getName()
                            .startsWith(prefix));
                }
            }
        } finally {
            release.countDown();
        }
    }

    private static Runnable blockingTask(final CountDownLatch started, final CountDownLatch release,
        final List<Thread> threads) {
        return new Runnable() {

            @Override
            public void run() {
                synchronized (threads) {
                    threads.add(Thread.currentThread());
                }
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                }
            }
        };
    }

    private static Runnable nonCooperativeTask(final CountDownLatch started, final CountDownLatch release,
        final CountDownLatch interrupted) {
        return new Runnable() {

            @Override
            public void run() {
                started.countDown();
                while (true) {
                    try {
                        release.await();
                        return;
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                    }
                }
            }
        };
    }

    private static boolean awaitShutdown(ExecutorService executor) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (executor.isShutdown()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }
}
