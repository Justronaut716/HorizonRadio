package com.horizonradio.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Test;

public class StandalonePackagingTest {

    @Test
    public void packagedArtifactContainsJavaMediaRuntimeOnly() throws Exception {
        try (ZipFile zip = new ZipFile(requiredArtifact().toFile())) {
            assertNotNull(zip.getEntry("META-INF/horizonradio-media-notices.txt"));
            assertNotNull(zip.getEntry("javazoom/jl/decoder/Decoder.class"));
            assertNotNull(zip.getEntry("net/sourceforge/jaad/aac/Decoder.class"));
            assertNotNull(zip.getEntry("com/jcraft/jogg/Packet.class"));
            assertNotNull(zip.getEntry("com/jcraft/jorbis/Info.class"));
            assertNotNull(zip.getEntry("io/github/jaredmdobson/concentus/OpusDecoder.class"));

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement()
                    .getName()
                    .toLowerCase(Locale.ROOT);
                assertFalse(
                    "artifact must not contain prohibited runtime entry: " + name,
                    isProhibitedRuntimeEntry(name));
            }
        }
    }

    private static Path requiredArtifact() {
        String configured = System.getProperty("horizonradio.test.artifact", "")
            .trim();
        assertFalse("packaging test requires horizonradio.test.artifact", configured.isEmpty());
        Path artifact = Paths.get(configured);
        assertTrue("packaging artifact does not exist: " + artifact, Files.isRegularFile(artifact));
        return artifact;
    }

    private static boolean isProhibitedRuntimeEntry(String name) {
        return name.contains("ffmpeg") || name.contains("yt-dlp")
            || name.startsWith("net/minecraft/")
            || name.startsWith("net/minecraftforge/")
            || name.startsWith("cpw/mods/fml/")
            || name.startsWith("org/lwjgl/")
            || name.startsWith("org/junit/")
            || name.startsWith("junit/")
            || name.startsWith("org/hamcrest/")
            || name.endsWith("test.class")
            || name.matches(".*\\.(dll|so|dylib)(\\.[0-9]+)*");
    }
}
