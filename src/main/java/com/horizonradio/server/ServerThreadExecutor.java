package com.horizonradio.server;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.server.MinecraftServer;

/**
 * Common/server scheduling boundary for work produced by asynchronous callbacks.
 *
 * <p>
 * Minecraft 1.7.10 exposes the IThreadListener scheduler on the client, not
 * on MinecraftServer. The dedicated-server-safe equivalent is a queue drained
 * from Forge's FML ServerTickEvent by ServerEvents.
 * </p>
 */
public final class ServerThreadExecutor {

    private static final ConcurrentHashMap<MinecraftServer, TaskQueue> TASKS = new ConcurrentHashMap<MinecraftServer, TaskQueue>();

    static final class TaskQueue {

        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();

        void add(Runnable task) {
            tasks.add(task);
        }

        void drain() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }

        void clear() {
            tasks.clear();
        }
    }

    private ServerThreadExecutor() {}

    public static void execute(MinecraftServer server, Runnable task) {
        if (server == null) {
            throw new IllegalArgumentException("server must not be null");
        }
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }

        TaskQueue queue = TASKS.get(server);
        if (queue == null) {
            TaskQueue newQueue = new TaskQueue();
            TaskQueue existingQueue = TASKS.putIfAbsent(server, newQueue);
            queue = existingQueue == null ? newQueue : existingQueue;
        }
        queue.add(task);
    }

    public static void drain(MinecraftServer server) {
        TaskQueue queue = TASKS.get(server);
        if (queue == null) {
            return;
        }
        queue.drain();
    }

    public static void clear(MinecraftServer server) {
        if (server != null) {
            TaskQueue queue = TASKS.remove(server);
            if (queue != null) {
                queue.clear();
            }
        }
    }
}
