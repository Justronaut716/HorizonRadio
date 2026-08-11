package com.horizonradio.server;

import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class StandalonePackagingTest {

    @Test
    public void packagedArtifactContainsJavaMediaRuntimeOnly() throws Exception {
        String artifact = System.getProperty("horizonradio.test.artifact");
        Assume.assumeTrue("artifact property is required for packaging verification", artifact != null);

        try (ZipFile zip = new ZipFile(artifact)) {
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
                assertFalse("artifact must not contain prohibited runtime entry: " + name,
                    isProhibitedRuntimeEntry(name));
            }
        }
    }

    private static boolean isProhibitedRuntimeEntry(String name) {
        return name.contains("ffmpeg")
            || name.contains("yt-dlp")
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
