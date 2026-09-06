package com.horizonradio.test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Executes test-only tasks synchronously without creating background workers. */
public final class DirectExecutorService extends AbstractExecutorService {

    private boolean shutdown;

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
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
        if (shutdown) {
            throw new RejectedExecutionException("executor is shut down");
        }
        task.run();
    }
}
