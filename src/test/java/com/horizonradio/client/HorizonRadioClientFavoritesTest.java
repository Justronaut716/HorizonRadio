package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.horizonradio.network.packets.TrackSyncPacket;

public class HorizonRadioClientFavoritesTest {

    @Before
    public void setUp() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.loadClientConfig(null);
    }

    @After
    public void tearDown() {
        HorizonRadioClient.clearCache();
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
    public void stoppedSourceCannotBeFavorited() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.stop(6L));

        assertFalse(HorizonRadioClient.hasCurrentFavoriteSource());
        assertFalse(HorizonRadioClient.toggleCurrentFavorite());
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
