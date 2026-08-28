package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
import com.horizonradio.server.AudioDownloadService;

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
        assertEquals(
            5L,
            HorizonRadioClient.getCachedRadioPresentation()
                .getGeneration());
        assertEquals(
            "station-id",
            HorizonRadioClient.getCachedRadioPresentation()
                .getStationUuid());
        assertFalse(HorizonRadioClient.isPaused());
    }

    @Test
    public void stopRadioSyncStopsStreamButKeepsStationAsResumablePresentation() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));
        HorizonRadioClient.handleLocalRadioStarted(5L, "station-id", "Station name");

        HorizonRadioClient.handleTrackSync(TrackSyncPacket.stop(6L));

        assertFalse(HorizonRadioClient.isRadioActive());
        assertFalse(
            HorizonRadioClient.getCachedRadioPresentation()
                .isActive());
        assertEquals(
            "station-id",
            HorizonRadioClient.getCachedRadioPresentation()
                .getStationUuid());
        assertEquals(
            "Station name",
            HorizonRadioClient.getCachedRadioPresentation()
                .getStationName());
        assertEquals(
            "",
            HorizonRadioClient.getCachedRadioPresentation()
                .getStatus());
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

        assertEquals(
            "Station name",
            HorizonRadioClient.getCachedRadioPresentation()
                .getStationName());
        assertEquals(
            "LIVE",
            HorizonRadioClient.getCachedRadioPresentation()
                .getStatus());

        HorizonRadioClient.handleLocalRadioFailure(5L, "station-id", "Connection lost");

        assertFalse(HorizonRadioClient.isRadioActive());
        assertEquals(
            "Connection lost",
            HorizonRadioClient.getCachedRadioPresentation()
                .getStatus());
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
    public void immediatelyResolvedQueueMetadataDoesNotDuplicateAnAuthoritativePlaylistEntry() {
        new ClientProxy(new DirectClientTaskScheduler());
        HorizonRadioClient.setClientMediaService(new ClientMediaService(new LocalMetadataProvider()));
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            HorizonRadioClient.handlePlaylistDelta(
                PlaylistDeltaPacket
                    .add(1L, new PlaylistDeltaPacket.Entry(MediaSourceType.YOUTUBE, "dQw4w9WgXcQ", "Alice"), 0));

            assertEquals(
                1,
                HorizonRadioClient.getCachedPlaylist()
                    .size());
            assertEquals(
                "Local title",
                HorizonRadioClient.getCachedPlaylist()
                    .get(0)
                    .displayTitle());
            assertEquals(
                1,
                screen.getPlaylistSnapshot()
                    .size());
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
            HorizonRadioClient.setClientMediaService(null);
        }
    }

    @Test
    public void authoritativeQueueUpdatesPrefetchOnlyTheNextFiniteTrack() throws Exception {
        new ClientProxy(new DirectClientTaskScheduler());
        Path directory = Files.createTempDirectory("horizonradio-prefetch-test");
        RecordingAudioDownloadService service = new RecordingAudioDownloadService(directory);
        HorizonRadioClient.setClientAudioDownloadService(service);
        try {
            HorizonRadioClient.handlePlaylistSnapshot(
                new PlaylistSyncPacket(
                    0L,
                    false,
                    false,
                    Arrays.asList(
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "dQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "aQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "bQw4w9WgXcQ", "Alice"))));
            // Queue updates prefetch after the settle window instead of immediately.
            HorizonRadioClient.onClientTick(System.currentTimeMillis() + 1500L);
            assertTrue(service.awaitDownload("dQw4w9WgXcQ"));

            HorizonRadioClient.handleTrackSync(
                TrackSyncPacket.youtube(5L, "dQw4w9WgXcQ", 0L, System.currentTimeMillis() + 3_000L, false));
            assertTrue(service.awaitDownload("aQw4w9WgXcQ"));
            assertFalse(service.hasDownload("bQw4w9WgXcQ"));
        } finally {
            HorizonRadioClient.setClientAudioDownloadService(null);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void rapidQueueRemovalsCoalesceIntoASinglePrefetch() throws Exception {
        new ClientProxy(new DirectClientTaskScheduler());
        Path directory = Files.createTempDirectory("horizonradio-prefetch-storm-test");
        RecordingAudioDownloadService service = new RecordingAudioDownloadService(directory);
        HorizonRadioClient.setClientAudioDownloadService(service);
        try {
            long now = System.currentTimeMillis();
            HorizonRadioClient.handlePlaylistSnapshot(
                new PlaylistSyncPacket(
                    6L,
                    false,
                    false,
                    Arrays.asList(
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "dQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "aQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "bQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "cQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "eQw4w9WgXcQ", "Alice"))));
            HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "dQw4w9WgXcQ", 0L, now + 3_000L, false));
            assertTrue(service.awaitDownload("aQw4w9WgXcQ"));

            // Deleting several queued tracks back to back must not start one download per deletion.
            HorizonRadioClient.handlePlaylistDelta(PlaylistDeltaPacket.remove(7L, 1));
            HorizonRadioClient.handlePlaylistDelta(PlaylistDeltaPacket.remove(8L, 1));
            HorizonRadioClient.handlePlaylistDelta(PlaylistDeltaPacket.remove(9L, 1));

            HorizonRadioClient.onClientTick(now + 500L);
            assertFalse(service.hasDownload("eQw4w9WgXcQ"));

            HorizonRadioClient.onClientTick(now + 1500L);
            assertTrue(service.hasDownload("eQw4w9WgXcQ"));

            HorizonRadioClient.onClientTick(now + 3_000L);
            assertEquals(Arrays.asList("dQw4w9WgXcQ", "aQw4w9WgXcQ", "eQw4w9WgXcQ"), service.downloadIds());
        } finally {
            HorizonRadioClient.setClientAudioDownloadService(null);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void trackChangesPruneTheCacheToCurrentPlusTwoNeighbours() throws Exception {
        new ClientProxy(new DirectClientTaskScheduler());
        Path directory = Files.createTempDirectory("horizonradio-window-test");
        RecordingAudioDownloadService service = new RecordingAudioDownloadService(directory);
        HorizonRadioClient.setClientAudioDownloadService(service);
        Path previous = directory.resolve("bQw4w9WgXcQ.wav");
        Path current = directory.resolve("cQw4w9WgXcQ.wav");
        try {
            Files.write(previous, new byte[60]);
            Files.write(current, new byte[60]);
            HorizonRadioClient.handlePlaylistSnapshot(
                new PlaylistSyncPacket(
                    0L,
                    false,
                    false,
                    Arrays.asList(
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "aQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "bQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "cQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "dQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "eQw4w9WgXcQ", "Alice"),
                        new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "fQw4w9WgXcQ", "Alice"))));

            // Starting c keeps c itself, the last two finished tracks, and the next two queued.
            HorizonRadioClient.handleTrackSync(
                TrackSyncPacket.youtube(5L, "cQw4w9WgXcQ", 0L, System.currentTimeMillis() + 3_000L, false));
            assertEquals(Arrays.asList("cQw4w9WgXcQ", "dQw4w9WgXcQ", "eQw4w9WgXcQ"), service.lastKeepSet());
            assertFalse(Files.exists(previous));
            assertTrue(Files.exists(current));

            // Advancing to d shifts the window by one: c joins the "previous" side.
            HorizonRadioClient.handleTrackSync(
                TrackSyncPacket.youtube(6L, "dQw4w9WgXcQ", 0L, System.currentTimeMillis() + 6_000L, false));
            assertEquals(
                Arrays.asList("dQw4w9WgXcQ", "cQw4w9WgXcQ", "eQw4w9WgXcQ", "fQw4w9WgXcQ"),
                service.lastKeepSet());

            // Stopping anchors the window on the most recent track.
            HorizonRadioClient.handleTrackSync(TrackSyncPacket.stop(7L));
            assertEquals(
                Arrays.asList("dQw4w9WgXcQ", "cQw4w9WgXcQ", "eQw4w9WgXcQ", "fQw4w9WgXcQ"),
                service.lastKeepSet());
        } finally {
            HorizonRadioClient.setClientAudioDownloadService(null);
            Files.deleteIfExists(previous);
            Files.deleteIfExists(current);
            Files.deleteIfExists(directory);
        }
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
            HorizonRadioClient
                .shouldAcceptTrackSync(6L, null, null, TrackSyncPacket.youtube(5L, "video-id", 0L, 3_000L, false)));
        assertFalse(HorizonRadioClient.shouldAcceptTrackSync(6L, null, null, TrackSyncPacket.stop(6L)));
    }

    private static final class DirectClientTaskScheduler implements ClientProxy.ClientTaskScheduler {

        @Override
        public void schedule(Runnable task) {
            task.run();
        }
    }

    private static final class RecordingAudioDownloadService extends AudioDownloadService {

        private final List<String> downloads = Collections.synchronizedList(new java.util.ArrayList<String>());
        private final java.util.Map<String, CompletableFuture<Path>> futures = new java.util.HashMap<String, CompletableFuture<Path>>();
        private final List<List<String>> keepSets = Collections
            .synchronizedList(new java.util.ArrayList<List<String>>());

        private RecordingAudioDownloadService(Path directory) throws java.io.IOException {
            super(directory, com.horizonradio.media.concurrent.MediaExecutors.newDownloadExecutor());
        }

        @Override
        public synchronized void keepOnlyTracks(java.util.Collection<String> videoIds) {
            keepSets.add(new java.util.ArrayList<String>(videoIds));
            super.keepOnlyTracks(videoIds);
        }

        private List<String> lastKeepSet() {
            synchronized (keepSets) {
                return keepSets.isEmpty() ? null : keepSets.get(keepSets.size() - 1);
            }
        }

        @Override
        public synchronized CompletableFuture<Path> download(String videoId) {
            CompletableFuture<Path> existing = futures.get(videoId);
            if (existing != null) {
                return existing;
            }
            downloads.add(videoId);
            CompletableFuture<Path> future = new CompletableFuture<Path>();
            futures.put(videoId, future);
            return future;
        }

        @Override
        public synchronized void cancelDownload(String videoId) {
            CompletableFuture<Path> future = futures.remove(videoId);
            if (future != null) {
                future.cancel(false);
            }
        }

        private boolean awaitDownload(String videoId) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (System.nanoTime() < deadline) {
                if (hasDownload(videoId)) {
                    return true;
                }
                Thread.sleep(5L);
            }
            return hasDownload(videoId);
        }

        private boolean hasDownload(String videoId) {
            synchronized (downloads) {
                return downloads.contains(videoId);
            }
        }

        private List<String> downloadIds() {
            synchronized (downloads) {
                return new java.util.ArrayList<String>(downloads);
            }
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
            return CompletableFuture
                .completedFuture("{\"id\":\"dQw4w9WgXcQ\",\"title\":\"Local title\",\"duration\":2}");
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
