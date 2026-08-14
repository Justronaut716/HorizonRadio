package com.horizonradio.core.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;

public class ClientLocalPlaylistStateTest {

    @Test
    public void localStateOwnsItsOwnEntriesAndRejectsDuplicateSources() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(3);
        PlaylistEntry first = PlaylistEntry.youtube("one", 60_000L, "Private");

        assertTrue(state.add(first));
        assertFalse(state.add(PlaylistEntry.youtube("one", 60_000L, "Private")));
        assertEquals(Collections.singletonList(first), state.snapshot());
        List<PlaylistEntry> copy = state.snapshot();
        copy.clear();
        assertEquals(1, state.size());
    }

    @Test
    public void validationRejectsNullZeroDurationAndEntriesPastTheLimit() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(1);

        assertFalse(state.add(null));
        assertFalse(state.add(PlaylistEntry.youtube("zero", 0L, "Private")));
        assertTrue(state.add(PlaylistEntry.youtube("one", 1L, "Private")));
        assertFalse(state.add(PlaylistEntry.radio("station", "Private")));
    }

    @Test
    public void immediatePlaybackAndNextRemovalKeepCurrentIndexConsistent() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(5);
        PlaylistEntry first = PlaylistEntry.youtube("one", 60_000L, "Private");
        PlaylistEntry second = PlaylistEntry.youtube("two", 60_000L, "Private");
        assertTrue(state.add(first));
        assertTrue(state.add(second));

        assertEquals(first, state.prepareImmediatePlayback(first));
        state.startFiniteTrack(0, 1_000L);
        assertEquals(first, state.getCurrentEntry());
        assertEquals(5_000L, state.pausePlayback(5_000L, 10_000L));
        assertEquals(7_000L, state.seek(7_000L, 11_000L));
        assertTrue(state.isPaused());

        state.removeCurrent();

        assertEquals(-1, state.getCurrentIndex());
        assertEquals(Collections.singletonList(second), state.snapshot());
    }

    @Test
    public void replayingTheActiveEntryPreservesFollowingQueuedEntries() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(3);
        PlaylistEntry current = PlaylistEntry.youtube("current", 1_000L, "Private");
        PlaylistEntry following = PlaylistEntry.youtube("following", 1_000L, "Private");
        state.add(current);
        state.add(following);
        state.startFiniteTrack(0, 100L);

        assertEquals(current, state.prepareImmediatePlayback(current));

        assertEquals(Arrays.asList(current, following), state.snapshot());
        assertEquals(-1, state.getCurrentIndex());
        assertFalse(state.isPlaying());
        assertNull(state.takeLastTrack());
    }

    @Test
    public void zeroCapacitySafelyRejectsImmediateFiniteAndRadioSelection() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(0);

        assertNull(state.prepareImmediatePlayback(PlaylistEntry.youtube("finite", 1_000L, "Private")));
        assertFalse(state.selectRadioAtFront(PlaylistEntry.radio("station", "Private")));
        assertTrue(state.snapshot().isEmpty());
        assertEquals(-1, state.getCurrentIndex());
    }

    @Test
    public void queuedMovesCannotMoveTheActiveEntry() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(5);
        PlaylistEntry current = PlaylistEntry.youtube("current", 1_000L, "Private");
        PlaylistEntry second = PlaylistEntry.youtube("two", 1_000L, "Private");
        PlaylistEntry third = PlaylistEntry.youtube("three", 1_000L, "Private");
        state.add(current);
        state.add(second);
        state.add(third);
        state.startFiniteTrack(0, 10L);

        assertFalse(state.moveQueued(0, 2));
        assertFalse(state.moveQueued(2, 0));
        assertTrue(state.moveQueued(2, 1));
        assertEquals(Arrays.asList(current, third, second), state.snapshot());
        assertEquals(current, state.getCurrentEntry());
    }

    @Test
    public void finiteSeekClampsToDurationMinusOneAndRadioHasNoFiniteDuration() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(3);
        PlaylistEntry finite = PlaylistEntry.youtube("finite", 10L, "Private");
        PlaylistEntry radio = PlaylistEntry.radio("station", "Private");
        state.add(finite);
        state.add(radio);
        state.startFiniteTrack(0, 100L);

        assertEquals(9L, state.seek(100L, 200L));
        assertEquals(191L, state.getPlaybackStartTime());
        state.startRadioTrack(1);

        assertEquals(MediaSourceType.RADIO, state.getCurrentSourceType());
        assertEquals(-1L, state.currentPositionMs(250L));
        assertEquals(-1L, state.pausePlayback(0L, 250L));
        assertEquals(-1L, state.seek(0L, 250L));
    }

    @Test
    public void queuedShuffleNeverMovesTheActiveEntry() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(5);
        PlaylistEntry current = PlaylistEntry.youtube("current", 1_000L, "Private");
        state.add(current);
        state.add(PlaylistEntry.youtube("one", 1_000L, "Private"));
        state.add(PlaylistEntry.youtube("two", 1_000L, "Private"));
        state.add(PlaylistEntry.youtube("three", 1_000L, "Private"));
        state.startFiniteTrack(0, 1L);

        state.shuffleQueued(new Random(7L));

        assertEquals(current, state.get(0));
        assertEquals(current, state.getCurrentEntry());
        assertEquals(0, state.getCurrentIndex());
    }

    @Test
    public void radioSelectionPreservesQueuedFiniteTracksAndPreviousBookkeeping() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(4);
        PlaylistEntry finite = PlaylistEntry.youtube("current", 1_000L, "Private");
        PlaylistEntry next = PlaylistEntry.youtube("next", 1_000L, "Private");
        PlaylistEntry radio = PlaylistEntry.radio("station", "Private");
        state.add(finite);
        state.add(next);
        state.startFiniteTrack(0, 100L);

        assertTrue(state.selectRadioAtFront(radio));
        assertEquals(Arrays.asList(radio, next), state.snapshot());
        assertEquals(MediaSourceType.RADIO, state.getCurrentSourceType());
        assertEquals(finite, state.takeLastTrack());
        assertNull(state.takeLastTrack());
        assertTrue(state.pauseRadioPlayback());
        assertFalse(state.isPlaying());
    }

    @Test
    public void previousRestartedAndClearResetOnlyLocalPlaybackState() {
        ClientLocalPlaylistState state = new ClientLocalPlaylistState(2);
        PlaylistEntry first = PlaylistEntry.youtube("one", 1_000L, "Private");
        state.add(first);
        state.startFiniteTrack(0, 0L);
        state.markPreviousRestarted();
        assertTrue(state.wasPreviousRestarted());
        assertTrue(state.toggleLooping());
        assertTrue(state.toggleShuffling());

        state.clear();

        assertTrue(state.snapshot().isEmpty());
        assertFalse(state.isPlaying());
        assertFalse(state.wasPreviousRestarted());
        assertFalse(state.isLooping());
        assertFalse(state.isShuffling());
        assertEquals(-1, state.getCurrentIndex());
    }
}
