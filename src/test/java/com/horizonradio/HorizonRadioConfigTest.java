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

import com.horizonradio.core.config.HorizonRadioConfig;

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
        } finally {
            deleteRecursively(configDirectory);
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
