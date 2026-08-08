package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

import org.junit.Test;

public class HorizonRadioClientConfigTest {

    @Test
    public void missingFileUsesDefaultVolume() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-client-config-missing")
            .toFile();
        try {
            HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);

            assertEquals(1.0f, config.getVolume(), 0.0001f);
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
            assertTrue(new File(directory, "horizonradio-client.json").isFile());
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

    private static void write(File directory, String json) throws IOException {
        FileOutputStream output = new FileOutputStream(new File(directory, "horizonradio-client.json"));
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
