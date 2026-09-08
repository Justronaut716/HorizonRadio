package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.horizonradio.client.media.ClientMediaService;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.network.packets.TrackSyncPacket;

public class HorizonRadioClientFavoritesTest {

    @Before
    public void setUp() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setClientMediaService(null);
        HorizonRadioClient.loadClientConfig(null);
    }

    @After
    public void tearDown() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setClientMediaService(null);
        HorizonRadioClient.loadClientConfig(null);
    }

    @Test
    public void currentYoutubeSourceCanBeFavoritedAndReloaded() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-current-favorite")
            .toFile();
        try {
            HorizonRadioClient.loadClientConfig(directory);
            HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "video-id", 0L, 0L, true));

            assertTrue(HorizonRadioClient.hasCurrentFavoriteSource());
            assertTrue(HorizonRadioClient.toggleCurrentFavorite());
            assertTrue(HorizonRadioClient.isCurrentSourceFavorite());

            HorizonRadioClient.loadClientConfig(directory);
            assertEquals(
                "video-id",
                HorizonRadioClient.getFavoriteSongs()
                    .get(0)
                    .getVideoId());
        } finally {
            HorizonRadioClient.clearCache();
            HorizonRadioClient.loadClientConfig(null);
            deleteRecursively(directory);
        }
    }

    @Test
    public void songFavoriteLookupMatchesTheClientFavoriteState() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "favorite-song", 0L, 0L, true));

        assertFalse(HorizonRadioClient.isSongFavorite("favorite-song"));
        assertTrue(HorizonRadioClient.toggleCurrentFavorite());
        assertTrue(HorizonRadioClient.isSongFavorite("favorite-song"));
        assertFalse(HorizonRadioClient.isSongFavorite("other-song"));
    }

    @Test
    public void currentRadioSourceCanBeFavoritedAndRemoved() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));

        assertTrue(HorizonRadioClient.hasCurrentFavoriteSource());
        assertTrue(HorizonRadioClient.toggleCurrentFavorite());
        assertTrue(HorizonRadioClient.isCurrentSourceFavorite());
        assertEquals(
            "station-id",
            HorizonRadioClient.getFavoriteRadios()
                .get(0)
                .getStationUuid());

        assertFalse(HorizonRadioClient.toggleCurrentFavorite());
        assertFalse(HorizonRadioClient.isCurrentSourceFavorite());
        assertTrue(
            HorizonRadioClient.getFavoriteRadios()
                .isEmpty());
    }

    @Test
    public void pausedRadioSourceRemainsFavoritable() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.stop(6L));

        assertTrue(HorizonRadioClient.hasCurrentFavoriteSource());
        assertTrue(HorizonRadioClient.toggleCurrentFavorite());
        assertTrue(HorizonRadioClient.isCurrentSourceFavorite());
    }

    @Test
    public void clearingPlaybackCachePreservesClientFavorites() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "video-id", 0L, 0L, true));
        HorizonRadioClient.toggleCurrentFavorite();

        HorizonRadioClient.clearCache();

        assertEquals(
            "video-id",
            HorizonRadioClient.getFavoriteSongs()
                .get(0)
                .getVideoId());
    }

    @Test
    public void changingVolumeDoesNotErasePersistedFavorites() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-favorite-volume")
            .toFile();
        try {
            HorizonRadioClient.loadClientConfig(directory);
            HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "video-id", 0L, 0L, true));
            HorizonRadioClient.toggleCurrentFavorite();

            HorizonRadioClient.setVolume(0.4f);
            HorizonRadioClient.loadClientConfig(directory);

            assertEquals(
                "video-id",
                HorizonRadioClient.getFavoriteSongs()
                    .get(0)
                    .getVideoId());
            assertEquals(0.4f, HorizonRadioClient.getVolume(), 0.0001f);
        } finally {
            HorizonRadioClient.clearCache();
            HorizonRadioClient.loadClientConfig(null);
            deleteRecursively(directory);
        }
    }

    @Test
    public void favoriteSongsRecoverArtistMetadataWhenTheyWereSavedBeforeLookupFinished() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "dQw4w9WgXcQ", 0L, 0L, true));
        assertTrue(HorizonRadioClient.toggleCurrentFavorite());
        assertEquals(
            "",
            HorizonRadioClient.getFavoriteSongs()
                .get(0)
                .getChannel());

        HorizonRadioClient
            .setClientMediaService(new ClientMediaService(new ArtistMetadataProvider("dQw4w9WgXcQ", "Song", "Artist")));

        assertEquals(
            "Artist",
            HorizonRadioClient.getFavoriteSongs()
                .get(0)
                .getChannel());
    }

    @Test
    public void controlCenterUsesResolvedArtistMetadataWithoutAQueueMetadataEntry() {
        HorizonRadioClient
            .setClientMediaService(new ClientMediaService(new ArtistMetadataProvider("dQw4w9WgXcQ", "Song", "Artist")));
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "dQw4w9WgXcQ", 0L, 0L, true));

        HorizonRadioScreen screen = new HorizonRadioScreen();
        screen.updateNowPlaying("Song", 0.0f);

        assertEquals("Artist", invokeCurrentArtistLabel(screen));
    }

    private static String invokeCurrentArtistLabel(HorizonRadioScreen screen) {
        try {
            java.lang.reflect.Method method = HorizonRadioScreen.class.getDeclaredMethod("currentArtistLabel");
            method.setAccessible(true);
            return (String) method.invoke(screen);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Current artist label was not available", exception);
        }
    }

    private static final class ArtistMetadataProvider implements ClientMediaService.RemoteProvider {

        private final String videoId;
        private final String title;
        private final String artist;

        private ArtistMetadataProvider(String videoId, String title, String artist) {
            this.videoId = videoId;
            this.title = title;
            this.artist = artist;
        }

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
                "{\"id\":\"" + videoId
                    + "\",\"title\":\""
                    + title
                    + "\",\"uploader\":\""
                    + artist
                    + "\",\"duration\":120}");
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

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
