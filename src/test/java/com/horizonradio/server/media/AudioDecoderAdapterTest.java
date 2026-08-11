package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

public class AudioDecoderAdapterTest {

    @Test
    public void registrySelectsOnlyTheAdapterForEachSupportedCompressedFormat() throws Exception {
        AudioDecoderRegistry registry = new AudioDecoderRegistry();

        assertTrue(registry.find(MediaFormat.MP3, input(), input()) instanceof MpegAudioDecoder);
        assertTrue(registry.find(MediaFormat.AAC, input(), input()) instanceof AacAudioDecoder);
        assertTrue(registry.find(MediaFormat.OGG_VORBIS, input(), input()) instanceof OggVorbisDecoder);
        assertTrue(registry.find(MediaFormat.OGG_OPUS, input(), input()) instanceof OggOpusDecoder);
    }

    @Test
    public void opusSilenceFixtureDecodesThroughThePcmSinkWithoutExternalTools() throws Exception {
        RecordingSink sink = new RecordingSink();

        new OggOpusDecoder().decode(new ByteArrayInputStream(opusSilenceOgg()), sink);

        assertTrue("Expected the one-frame Opus fixture to produce PCM", sink.bytes.size() > 0);
        assertEquals(0, sink.bytes.size() % PcmFormat.normalized().getFrameSize());
        assertEquals(1, sink.finishCalls);
        assertEquals(0, sink.abortCalls);
        for (byte value : sink.bytes.toByteArray()) {
            assertEquals("Opus comfort-noise fixture must remain bounded", 0, value);
        }
    }

    @Test
    public void finiteOggOpusWithoutEosAbortsInsteadOfPublishingPartialAudio() throws Exception {
        assertAborts(new OggOpusDecoder(), opusSilenceOggWithoutEos());
    }

    @Test
    public void inconsistentOpusEosGranuleStillAborts() throws Exception {
        assertAborts(new OggOpusDecoder(), opusSilenceOgg(1272L));
    }

    @Test
    public void malformedCompressedStreamsAbortTheirSinkInsteadOfFinishingPartialOutput() throws Exception {
        assertAborts(new MpegAudioDecoder(), new byte[] { 0, 1, 2, 3 });
        assertAborts(new AacAudioDecoder(), new byte[] { (byte) 0xff, (byte) 0xf1, 0x50, (byte) 0x80 });
        assertAborts(new OggVorbisDecoder(), oggPage(2, new byte[][] { ascii("not-vorbis") }));
    }

    private static void assertAborts(AudioDecoder decoder, byte[] bytes) throws Exception {
        RecordingSink sink = new RecordingSink();
        try {
            decoder.decode(new ByteArrayInputStream(bytes), sink);
            fail("Expected invalid compressed media to be rejected");
        } catch (MediaException expected) {
            assertEquals(0, sink.finishCalls);
            assertEquals(1, sink.abortCalls);
        }
    }

    private static ByteArrayInputStream input() {
        return new ByteArrayInputStream(new byte[0]);
    }

    private static byte[] opusSilenceOgg() { return opusSilenceOgg(960L); }

    private static byte[] opusSilenceOgg(long granule) {
        byte[] opusHead = new byte[] {
            'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1,
            56, 1, (byte) 0x80, (byte) 0xbb, 0, 0, 0, 0, 0
        };
        byte[] opusTags = new byte[] {
            'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0
        };
        return join(
            oggPage(2, 0, new byte[][] { opusHead }),
            oggPage(0, 1, new byte[][] { opusTags }),
            oggPage(4, 2, granule, new byte[][] { new byte[] { (byte) 0xf8, (byte) 0xff, (byte) 0xfe } }));
    }

    private static byte[] opusSilenceOggWithoutEos() {
        byte[] opusHead = new byte[] {
            'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1,
            56, 1, (byte) 0x80, (byte) 0xbb, 0, 0, 0, 0, 0
        };
        byte[] opusTags = new byte[] {
            'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0
        };
        return join(
            oggPage(2, 0, new byte[][] { opusHead }),
            oggPage(0, 1, new byte[][] { opusTags }),
            oggPage(0, 2, 0L, new byte[][] { new byte[] { (byte) 0xf8, (byte) 0xff, (byte) 0xfe } }));
    }

    private static byte[] oggPage(int flags, byte[][] packets) { return oggPage(flags, 0, packets); }

    private static byte[] oggPage(int flags, int sequence, byte[][] packets) { return oggPage(flags, sequence, 0L, packets); }

    private static byte[] oggPage(int flags, int sequence, long granule, byte[][] packets) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream lacing = new ByteArrayOutputStream();
        for (byte[] packet : packets) {
            int remaining = packet.length;
            int offset = 0;
            do {
                int segment = Math.min(255, remaining);
                lacing.write(segment);
                body.write(packet, offset, segment);
                offset += segment;
                remaining -= segment;
            } while (remaining > 0);
            if (packet.length > 0 && packet.length % 255 == 0) {
                lacing.write(0);
            }
        }
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        page.write('O');
        page.write('g');
        page.write('g');
        page.write('S');
        page.write(0);
        page.write(flags);
        writeLeLong(page, granule);
        page.write(new byte[] { 1, 0, 0, 0 }, 0, 4);
        page.write(new byte[] { (byte) sequence, 0, 0, 0 }, 0, 4);
        page.write(new byte[4], 0, 4);
        page.write(lacing.size());
        page.write(lacing.toByteArray(), 0, lacing.size());
        page.write(body.toByteArray(), 0, body.size());
        byte[] bytes = page.toByteArray();
        putLeInt(bytes, 22, oggCrc(bytes));
        return bytes;
    }

    private static byte[] ascii(String value) {
        byte[] bytes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            bytes[i] = (byte) value.charAt(i);
        }
        return bytes;
    }

    private static byte[] join(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, joined, offset, array.length);
            offset += array.length;
        }
        return joined;
    }

    private static void putLeInt(byte[] bytes, int offset, int value) { for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i)); }
    private static void writeLeLong(ByteArrayOutputStream output, long value) { for (int i = 0; i < 8; i++) output.write((int) (value >>> (8 * i))); }
    private static int oggCrc(byte[] bytes) { int crc = 0; for (int i = 0; i < bytes.length; i++) { int value = i >= 22 && i < 26 ? 0 : bytes[i] & 255; crc ^= value << 24; for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ ((crc & 0x80000000) == 0 ? 0 : 0x04c11db7); } return crc; }

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
        public void close() {
        }
    }
}
