package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.TrackSyncPacket;

public class HorizonRadioClientModeTest {

    private RecordingTransport transport;

    @Before
    public void setUp() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);
        transport = new RecordingTransport();
        HorizonRadioClient.setTransport(transport);
    }

    @After
    public void tearDown() {
        HorizonRadioClient.setTransport(new HorizonRadioClient.NoopClientTransport());
        HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);
        HorizonRadioClient.clearCache();
    }

    @Test
    public void privateAddUsesLocalQueueWithoutTransport() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);

        HorizonRadioClient.sendAdd("private-song", 120_000L);

        assertEquals(0, transport.addCount);
        assertEquals("private-song", HorizonRadioClient.getCachedPlaylist().get(0).sourceId);
    }

    @Test
    public void privateModeIgnoresServerPlaylistAndTrackPackets() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);

        HorizonRadioClient.handlePlaylistSnapshot(snapshotPacket(4L, "server-song"));
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(4L, "server-song", 0L, 0L, false));

        assertTrue(HorizonRadioClient.getCachedPlaylist().isEmpty());
        assertNull(HorizonRadioClient.getCachedNowPlaying());
    }

    @Test
    public void switchingBackToServerClearsPrivateViewAndRequestsSnapshotAndClock() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendAdd("private-song", 120_000L);

        HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);

        assertTrue(HorizonRadioClient.getCachedPlaylist().isEmpty());
        assertEquals(1, transport.playlistResyncCount);
        assertEquals(1, transport.clockSyncCount);
    }

    @Test
    public void serverActionsContinueToDelegateToTheTransport() {
        HorizonRadioClient.sendAdd("server-song", 120_000L);
        HorizonRadioClient.sendPlayNow("server-now", 120_000L);
        HorizonRadioClient.sendRemove("server-song");
        HorizonRadioClient.sendClearPlaylist();
        HorizonRadioClient.sendReorder(0, 1);
        HorizonRadioClient.sendSeek(0.5f);
        HorizonRadioClient.sendTogglePlayback();
        HorizonRadioClient.sendSkipTrack();
        HorizonRadioClient.sendPreviousTrack();
        HorizonRadioClient.sendToggleLoop();
        HorizonRadioClient.sendToggleShuffle();
        HorizonRadioClient.sendSelectRadio("station-id");
        HorizonRadioClient.sendStopRadio();
        HorizonRadioClient.sendClockSync();

        assertEquals(1, transport.addCount);
        assertEquals(1, transport.playNowCount);
        assertEquals(1, transport.removeCount);
        assertEquals(1, transport.clearCount);
        assertEquals(1, transport.reorderCount);
        assertEquals(1, transport.seekCount);
        assertEquals(1, transport.playbackCount);
        assertEquals(1, transport.skipCount);
        assertEquals(1, transport.previousCount);
        assertEquals(1, transport.loopCount);
        assertEquals(1, transport.shuffleCount);
        assertEquals(1, transport.radioCount);
        assertEquals(1, transport.stopRadioCount);
        assertEquals(1, transport.clockSyncCount);
        assertEquals(14, transport.totalPacketCount());
    }

    @Test
    public void groupModeRequestLeavesTheSelectedModeUnchanged() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);

        HorizonRadioClient.setPlaybackMode(PlaybackMode.GROUP);

        assertSame(PlaybackMode.PRIVATE, HorizonRadioClient.getPlaybackMode());
    }

    @Test
    public void privateModeDoesNotUseTransportForControlActionsOrClockSync() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendAdd("private-song", 120_000L);

        HorizonRadioClient.sendRemove("private-song");
        HorizonRadioClient.sendClearPlaylist();
        HorizonRadioClient.sendReorder(0, 0);
        HorizonRadioClient.sendSeek(0.5f);
        HorizonRadioClient.sendTogglePlayback();
        HorizonRadioClient.sendSkipTrack();
        HorizonRadioClient.sendPreviousTrack();
        HorizonRadioClient.sendToggleLoop();
        HorizonRadioClient.sendToggleShuffle();
        HorizonRadioClient.sendSelectRadio("station-id");
        HorizonRadioClient.sendStopRadio();
        HorizonRadioClient.sendClockSync();

        assertEquals(0, transport.totalPacketCount());
        assertTrue(HorizonRadioClient.getCachedPlaylist().isEmpty());
    }

    @Test
    public void privateAudioCompletionGuardRequiresMatchingPrivateGenerationAndVideo() {
        assertTrue(
            HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
                PlaybackMode.PRIVATE,
                7L,
                7L,
                "private-song",
                "private-song"));
        assertTrue(
            !HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
                PlaybackMode.SERVER,
                7L,
                7L,
                "private-song",
                "private-song"));
        assertTrue(
            !HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
                PlaybackMode.PRIVATE,
                8L,
                7L,
                "private-song",
                "private-song"));
        assertTrue(
            !HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
                PlaybackMode.PRIVATE,
                7L,
                7L,
                "other-song",
                "private-song"));
    }

    private static PlaylistSyncPacket snapshotPacket(long revision, String videoId) {
        return new PlaylistSyncPacket(
            revision,
            false,
            false,
            Arrays.asList(new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, videoId, "Server")));
    }

    private static final class RecordingTransport implements HorizonRadioClient.ClientTransport {

        private int addCount;
        private int playNowCount;
        private int removeCount;
        private int clearCount;
        private int reorderCount;
        private int seekCount;
        private int playbackCount;
        private int skipCount;
        private int previousCount;
        private int loopCount;
        private int shuffleCount;
        private int radioCount;
        private int stopRadioCount;
        private int playlistResyncCount;
        private int clockSyncCount;

        @Override
        public void sendAdd(String videoId, String title, String duration) {
            addCount++;
        }

        @Override
        public void sendPlayNow(String videoId, String title, String duration) {
            playNowCount++;
        }

        @Override
        public void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results) {}

        @Override
        public void sendRemove(String videoId) {
            removeCount++;
        }

        @Override
        public void sendClearPlaylist() {
            clearCount++;
        }

        @Override
        public void sendReorder(int fromIndex, int targetIndex) {
            reorderCount++;
        }

        @Override
        public void sendSeek(float progress) {
            seekCount++;
        }

        @Override
        public void sendTogglePlayback() {
            playbackCount++;
        }

        @Override
        public void sendSkipTrack() {
            skipCount++;
        }

        @Override
        public void sendPreviousTrack() {
            previousCount++;
        }

        @Override
        public void sendToggleLoop() {
            loopCount++;
        }

        @Override
        public void sendToggleShuffle() {
            shuffleCount++;
        }

        @Override
        public void sendSelectRadio(String stationUuid) {
            radioCount++;
        }

        @Override
        public void sendStopRadio() {
            stopRadioCount++;
        }

        @Override
        public void sendPlaylistResync(long knownRevision) {
            playlistResyncCount++;
        }

        @Override
        public void sendClockSync(long clientSentAtMs) {
            clockSyncCount++;
        }

        private int totalPacketCount() {
            return addCount + playNowCount + removeCount + clearCount + reorderCount + seekCount + playbackCount
                + skipCount + previousCount + loopCount + shuffleCount + radioCount + stopRadioCount
                + playlistResyncCount + clockSyncCount;
        }
    }
}
