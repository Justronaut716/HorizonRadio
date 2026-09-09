package com.horizonradio.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Minecraft-independent notification lifetime and confirmed-state change detection. */
final class NotificationCenter {

    static final long DISPLAY_MILLIS = 4000L;
    static final int MAX_PENDING = 5;
    private final Deque<Notice> pending = new ArrayDeque<Notice>();
    private final Set<String> awaitingQueueTitles = new LinkedHashSet<String>();
    private Notice active;
    private Notice preview;
    private Snapshot previous;
    private ClientUiSettings settings = new ClientUiSettings();

    synchronized void configure(ClientUiSettings settings) {
        this.settings = settings;
        if (!settings.allows("queue-add")) awaitingQueueTitles.clear();
        pending.removeIf(notice -> !settings.allows(notice.key));
        if (active != null && !settings.allows(active.key)) active = null;
    }

    private int announcedVolume;
    private long volumeChangedAt;

    static final class Notice {

        final String key;
        String title;
        String detail;
        final long createdAt;
        long startedAt;

        Notice(String key, String title, String detail, long now) {
            this.key = key;
            this.title = title;
            this.detail = detail;
            this.createdAt = now;
        }
    }

    static final class Snapshot {

        String trackKey = "";
        String trackId = "";
        String trackTitle = "";
        boolean radio;
        boolean paused;
        boolean loop;
        boolean shuffle;
        boolean audioEnabled = true;
        String mode = "";
        int volume;
        String error = "";
        final Map<String, String> queue = new LinkedHashMap<String, String>();
        final Map<String, String> favorites = new LinkedHashMap<String, String>();
    }

    synchronized void post(String key, String title, String detail, long now) {
        if (!settings.allows(key)) return;
        if (active != null && active.key.equals(key) && now - active.startedAt < 800L) {
            active.title = title;
            active.detail = detail;
            return;
        }
        pending.removeIf(notice -> notice.key.equals(key));
        if (key.equals("playback") || key.equals("error")) {
            active = new Notice(key, title, detail, now);
            active.startedAt = now;
            return;
        }
        while (pending.size() >= MAX_PENDING) pending.removeFirst();
        pending.addLast(new Notice(key, title, detail, now));
    }

    synchronized void preview(long now) {
        preview = new Notice("preview", "Notification preview", settings.position.label, now);
        preview.startedAt = now;
    }

    synchronized Notice current(long now) {
        if (preview != null) {
            if (now - preview.startedAt < DISPLAY_MILLIS) return preview;
            preview = null;
        }
        if (active != null && now - active.startedAt >= DISPLAY_MILLIS) active = null;
        while (active == null && !pending.isEmpty()) {
            Notice next = pending.removeFirst();
            if (now - next.createdAt > 15000L) continue;
            next.startedAt = now;
            active = next;
        }
        return active;
    }

    synchronized void clear() {
        preview = null;
        active = null;
        awaitingQueueTitles.clear();
        pending.clear();
        previous = null;
    }

    synchronized void observe(Snapshot state, long now) {
        if (previous == null) {
            previous = state;
            announcedVolume = state.volume;
            return;
        }
        boolean trackChanged = !state.trackKey.equals(previous.trackKey);
        if (trackChanged) {
            post(
                "playback",
                state.trackKey.isEmpty() ? "Playback stopped" : state.radio ? "Radio playing" : "Now playing",
                state.trackKey.isEmpty() ? previous.trackTitle : state.trackTitle,
                now);
        } else if (!state.trackTitle.equals(previous.trackTitle)) {
            if (active != null && active.key.equals("playback")) active.detail = state.trackTitle;
            for (Notice notice : pending) if (notice.key.equals("playback")) notice.detail = state.trackTitle;
        }
        List<String> added = difference(state.queue, previous.queue, "");
        List<String> removed = difference(previous.queue, state.queue, trackChanged ? previous.trackId : "");
        if (settings.allows("queue-add")) {
            for (String id : state.queue.keySet()) {
                if (!previous.queue.containsKey(id)) awaitingQueueTitles.add(id);
            }
        } else {
            awaitingQueueTitles.clear();
        }
        awaitingQueueTitles.retainAll(state.queue.keySet());
        List<String> resolvedTitles = new ArrayList<String>();
        for (Iterator<String> ids = awaitingQueueTitles.iterator(); ids.hasNext();) {
            String title = state.queue.get(ids.next());
            if (hasTitle(title)) {
                resolvedTitles.add(title);
                ids.remove();
            }
        }
        if (!resolvedTitles.isEmpty())
            post("queue-add", "Added to queue", describe(resolvedTitles, "entries added"), now);
        List<String> removedTitles = new ArrayList<String>(removed);
        removedTitles.removeIf(title -> !hasTitle(title));
        if (!removedTitles.isEmpty()) post(
            "queue-remove",
            state.queue.isEmpty() ? "Queue cleared" : "Removed from queue",
            describe(removedTitles, "entries removed"),
            now);
        if (added.isEmpty() && removed.isEmpty()
            && !trackChanged
            && !new ArrayList<String>(state.queue.keySet()).equals(new ArrayList<String>(previous.queue.keySet()))) {
            post("queue-order", "Queue reordered", "Playback order updated", now);
        }
        List<String> favoritesAdded = difference(state.favorites, previous.favorites, "");
        List<String> favoritesRemoved = difference(previous.favorites, state.favorites, "");
        if (!favoritesAdded.isEmpty())
            post("favorites-add", "Favorite added", describe(favoritesAdded, "favorites added"), now);
        if (!favoritesRemoved.isEmpty())
            post("favorites-remove", "Favorite removed", describe(favoritesRemoved, "favorites removed"), now);
        if (!trackChanged && state.paused != previous.paused && !state.trackKey.isEmpty()) {
            post("pause", state.paused ? "Playback paused" : "Playback resumed", state.trackTitle, now);
        }
        if (state.loop != previous.loop) post("loop", "Repeat", state.loop ? "Enabled" : "Disabled", now);
        if (state.shuffle != previous.shuffle) post("shuffle", "Shuffle", state.shuffle ? "Enabled" : "Disabled", now);
        if (!state.mode.equals(previous.mode)) post("mode", "Playback mode", state.mode, now);
        if (state.audioEnabled != previous.audioEnabled)
            post("audio", "YouTube audio", state.audioEnabled ? "Enabled" : "Disabled", now);
        if (state.volume != previous.volume) volumeChangedAt = now;
        if (state.volume != announcedVolume && now - volumeChangedAt >= 500L) {
            post("volume", "Volume", state.volume == 0 ? "Muted" : state.volume + "%", now);
            announcedVolume = state.volume;
        }
        if (!state.error.isEmpty() && !state.error.replaceAll("\\d+s", "Ns")
            .equals(previous.error.replaceAll("\\d+s", "Ns"))) {
            post("error", "HorizonRadio", state.error, now);
        }
        previous = state;
        current(now);
    }

    private static List<String> difference(Map<String, String> items, Map<String, String> before, String ignored) {
        List<String> result = new ArrayList<String>();
        for (Map.Entry<String, String> entry : items.entrySet()) {
            if (!before.containsKey(entry.getKey()) && !Objects.equals(entry.getKey(), ignored))
                result.add(entry.getValue());
        }
        return result;
    }

    private static boolean hasTitle(String title) {
        return title != null && !title.trim()
            .isEmpty();
    }

    private static String describe(List<String> names, String plural) {
        return names.size() == 1 ? names.get(0) : names.size() + " " + plural;
    }
}
