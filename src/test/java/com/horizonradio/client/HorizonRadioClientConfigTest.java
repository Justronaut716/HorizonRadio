package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Collections;

import org.junit.Test;

public class HorizonRadioClientConfigTest {

    @Test
    public void missingFileEnablesYoutubeAudioByDefault() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-client-config-missing")
            .toFile();
        try {
            assertTrue(
                HorizonRadioClientConfig.load(directory)
                    .isYoutubeAudioEnabled());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void youtubeAudioSettingSurvivesConfigurationRoundTrip() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-youtube-audio-setting")
            .toFile();
        try {
            HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);
            config.save(0.5f, new ClientFavorites(), PlaybackMode.SERVER, false);

            assertTrue(
                !HorizonRadioClientConfig.load(directory)
                    .isYoutubeAudioEnabled());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void missingOrInvalidPlaybackModeDefaultsToServer() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-playback-mode-default")
            .toFile();
        try {
            assertEquals(
                PlaybackMode.SERVER,
                HorizonRadioClientConfig.load(directory)
                    .getPlaybackMode());
            write(directory, "{\"playbackMode\":\"not-a-mode\"}");
            assertEquals(
                PlaybackMode.SERVER,
                HorizonRadioClientConfig.load(directory)
                    .getPlaybackMode());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void privateModeSurvivesConfigurationRoundTrip() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-playback-mode-roundtrip")
            .toFile();
        try {
            HorizonRadioClientConfig.load(directory)
                .save(0.35f, new ClientFavorites(), PlaybackMode.PRIVATE);
            assertEquals(
                PlaybackMode.PRIVATE,
                HorizonRadioClientConfig.load(directory)
                    .getPlaybackMode());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void savedVolumeLoadsAgainFromDedicatedFile() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-client-config-roundtrip")
            .toFile();
        try {
            HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);
            config.save(0.35f);

            assertEquals(
                0.35f,
                HorizonRadioClientConfig.load(directory)
                    .getVolume(),
                0.0001f);
            assertTrue(new File(directory, HorizonRadioClientConfig.FILE_NAME).isFile());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void malformedFileUsesDefaultVolume() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-client-config-malformed")
            .toFile();
        try {
            write(directory, "{not-json");

            assertEquals(
                1.0f,
                HorizonRadioClientConfig.load(directory)
                    .getVolume(),
                0.0001f);
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void persistedVolumeIsBoundedToSupportedRange() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-client-config-bounds")
            .toFile();
        try {
            write(directory, "{\"volume\":2.5}");
            assertEquals(
                1.0f,
                HorizonRadioClientConfig.load(directory)
                    .getVolume(),
                0.0001f);

            write(directory, "{\"volume\":-0.5}");
            assertEquals(
                0.0f,
                HorizonRadioClientConfig.load(directory)
                    .getVolume(),
                0.0001f);
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void savedFavoritesRoundTripWithVolume() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-favorites-roundtrip")
            .toFile();
        try {
            ClientFavorites favorites = new ClientFavorites(
                Collections.singletonList(new ClientFavorites.Song("song", "Song", "Channel", "2:00", "thumb")),
                Collections.singletonList(new ClientFavorites.Radio("station", "Station")));
            HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);

            config.save(0.35f, favorites);

            HorizonRadioClientConfig loaded = HorizonRadioClientConfig.load(directory);
            assertEquals(0.35f, loaded.getVolume(), 0.0001f);
            assertEquals(
                favorites.getSongs(),
                loaded.getFavorites()
                    .getSongs());
            assertEquals(
                favorites.getRadios(),
                loaded.getFavorites()
                    .getRadios());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void volumeOnlyConfigurationLoadsEmptyFavorites() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-favorites-legacy")
            .toFile();
        try {
            write(directory, "{\"volume\":0.5}");

            HorizonRadioClientConfig loaded = HorizonRadioClientConfig.load(directory);

            assertEquals(0.5f, loaded.getVolume(), 0.0001f);
            assertTrue(
                loaded.getFavorites()
                    .getSongs()
                    .isEmpty());
            assertTrue(
                loaded.getFavorites()
                    .getRadios()
                    .isEmpty());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void invalidFavoriteRecordsAreSkippedWhileValidRecordsSurvive() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-favorites-invalid")
            .toFile();
        try {
            write(
                directory,
                "{\"volume\":0.5,\"favoriteSongs\":[" + "{\"videoId\":\"valid\",\"title\":\"Valid\"},"
                    + "{\"videoId\":\" \"},42],"
                    + "\"favoriteRadios\":[{\"stationUuid\":\"station\",\"name\":\"Station\"},null]}");

            HorizonRadioClientConfig loaded = HorizonRadioClientConfig.load(directory);

            assertEquals(
                Collections.singletonList("valid"),
                songIds(
                    loaded.getFavorites()
                        .getSongs()));
            assertEquals(
                Collections.singletonList("station"),
                radioIds(
                    loaded.getFavorites()
                        .getRadios()));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void savingVolumeWithoutExplicitFavoritesPreservesLoadedFavorites() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-favorites-save")
            .toFile();
        try {
            ClientFavorites favorites = new ClientFavorites(
                Collections.singletonList(new ClientFavorites.Song("song", "Song", "", "", "")),
                Collections.<ClientFavorites.Radio>emptyList());
            HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);
            config.save(0.4f, favorites);
            config = HorizonRadioClientConfig.load(directory);

            config.save(0.6f);

            assertEquals(
                Collections.singletonList("song"),
                songIds(
                    HorizonRadioClientConfig.load(directory)
                        .getFavorites()
                        .getSongs()));
            assertEquals(
                0.6f,
                HorizonRadioClientConfig.load(directory)
                    .getVolume(),
                0.0001f);
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void savingVolumeWithoutExplicitFavoritesPreservesLoadedPlaybackMode() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-playback-mode-volume-save")
            .toFile();
        try {
            HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);
            config.save(0.4f, new ClientFavorites(), PlaybackMode.PRIVATE);
            HorizonRadioClientConfig.load(directory)
                .save(0.6f);

            assertEquals(
                PlaybackMode.PRIVATE,
                HorizonRadioClientConfig.load(directory)
                    .getPlaybackMode());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void savingFavoritesWithoutExplicitPlaybackModePreservesLoadedPlaybackMode() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-playback-mode-favorites-save")
            .toFile();
        try {
            HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);
            config.save(0.4f, new ClientFavorites(), PlaybackMode.PRIVATE);
            HorizonRadioClientConfig loaded = HorizonRadioClientConfig.load(directory);
            loaded.save(0.5f, new ClientFavorites());

            assertEquals(
                PlaybackMode.PRIVATE,
                HorizonRadioClientConfig.load(directory)
                    .getPlaybackMode());
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void write(File directory, String json) throws IOException {
        FileOutputStream output = new FileOutputStream(new File(directory, HorizonRadioClientConfig.FILE_NAME));
        try {
            output.write(json.getBytes(Charset.forName("UTF-8")));
        } finally {
            output.close();
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

    private static java.util.List<String> songIds(java.util.List<ClientFavorites.Song> songs) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (ClientFavorites.Song song : songs) {
            ids.add(song.getVideoId());
        }
        return ids;
    }

    private static java.util.List<String> radioIds(java.util.List<ClientFavorites.Radio> radios) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (ClientFavorites.Radio radio : radios) {
            ids.add(radio.getStationUuid());
        }
        return ids;
    }
}
