package com.horizonradio.core.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.network.packets.PlaylistDeltaPacket;

public class ClientQueueStateTest {

    @Test
    public void appliesOnlyContiguousDeltas() {
        ClientQueueState state = new ClientQueueState();
        state.applySnapshot(4L, false, false, entries("one"));

        assertTrue(state.applyDelta(PlaylistDeltaPacket.add(5L, entry("two"), 1)));
        assertFalse(state.applyDelta(PlaylistDeltaPacket.add(7L, entry("three"), 2)));
        assertTrue(state.isSnapshotRequired());
        assertEquals(Arrays.asList("one", "two"), sourceIds(state.snapshot()));
    }

    @Test
    public void snapshotClearsRevisionGapState() {
        ClientQueueState state = new ClientQueueState();
        state.applySnapshot(4L, false, false, entries("one"));
        assertFalse(state.applyDelta(PlaylistDeltaPacket.remove(6L, 0)));
        state.applySnapshot(6L, true, false, entries("replacement"));

        assertFalse(state.isSnapshotRequired());
        assertEquals(6L, state.getRevision());
        assertTrue(state.isShuffling());
    }

    @Test
    public void ignoresLaterContiguousDeltasUntilTheRequiredSnapshotArrives() {
        ClientQueueState state = new ClientQueueState();
        state.applySnapshot(4L, false, false, entries("one"));

        assertFalse(state.applyDelta(PlaylistDeltaPacket.add(6L, entry("missing"), 1)));
        assertFalse(state.applyDelta(PlaylistDeltaPacket.add(5L, entry("late"), 1)));
        assertEquals(Arrays.asList("one"), sourceIds(state.snapshot()));
    }

    @Test
    public void rejectsDuplicateAddForExistingSourceAndRequestsSnapshot() {
        ClientQueueState state = new ClientQueueState();
        state.applySnapshot(4L, false, false, entries("one"));

        assertFalse(state.applyDelta(PlaylistDeltaPacket.add(5L, entry("one"), 1)));
        assertTrue(state.isSnapshotRequired());
        assertEquals(Arrays.asList("one"), sourceIds(state.snapshot()));
        assertEquals(4L, state.getRevision());
    }

    @Test
    public void ignoresStaleSnapshotInsteadOfReplacingNewerQueue() {
        ClientQueueState state = new ClientQueueState();
        state.applySnapshot(4L, false, false, entries("one"));
        assertTrue(state.applyDelta(PlaylistDeltaPacket.add(5L, entry("two"), 1)));

        state.applySnapshot(4L, false, false, entries("one"));

        assertTrue(state.isSnapshotRequired());
        assertEquals(Arrays.asList("one", "two"), sourceIds(state.snapshot()));
        assertEquals(5L, state.getRevision());
    }

    @Test
    public void rejectsDuplicateEntriesFromSnapshotWithoutPublishingThem() {
        ClientQueueState state = new ClientQueueState();
        state.applySnapshot(4L, false, false, entries("one"));

        state.applySnapshot(5L, false, false, entries("one", "one"));

        assertTrue(state.isSnapshotRequired());
        assertEquals(Arrays.asList("one"), sourceIds(state.snapshot()));
        assertEquals(4L, state.getRevision());
    }

    private static PlaylistDeltaPacket.Entry entry(String sourceId) {
        return new PlaylistDeltaPacket.Entry(MediaSourceType.YOUTUBE, sourceId, "tester");
    }

    private static List<PlaylistEntry> entries(String... sourceIds) {
        List<PlaylistEntry> entries = new ArrayList<PlaylistEntry>();
        for (String sourceId : sourceIds) {
            entries.add(PlaylistEntry.youtube(sourceId, 1000L, "tester"));
        }
        return entries;
    }

    private static List<String> sourceIds(List<PlaylistEntry> entries) {
        List<String> sourceIds = new ArrayList<String>();
        for (PlaylistEntry entry : entries) {
            sourceIds.add(entry.getSourceId());
        }
        return sourceIds;
    }
}
