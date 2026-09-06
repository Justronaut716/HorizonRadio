package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class ClientProxyLifecycleTest {

    @Test
    public void shutdownMediaFinishesDownloadsBeforeCacheCleanupAndClosesEachPoolOnce() {
        List<String> events = new ArrayList<String>();
        CompletingExecutor discovery = new CompletingExecutor(events, "discovery");
        CompletingExecutor download = new CompletingExecutor(events, "download");
        download.execute(new Runnable() {

            @Override
            public void run() {
                events.add("published");
            }
        });

        ClientProxy.shutdownMedia(discovery, download, new Runnable() {

            @Override
            public void run() {
                events.add("cleanup");
            }
        });

        assertEquals(java.util.Arrays.asList("discovery", "download", "published", "cleanup"), events);
        assertEquals(1, discovery.shutdownCalls);
        assertEquals(1, download.shutdownCalls);
        assertEquals(0, discovery.shutdownNowCalls);
        assertEquals(0, download.shutdownNowCalls);
    }

    @Test
    public void shutdownMediaCleansCacheAfterBothShutdownAttemptsWhenDiscoveryShutdownFails() {
        List<String> events = new ArrayList<String>();
        CompletingExecutor download = new CompletingExecutor(events, "download");
        try {
            ClientProxy.shutdownMedia(new FailingExecutor(), download, new Runnable() {

                @Override
                public void run() {
                    events.add("cleanup");
                }
            });
            fail("expected discovery shutdown failure");
        } catch (IllegalStateException expected) {
            assertEquals(java.util.Arrays.asList("download", "cleanup"), events);
            assertEquals(1, download.shutdownCalls);
        }
    }

    private static class CompletingExecutor extends AbstractExecutorService {

        private final List<String> events;
        private final String name;
        private final List<Runnable> queued = new ArrayList<Runnable>();
        private boolean shutdown;
        private int shutdownCalls;
        private int shutdownNowCalls;

        private CompletingExecutor(List<String> events, String name) {
            this.events = events;
            this.name = name;
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
            shutdown = true;
            events.add(name);
            for (Runnable task : queued) {
                task.run();
            }
            queued.clear();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownNowCalls++;
            shutdown();
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
            queued.add(task);
        }
    }

    private static final class FailingExecutor extends CompletingExecutor {

        private FailingExecutor() {
            super(new ArrayList<String>(), "discovery");
        }

        @Override
        public void shutdown() {
            throw new IllegalStateException("discovery shutdown failed");
        }
    }
}
