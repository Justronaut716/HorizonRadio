package com.horizonradio.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioPlayerStateTest {

    @Test
    public void pauseAndResumeUseLoadedClipStateWithoutARealClip() {
        AudioPlayerState state = new AudioPlayerState();
        state.markClipLoaded("Track");

        assertTrue(state.resume(1250L));
        assertTrue(state.isPlaying());
        assertFalse(state.isAwaitingResume());
        assertEquals(1250L, state.getPositionMs());

        state.pause(400L);
        assertFalse(state.isPlaying());
        assertTrue(state.isAwaitingResume());
        assertEquals(400L, state.getPositionMs());
    }

    @Test
    public void stopClearsPlaybackState() {
        AudioPlayerState state = new AudioPlayerState();
        state.startPlayback("Track");
        state.stop();

        assertFalse(state.isPlaying());
        assertFalse(state.isAwaitingResume());
        assertEquals("", state.getCurrentTitle());
        assertEquals(0L, state.getPositionMs());
    }

    @Test
    public void volumeIsBounded() {
        AudioPlayerState state = new AudioPlayerState();

        state.setVolume(-1.0f);
        assertEquals(0.0f, state.getVolume(), 0.0001f);
        state.setVolume(2.0f);
        assertEquals(1.0f, state.getVolume(), 0.0001f);
        state.setVolume(Float.NaN);
        assertEquals(0.0f, state.getVolume(), 0.0001f);
    }

    @Test
    public void lateJoinSignalsReadyThroughInjectedFakeTransport() {
        RecordingReadyTransport transport = new RecordingReadyTransport();
        AudioPlayerState state = new AudioPlayerState(transport);

        state.prepareLateJoin("video-id", "Track");

        assertEquals("video-id", transport.videoId);
        assertTrue(state.isAwaitingResume());
        assertFalse(state.isPlaying());
        assertEquals("Track", state.getCurrentTitle());
    }

    @Test
    public void disconnectCleanupIsEquivalentToStop() {
        AudioPlayerState state = new AudioPlayerState();
        state.prepareLateJoin("video-id", "Track");
        state.stop();

        assertFalse(state.isPlaying());
        assertFalse(state.isAwaitingResume());
        assertEquals("", state.getCurrentTitle());
    }

    private static final class RecordingReadyTransport implements AudioPlayerState.ReadySender {

        private String videoId;

        @Override
        public void sendReady(String videoId) {
            this.videoId = videoId;
        }
    }
}
