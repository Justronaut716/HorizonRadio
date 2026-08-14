package com.horizonradio.core.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;

public class PlaylistStateTest {

    @Test
    public void constructsFiniteAndRadioEntriesWithSourceMetadata() {
        PlaylistEntry finite = PlaylistEntry.youtube("video", 12_000L, "Alice");
        PlaylistEntry radio = PlaylistEntry.radio("station", "Bob");

        assertEquals(MediaSourceType.YOUTUBE, finite.getSourceType());
        assertEquals("video", finite.getSourceId());
        assertEquals(12_000L, finite.getDurationMs());
        assertTrue(finite.isFinite());
        assertEquals(MediaSourceType.RADIO, radio.getSourceType());
        assertEquals("station", radio.getSourceId());
        assertEquals(0L, radio.getDurationMs());
        assertTrue(radio.isRadio());
    }

    @Test
    public void rejectsZeroDurationFiniteEntriesInServerQueue() {
        PlaylistState state = new PlaylistState(5);

        assertFalse(state.add(PlaylistEntry.youtube("unresolved", 0L, "Alice")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveFiniteStartDuration() {
        PlaylistState state = new PlaylistState(5);
        state.add(PlaylistEntry.youtube("video", 1_000L, "Alice"));

        state.startFiniteTrack(0, "video", 0L, 0L);
    }

    @Test
    public void startsRadioWithoutFiniteTimingAndRejectsPauseOrSeek() {
        PlaylistState state = new PlaylistState(5);
        state.add(PlaylistEntry.youtube("video", 12_000L, "Alice"));
        state.add(PlaylistEntry.radio("station", "Bob"));

        state.startRadioTrack(1, "station");

        assertEquals(MediaSourceType.RADIO, state.getCurrentSourceType());
        assertEquals("station", state.getCurrentSourceId());
        assertEquals(0L, state.getPlaybackStartTime());
        assertEquals(0L, state.getCurrentTrackDurationMs());
        assertEquals(-1L, state.pausePlayback(0L, 0L));
        assertEquals(-1L, state.seek(0L, 0L));
    }

    @Test
    public void radioSelectionStoresInterruptedFiniteTrackAndKeepsSuccessors() {
        PlaylistState state = new PlaylistState(5);
        state.add(PlaylistEntry.youtube("current", 60_000L, "Alice"));
        state.add(PlaylistEntry.youtube("next", 60_000L, "Bob"));
        state.startFiniteTrack(0, "current", 60_000L, 0L);

        assertTrue(state.selectRadioAtFront(PlaylistEntry.radio("station", "Carol")));

        assertEquals(
            "station",
            state.get(0)
                .getSourceId());
        assertEquals(
            "next",
            state.get(1)
                .getSourceId());
        assertEquals(
            "current",
            state.peekLastTrack()
                .getSourceId());
        assertEquals(MediaSourceType.RADIO, state.getCurrentSourceType());
    }

    @Test
    public void pausingRadioKeepsQueueAndRadioSelection() {
        PlaylistState state = new PlaylistState(5);
        state.selectRadioAtFront(PlaylistEntry.radio("station", "Carol"));

        assertTrue(state.pauseRadioPlayback());

        assertFalse(state.isPlaying());
        assertEquals(0, state.getCurrentIndex());
        assertEquals(MediaSourceType.RADIO, state.getCurrentSourceType());
        assertEquals(
            "station",
            state.get(0)
                .getSourceId());
    }

    @Test
    public void selectingRadioAtFrontAtomicallyReplacesTheActiveStation() {
        PlaylistState state = new PlaylistState(5);
        state.selectRadioAtFront(PlaylistEntry.radio("station-one", "Alice"));
        long before = state.getQueueRevision();

        assertTrue(state.selectRadioAtFront(PlaylistEntry.radio("station-two", "Bob")));

        assertEquals(before + 1L, state.getQueueRevision());
        assertEquals(1, state.size());
        assertEquals(
            "station-two",
            state.get(0)
                .getSourceId());
        assertEquals(0, state.getCurrentIndex());
        assertEquals(MediaSourceType.RADIO, state.getCurrentSourceType());
    }

    @Test
    public void immediateFinitePlaybackReplacesTheCurrentRadioInOneQueueMutation() {
        PlaylistState state = new PlaylistState(5);
        state.selectRadioAtFront(PlaylistEntry.radio("station", "Alice"));
        long before = state.getQueueRevision();

        PlaylistEntry selected = state.prepareImmediatePlayback(PlaylistEntry.youtube("video", 1_000L, "Bob"));

        assertEquals(before + 1L, state.getQueueRevision());
        assertEquals(selected, state.get(0));
        assertEquals(-1, state.getCurrentIndex());
        assertFalse(state.isPlaying());
        assertEquals(
            "station",
            state.takeLastTrack()
                .getSourceId());
    }

    @Test
    public void immediateFinitePlaybackEvictsFrontWhenFullWithoutActiveTrack() {
        PlaylistState state = new PlaylistState(2);
        state.add(PlaylistEntry.youtube("oldest", 1_000L, "Alice"));
        state.add(PlaylistEntry.youtube("queued", 1_000L, "Bob"));

        PlaylistEntry selected = state.prepareImmediatePlayback(PlaylistEntry.youtube("direct", 1_000L, "Carol"));

        assertEquals("direct", selected.getSourceId());
        assertEquals(2, state.size());
        assertEquals(
            "direct",
            state.get(0)
                .getSourceId());
        assertEquals(
            "queued",
            state.get(1)
                .getSourceId());
    }

    @Test
    public void selectingRadioEvictsFrontWhenFull() {
        PlaylistState state = new PlaylistState(2);
        state.add(PlaylistEntry.youtube("oldest", 1_000L, "Alice"));
        state.add(PlaylistEntry.youtube("queued", 1_000L, "Bob"));

        PlaylistEntry station = PlaylistEntry.radio("station", "Carol");
        assertFalse(state.canSelectRadioAtFront(station));
        assertTrue(state.selectRadioAtFront(station));

        assertEquals(2, state.size());
        assertEquals(
            MediaSourceType.RADIO,
            state.get(0)
                .getSourceType());
        assertEquals(
            "station",
            state.get(0)
                .getSourceId());
        assertEquals(
            "queued",
            state.get(1)
                .getSourceId());
        assertEquals(0, state.getCurrentIndex());
    }

    @Test
    public void ordinaryAddStillRejectsAFullQueue() {
        PlaylistState state = new PlaylistState(1);
        state.add(PlaylistEntry.youtube("existing", 1_000L, "Alice"));

        assertFalse(state.add(PlaylistEntry.youtube("rejected", 1_000L, "Bob")));
        assertEquals(1, state.size());
        assertEquals(
            "existing",
            state.get(0)
                .getSourceId());
    }

    @Test
    public void queuedEntriesCanBeMovedWithoutMovingTheCurrentTrack() {
        PlaylistState state = new PlaylistState(5);
        state.add(PlaylistEntry.youtube("current", 1_000L, "Alice"));
        state.add(PlaylistEntry.youtube("two", 1_000L, "Bob"));
        state.add(PlaylistEntry.youtube("three", 1_000L, "Carol"));
        state.startFiniteTrack(0, "current", 1_000L, 42L);

        assertTrue(state.moveQueued(2, 1));
        assertEquals(
            Arrays.asList(
                PlaylistEntry.youtube("current", 1_000L, "Alice"),
                PlaylistEntry.youtube("three", 1_000L, "Carol"),
                PlaylistEntry.youtube("two", 1_000L, "Bob")),
            state.snapshot());
        assertEquals(0, state.getCurrentIndex());
        assertFalse(state.moveQueued(0, 2));
    }

    @Test
    public void clearResetsPlaybackAndQueueFlags() {
        PlaylistState state = new PlaylistState(5);
        state.add(PlaylistEntry.youtube("video", 1_000L, "Alice"));
        state.startFiniteTrack(0, "video", 1_000L, 42L);
        state.toggleLooping();
        state.toggleShuffling();

        state.clear();

        assertTrue(
            state.snapshot()
                .isEmpty());
        assertFalse(state.isPlaying());
        assertFalse(state.isLooping());
        assertFalse(state.isShuffling());
        assertEquals(-1, state.getCurrentIndex());
        assertNull(state.getCurrentSourceId());
    }
}
