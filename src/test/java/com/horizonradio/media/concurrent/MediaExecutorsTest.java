package com.horizonradio.media.concurrent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class MediaExecutorsTest {

    @Test
    public void discoveryExecutorUsesBoundedDaemonWorkersAndRejectsOverflow() throws Exception {
        ExecutorService discovery = MediaExecutors.newDiscoveryExecutor();
        try {
            ThreadPoolExecutor pool = (ThreadPoolExecutor) discovery;
            assertEquals(4, pool.getCorePoolSize());
            assertEquals(64, pool.getQueue().remainingCapacity());

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
            assertEquals(16, pool.getQueue().remainingCapacity());

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
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertTrue("worker did not start", started.await(2, TimeUnit.SECONDS));

        MediaExecutors.shutdown(discovery);

        assertTrue("shutdown must interrupt running media work", interrupted.await(1, TimeUnit.SECONDS));
        assertTrue("shutdown must terminate the media pool", discovery.isTerminated());
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
            executor.execute(new Runnable() {

                @Override
                public void run() {}
            });
            fail("expected saturated media executor to reject the task");
        } catch (RejectedExecutionException expected) {
            // Expected: media work must have a bounded queue.
        } finally {
            release.countDown();
        }

        for (Thread thread : threads) {
            assertTrue(thread.isDaemon());
            assertTrue(thread.getName().startsWith(prefix));
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
                    Thread.currentThread().interrupt();
                }
            }
        };
    }
}
