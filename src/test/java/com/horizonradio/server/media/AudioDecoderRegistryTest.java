package com.horizonradio.server.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

public class AudioDecoderRegistryTest {

    @Test
    public void selectsTheWavAdapterAndStreamsOnlyTheDeclaredPcmPayload() throws Exception {
        byte[] wav = wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 });
        AudioDecoder decoder = new AudioDecoderRegistry()
            .find(MediaFormat.WAV, new ByteArrayInputStream(wav), new ByteArrayInputStream(wav));
        RecordingSink sink = new RecordingSink();

        decoder.decode(new ByteArrayInputStream(wav), sink);

        assertArrayEquals(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 }, sink.bytes.toByteArray());
        assertEquals(1, sink.finishCalls);
        assertEquals(0, sink.abortCalls);
    }

    @Test
    public void rejectsUnknownFormatsRatherThanFallingBackToAnArbitraryDecoder() throws Exception {
        try {
            new AudioDecoderRegistry().find(
                MediaFormat.UNKNOWN,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(new byte[0]));
            fail("Expected unknown media format to be rejected");
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("Unsupported"));
        }
    }

    @Test
    public void exposesEveryDetectedTask3ContainerAsDecodableForFutureResolverSelection() throws Exception {
        AudioDecoderRegistry registry = new AudioDecoderRegistry();
        assertTrue(registry.supports(MediaFormat.M4A));
        assertTrue(registry.supports(MediaFormat.WEBM_OPUS));
        assertTrue(registry.supports(MediaFormat.AAC));
        assertTrue(
            registry.find(
                MediaFormat.WEBM_OPUS,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(new byte[0])) instanceof WebmOpusDecoder);
    }

    private static byte[] wave(byte[] pcm) {
        byte[] wav = new byte[44 + pcm.length];
        putAscii(wav, 0, "RIFF");
        putLeInt(wav, 4, 36 + pcm.length);
        putAscii(wav, 8, "WAVEfmt ");
        putLeInt(wav, 16, 16);
        putLeShort(wav, 20, 1);
        putLeShort(wav, 22, 2);
        putLeInt(wav, 24, 44100);
        putLeInt(wav, 28, 176400);
        putLeShort(wav, 32, 4);
        putLeShort(wav, 34, 16);
        putAscii(wav, 36, "data");
        putLeInt(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static void putAscii(byte[] bytes, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            bytes[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void putLeShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void putLeInt(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            bytes[offset + i] = (byte) (value >>> (i * 8));
        }
    }

    private static final class RecordingSink implements PcmSink {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int finishCalls;
        private int abortCalls;

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            bytes.write(data, offset, length);
        }

        @Override
        public void finish() {
            finishCalls++;
        }

        @Override
        public void abort() {
            abortCalls++;
        }

        @Override
        public void close() {}
    }
}
