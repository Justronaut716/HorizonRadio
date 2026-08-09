package com.horizonradio.core.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.horizonradio.core.model.RadioStation;

public class RadioPlaybackStateTest {

    private static final RadioStation STATION = new RadioStation(
        "station-a",
        "Station A",
        "https://stream.example/a",
        true,
        false);

    @Test
    public void promotesReadyCandidateWithGenerationAndStationIdentity() {
        RadioPlaybackState state = new RadioPlaybackState();

        long generation = state.beginCandidate();
        assertTrue(state.promoteCandidate(generation, STATION));

        assertEquals(RadioPlaybackState.Mode.RADIO, state.getMode());
        assertEquals(generation, state.getGeneration());
        assertEquals("station-a", state.getStationUuid());
        assertEquals("Station A", state.getStationName());
        assertEquals("Playing Station A", state.getStatus());
    }

    @Test
    public void failedCandidateDoesNotReplacePublishedRadioState() {
        RadioPlaybackState state = new RadioPlaybackState();
        long publishedGeneration = state.beginCandidate();
        state.promoteCandidate(publishedGeneration, STATION);

        long failedGeneration = state.beginCandidate();
        state.failCandidate(failedGeneration, "Stream unavailable");

        assertEquals(RadioPlaybackState.Mode.RADIO, state.getMode());
        assertEquals(publishedGeneration, state.getGeneration());
        assertEquals("station-a", state.getStationUuid());
        assertEquals("Playing Station A", state.getStatus());
    }

    @Test
    public void stopRetainsLastStationForResumeAndInvalidatesCandidateGeneration() {
        RadioPlaybackState state = new RadioPlaybackState();
        long generation = state.beginCandidate();
        state.promoteCandidate(generation, STATION);

        state.stop();

        assertEquals(RadioPlaybackState.Mode.IDLE, state.getMode());
        assertFalse(state.isRadioActive());
        assertEquals("station-a", state.getStationUuid());
        assertEquals("Station A", state.getStationName());
        assertEquals("", state.getStatus());
        assertFalse(state.isCandidateGeneration(generation));
    }

    @Test
    public void startingMusicKeepsLastStationAvailableForLaterResume() {
        RadioPlaybackState state = new RadioPlaybackState();
        long generation = state.beginCandidate();
        state.promoteCandidate(generation, STATION);

        state.startMusic();

        assertEquals(RadioPlaybackState.Mode.MUSIC, state.getMode());
        assertEquals("station-a", state.getStationUuid());
        assertEquals("Station A", state.getStationName());
    }

    @Test
    public void failedPublishedRadioRetainsInactiveStatusUntilExplicitReset() {
        RadioPlaybackState state = new RadioPlaybackState();
        long generation = state.beginCandidate();
        state.promoteCandidate(generation, STATION);

        state.stop("Radio stream stopped producing PCM data");

        assertEquals(RadioPlaybackState.Mode.IDLE, state.getMode());
        assertFalse(state.isRadioActive());
        assertEquals("station-a", state.getStationUuid());
        assertEquals("Station A", state.getStationName());
        assertEquals("Radio stream stopped producing PCM data", state.getStatus());

        state.stop();

        assertEquals("", state.getStatus());
    }
}
