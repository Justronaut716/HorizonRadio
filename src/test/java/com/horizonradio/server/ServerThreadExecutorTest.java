package com.horizonradio.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ServerThreadExecutorTest {

    @Test
    public void drainsTasksInSubmissionOrder() {
        final StringBuilder order = new StringBuilder();
        ServerThreadExecutor.TaskQueue queue = new ServerThreadExecutor.TaskQueue();

        queue.add(new Runnable() {

            @Override
            public void run() {
                order.append('a');
            }
        });
        queue.add(new Runnable() {

            @Override
            public void run() {
                order.append('b');
            }
        });

        queue.drain();

        assertEquals("ab", order.toString());
    }

    @Test
    public void clearRemovesTasksBeforeTheyRun() {
        final StringBuilder order = new StringBuilder();
        ServerThreadExecutor.TaskQueue queue = new ServerThreadExecutor.TaskQueue();
        queue.add(new Runnable() {

            @Override
            public void run() {
                order.append('x');
            }
        });

        queue.clear();
        queue.drain();

        assertEquals("", order.toString());
    }
}
