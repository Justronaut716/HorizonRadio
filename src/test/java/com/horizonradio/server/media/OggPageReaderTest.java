package com.horizonradio.server.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.junit.Test;

public class OggPageReaderTest {

    @Test
    public void preservesPacketsAcrossLacingValuesAndPageBoundaries() throws Exception {
        byte[] firstPacketStart = repeated((byte) 7, 255);
        byte[] firstPage = page(0, new int[] { 255 }, firstPacketStart);
        byte[] secondPage = page(5, 1, new int[] { 3, 2 }, new byte[] { 8, 9, 10, 11, 12 });
        OggPageReader reader = new OggPageReader(new ByteArrayInputStream(join(firstPage, secondPage)));

        assertArrayEquals(join(firstPacketStart, new byte[] { 8, 9, 10 }), reader.nextPacket());
        assertArrayEquals(new byte[] { 11, 12 }, reader.nextPacket());
        assertNull(reader.nextPacket());
    }

    @Test
    public void finiteReaderRejectsEofWithoutEosWhileStreamingPolicyAllowsIt() throws Exception {
        byte[] noEos = page(2, 0, new int[] { 1 }, new byte[] { 7 });
        OggPageReader finite = new OggPageReader(new ByteArrayInputStream(noEos));
        assertArrayEquals(new byte[] { 7 }, finite.nextPacket());
        try {
            finite.nextPacket();
            fail("Expected finite Ogg input without EOS to be rejected");
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("EOS"));
        }

        OggPageReader streaming = OggPageReader.allowNoEos(new ByteArrayInputStream(noEos));
        assertArrayEquals(new byte[] { 7 }, streaming.nextPacket());
        assertNull(streaming.nextPacket());
    }

    @Test
    public void rejectsInvalidCaptureVersionAndTruncatedBodies() throws Exception {
        byte[] invalidCapture = page(0, new int[] { 1 }, new byte[] { 1 });
        invalidCapture[0] = 'X';
        assertInvalid(invalidCapture, "capture");

        byte[] invalidVersion = page(0, new int[] { 1 }, new byte[] { 1 });
        invalidVersion[4] = 1;
        assertInvalid(invalidVersion, "version");

        byte[] truncated = page(0, new int[] { 3 }, new byte[] { 1, 2 });
        assertInvalid(truncated, "truncated");
    }

    @Test
    public void rejectsPagesAndPacketsThatExceedConfiguredBoundsBeforeAllocation() throws Exception {
        byte[] page = page(0, new int[] { 4 }, new byte[] { 1, 2, 3, 4 });
        try {
            new OggPageReader(new ByteArrayInputStream(page), 3, 3).nextPacket();
            fail("Expected page body bound to be enforced");
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("page"));
        }

        byte[] continued = join(
            page(0, new int[] { 255 }, repeated((byte) 1, 255)),
            page(1, new int[] { 1 }, new byte[] { 2 }));
        try {
            new OggPageReader(new ByteArrayInputStream(continued), 1024, 255).nextPacket();
            fail("Expected packet bound to be enforced");
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("packet"));
        }
    }

    private static void assertInvalid(byte[] bytes, String messagePart) throws Exception {
        try {
            new OggPageReader(new ByteArrayInputStream(bytes)).nextPacket();
            fail("Expected malformed Ogg page to be rejected");
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .toLowerCase()
                    .contains(messagePart));
        }
    }

    private static byte[] page(int pageNumber, int[] lacing, byte[] body) {
        return page(pageNumber == 0 ? 2 : 1, pageNumber, lacing, body);
    }

    private static byte[] page(int flags, int pageNumber, int[] lacing, byte[] body) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write('O');
        output.write('g');
        output.write('g');
        output.write('S');
        output.write(0);
        output.write(flags);
        output.write(new byte[8], 0, 8);
        output.write(new byte[] { 1, 0, 0, 0 }, 0, 4);
        output.write(new byte[] { (byte) pageNumber, 0, 0, 0 }, 0, 4);
        output.write(new byte[4], 0, 4);
        output.write(lacing.length);
        for (int value : lacing) {
            output.write(value);
        }
        output.write(body, 0, body.length);
        byte[] page = output.toByteArray();
        putLeInt(page, 22, oggCrc(page));
        return page;
    }

    private static byte[] repeated(byte value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = value;
        }
        return bytes;
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] bytes = new byte[first.length + second.length];
        System.arraycopy(first, 0, bytes, 0, first.length);
        System.arraycopy(second, 0, bytes, first.length, second.length);
        return bytes;
    }

    private static void putLeInt(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }

    private static int oggCrc(byte[] bytes) {
        int crc = 0;
        for (int i = 0; i < bytes.length; i++) {
            int value = i >= 22 && i < 26 ? 0 : bytes[i] & 255;
            crc ^= value << 24;
            for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ ((crc & 0x80000000) == 0 ? 0 : 0x04c11db7);
        }
        return crc;
    }
}
