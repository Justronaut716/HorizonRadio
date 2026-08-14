package com.horizonradio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.network.PacketBufferUtil;

public class HorizonRadioConfigTest {

    @Test
    public void loadsJsonKeysFromConfigDirectory() throws IOException {
        File configDirectory = Files.createTempDirectory("horizonradio-config")
            .toFile();
        try {
            writeConfig(
                configDirectory,
                "{\"downloadDir\":\"D:/audio\",\"maxPlaylistSize\":12,"
                    + "\"maxTrackDurationMinutes\":7,\"youtubeCookiesFromBrowser\":\"chrome\","
                    + "\"youtubeCookiesFile\":\"D:/cookies.txt\",\"serverDebugChat\":true}");

            HorizonRadioConfig config = HorizonRadioConfig.load(configDirectory);

            assertEquals("D:/audio", config.getDownloadDir());
            assertEquals(12, config.getMaxPlaylistSize());
            assertEquals(7, config.getMaxTrackDurationMinutes());
            assertEquals("chrome", config.getYoutubeCookiesFromBrowser());
            assertEquals("D:/cookies.txt", config.getYoutubeCookiesFile());
            assertTrue(config.isServerDebugChat());
        } finally {
            deleteRecursively(configDirectory);
        }
    }

    @Test
    public void clampsPlaylistSizeToTheWireCollectionLimit() throws IOException {
        File configDirectory = Files.createTempDirectory("horizonradio-config-playlist-limit")
            .toFile();
        try {
            writeConfig(configDirectory, "{\"maxPlaylistSize\":" + (PacketBufferUtil.MAX_COLLECTION_SIZE + 1) + "}");

            HorizonRadioConfig config = HorizonRadioConfig.load(configDirectory);

            assertEquals(PacketBufferUtil.MAX_COLLECTION_SIZE, config.getMaxPlaylistSize());
        } finally {
            deleteRecursively(configDirectory);
        }
    }

    @Test
    public void usesDefaultsWhenConfigFileIsMissing() throws IOException {
        File configDirectory = Files.createTempDirectory("horizonradio-config-defaults")
            .toFile();
        try {
            HorizonRadioConfig config = HorizonRadioConfig.load(configDirectory);

            assertEquals("./horizonradio-downloads", config.getDownloadDir());
            assertEquals(50, config.getMaxPlaylistSize());
            assertEquals(15, config.getMaxTrackDurationMinutes());
            assertEquals("", config.getYoutubeCookiesFromBrowser());
            assertEquals("", config.getYoutubeCookiesFile());
            assertFalse(config.isServerDebugChat());

            JsonObject generated = new Gson().fromJson(readConfig(configDirectory), JsonObject.class);
            assertTrue(generated.has("serverDebugChat"));
            assertFalse(
                generated.get("serverDebugChat")
                    .getAsBoolean());
        } finally {
            deleteRecursively(configDirectory);
        }
    }

    @Test
    public void savesAndReloadsBothServerDebugChatValues() throws IOException {
        File sourceDirectory = Files.createTempDirectory("horizonradio-config-debug-chat-source")
            .toFile();
        File savedDirectory = Files.createTempDirectory("horizonradio-config-debug-chat-saved")
            .toFile();
        try {
            writeConfig(sourceDirectory, "{\"serverDebugChat\":true}");
            HorizonRadioConfig.load(sourceDirectory)
                .save(savedDirectory);
            assertTrue(
                HorizonRadioConfig.load(savedDirectory)
                    .isServerDebugChat());
            assertTrue(readConfig(savedDirectory).contains("\"serverDebugChat\":true"));

            writeConfig(sourceDirectory, "{\"serverDebugChat\":false}");
            HorizonRadioConfig.load(sourceDirectory)
                .save(savedDirectory);
            assertFalse(
                HorizonRadioConfig.load(savedDirectory)
                    .isServerDebugChat());
            assertTrue(readConfig(savedDirectory).contains("\"serverDebugChat\":false"));
        } finally {
            deleteRecursively(sourceDirectory);
            deleteRecursively(savedDirectory);
        }
    }

    private static void writeConfig(File configDirectory, String json) throws IOException {
        File configFile = new File(configDirectory, "horizonradio.json");
        FileOutputStream output = new FileOutputStream(configFile);
        try {
            output.write(json.getBytes(Charset.forName("UTF-8")));
        } finally {
            output.close();
        }
    }

    private static String readConfig(File configDirectory) throws IOException {
        File configFile = new File(configDirectory, "horizonradio.json");
        return new String(Files.readAllBytes(configFile.toPath()), Charset.forName("UTF-8"));
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
