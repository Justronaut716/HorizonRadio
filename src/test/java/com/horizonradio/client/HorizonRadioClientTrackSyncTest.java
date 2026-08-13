package com.horizonradio.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.horizonradio.client.media.ClientMediaService;
import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.TrackSyncPacket;

public class HorizonRadioClientTrackSyncTest {

    @Before
    public void setUp() {
        HorizonRadioClient.clearCache();
    }

    @After
    public void tearDown() {
        HorizonRadioClient.clearCache();
    }

    @Test
    public void acceptsNewerTrackGenerations() {
        assertTrue(
            HorizonRadioClient
                .shouldAcceptTrackSync(4L, "old-video", new TrackSyncPacket(5L, "new-video", 0L, 3_000L, false)));
    }

    @Test
    public void rejectsOlderOrDuplicateTrackGenerations() {
        assertFalse(
            HorizonRadioClient
                .shouldAcceptTrackSync(5L, "new-video", new TrackSyncPacket(4L, "old-video", 0L, 3_000L, false)));
        assertFalse(
            HorizonRadioClient
                .shouldAcceptTrackSync(5L, "new-video", new TrackSyncPacket(5L, "new-video", 0L, 3_000L, false)));
    }

    @Test
    public void acceptsNewerRadioGenerationUsingItsStationSourceId() {
        assertTrue(
            HorizonRadioClient.shouldAcceptTrackSync(
                4L,
                MediaSourceType.YOUTUBE,
                "old-video",
                TrackSyncPacket.radio(5L, "station-id")));
    }

    @Test
    public void handlesRadioTrackSyncAsLocalLivePlayback() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));

        assertTrue(HorizonRadioClient.isRadioActive());
        assertEquals(5L, HorizonRadioClient.getCachedRadioPresentation().getGeneration());
        assertEquals("station-id", HorizonRadioClient.getCachedRadioPresentation().getStationUuid());
        assertFalse(HorizonRadioClient.isPaused());
    }

    @Test
    public void radioTrackIgnoresFinitePauseAndResumeControls() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));

        HorizonRadioClient.handlePause(10_000L);
        HorizonRadioClient.handleResume(10_000L, 20_000L);

        assertFalse(HorizonRadioClient.isPaused());
        assertTrue(HorizonRadioClient.isRadioActive());
    }

    @Test
    public void localRadioCallbacksReplaceLiveEdgeLabelAndKeepFailuresLocal() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));

        HorizonRadioClient.handleLocalRadioStarted(5L, "station-id", "Station name");

        assertEquals("Station name", HorizonRadioClient.getCachedRadioPresentation().getStationName());
        assertEquals("LIVE", HorizonRadioClient.getCachedRadioPresentation().getStatus());

        HorizonRadioClient.handleLocalRadioFailure(5L, "station-id", "Connection lost");

        assertFalse(HorizonRadioClient.isRadioActive());
        assertEquals("Connection lost", HorizonRadioClient.getCachedRadioPresentation().getStatus());
    }

    @Test
    public void clientTickBuildsFinitePresentationFromLocalMetadataAndServerClock() {
        new ClientProxy(new DirectClientTaskScheduler());
        HorizonRadioClient.setClientMediaService(new ClientMediaService(new LocalMetadataProvider()));

        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "dQw4w9WgXcQ", 1_000L, 10_000L, false));
        HorizonRadioClient.refreshLocalFinitePresentation(10_000L);

        assertEquals("Local title", HorizonRadioClient.getCachedNowPlaying());
        assertEquals(0.5f, HorizonRadioClient.getCachedProgress(), 0.0001f);
    }

    @Test
    public void removingActiveRadioFromAuthoritativeQueueStopsLocalRadio() {
        HorizonRadioClient.handlePlaylistSnapshot(
            new PlaylistSyncPacket(
                0L,
                false,
                false,
                Arrays.asList(new PlaylistSyncPacket.Entry(MediaSourceType.RADIO, "station-id", "Alice"))));
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));

        HorizonRadioClient.handlePlaylistDelta(PlaylistDeltaPacket.remove(1L, 0));

        assertFalse(HorizonRadioClient.isRadioActive());
        assertNull(HorizonRadioClient.getCachedRadioPresentation());
    }

    @Test
    public void stopTrackSyncStopsFinitePlaybackAndRejectsTheStaleGeneration() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "video-id", 12_000L, 0L, true));

        HorizonRadioClient.handleTrackSync(TrackSyncPacket.stop(6L));

        assertFalse(HorizonRadioClient.isPaused());
        assertFalse(
            HorizonRadioClient.shouldAcceptTrackSync(
                6L,
                null,
                null,
                TrackSyncPacket.youtube(5L, "video-id", 0L, 3_000L, false)));
        assertFalse(HorizonRadioClient.shouldAcceptTrackSync(6L, null, null, TrackSyncPacket.stop(6L)));
    }

    private static final class DirectClientTaskScheduler implements ClientProxy.ClientTaskScheduler {

        @Override
        public void schedule(Runnable task) {
            task.run();
        }
    }

    private static final class LocalMetadataProvider implements ClientMediaService.RemoteProvider {

        @Override
        public CompletableFuture<List<SearchResult>> search(String query, long maxDurationMs) {
            return CompletableFuture.completedFuture(Collections.<SearchResult>emptyList());
        }

        @Override
        public CompletableFuture<List<SearchResult>> fetchCharts(ChartRegion region) {
            return CompletableFuture.completedFuture(Collections.<SearchResult>emptyList());
        }

        @Override
        public CompletableFuture<String> extractPlaylistJson(String playlistUrl) {
            return CompletableFuture.completedFuture("{\"entries\":[]}");
        }

        @Override
        public CompletableFuture<String> extractVideoJson(String videoUrl) {
            return CompletableFuture.completedFuture(
                "{\"id\":\"dQw4w9WgXcQ\",\"title\":\"Local title\",\"duration\":2}");
        }

        @Override
        public CompletableFuture<List<RadioStation>> searchRadio(String query) {
            return CompletableFuture.completedFuture(Collections.<RadioStation>emptyList());
        }

        @Override
        public CompletableFuture<RadioStation> lookupRadio(String stationUuid) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
