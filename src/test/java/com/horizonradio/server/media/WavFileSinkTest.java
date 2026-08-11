package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class WavFileSinkTest {

    @Test
    public void publishesAValidNormalizedWaveOnlyAfterFinish() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-wav-sink");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            WavFileSink sink = new WavFileSink(destination, 64L);
            sink.write(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 }, 0, 8);
            assertFalse(Files.exists(destination));

            sink.finish();

            byte[] wave = Files.readAllBytes(destination);
            assertEquals(52, wave.length);
            assertEquals("RIFF", ascii(wave, 0));
            assertEquals(44, leInt(wave, 4));
            assertEquals("WAVE", ascii(wave, 8));
            assertEquals(44100, leInt(wave, 24));
            assertEquals(2, leShort(wave, 22));
            assertEquals(16, leShort(wave, 34));
            assertEquals(8, leInt(wave, 40));
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void abortDeletesTemporaryOutputAndNeverPublishesPartialFrames() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-wav-abort");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            WavFileSink sink = new WavFileSink(destination, 64L);
            try {
                sink.write(new byte[] { 1, 0 }, 0, 2);
                fail("Expected partial normalized PCM frame to be rejected");
            } catch (MediaException expected) {
                // A 44.1 kHz stereo signed-16 frame is exactly four bytes.
            }
            sink.abort();
            assertFalse(Files.exists(destination));
            assertFalse(Files.exists(directory.resolve("dQw4w9WgXcQ.wav.part")));
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void failsClosedAndCleansUpWhenAtomicPublicationIsUnavailable() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-wav-atomic");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            WavFileSink sink = new WavFileSink(
                destination,
                64L,
                (source, target) -> {
                    throw new java.nio.file.AtomicMoveNotSupportedException(
                        source.toString(),
                        target.toString(),
                        "test");
                });
            sink.write(new byte[] { 1, 0, 2, 0 }, 0, 4);
            try {
                sink.finish();
                fail("Expected unavailable atomic move to reject publication");
            } catch (java.nio.file.AtomicMoveNotSupportedException expected) {
                // A finite cache entry must never be published non-atomically.
            }
            assertFalse(Files.exists(destination));
            assertEquals(
                0L,
                Files.list(directory)
                    .count());
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int leShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int leInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16)
            | ((bytes[offset + 3] & 0xff) << 24);
    }
}
