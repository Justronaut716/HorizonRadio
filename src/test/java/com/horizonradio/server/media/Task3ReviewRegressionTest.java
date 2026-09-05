package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import org.junit.Test;

public class Task3ReviewRegressionTest {

    private static final byte[] AAC = Base64.getDecoder()
        .decode(
            "//FQQCD//NwATGF2YzYzLjEuMTAwAAJgrFupUHImpXj14/euuJbVSWqWlSTJO4OhAQYVcvF3Gukta9peY8G7Y87q+TxS2DM+S+edNdI83c27i2DlLiOvtU21bNlU7VWfdVYjhWNxVxs2g2bG2LE3Ky3LOdexOOxuKuNmuM9WY6Nfmr81fmsc+tn1tSz1LHRr81pmr81fn1s+tn1s+qn1+awzWGmzos6K1FailTYzMZmMzGitRWorUWNNjMxmYxMaLOitRWosaLGZjMxiSiSniniiSiXwvkvklElPFPFPFEvkvkvkvvFPFPFPFEvkvxRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRcD/8VBAIT/8ATSa2tJNwGzFTCbMVKJ03U2p5+tXw2+NXdf+nH6/VwXep//S8/r8cBq5v/+31+/tIavjOhJdBBf4mjAw8eXAfZNO7BYpfDaiYXXBmtILtidzjSIfCxdrjYIcbFqaqa1BCpdVNnM/8bQ127+s6JBga7kEhIMDA2TuEhISDAwM7uEhJwGHMu7hJxoGZBrzu+QSE5L8/MDZGaE2Eu/whVhpq4jpBkpRSqSNzKzZ2VRuaWttttk0WkUps5kqqotM6mBvkwN/WQkGJAwMEhJK84MbNhISVBs3BgY2EhJW9jmixlCSt2Aggy8OFmOXiPtdf3h9NlWVflOiQk5NNMlWcmmCUt3g//FQQB/f/AE0m7LNJmmE2a0JWhD4Pnn9c15Ver0//tefrzxqwBRvxEIoEMjUGGNCpA6YmdbKC7E1wZUxfBhSZ8McMVQU8MMcAYGU+iQkGBncJCQYGBndwkJCQYGBndwkJKgwMDO7hITTBkQMid3cJCWsoAJjS9nAnmVEyLMMeByCKwxrMSvqsBjk7rXCK36LNdZnetc9WmunbrKJzn7X279Ty3Ynbp2g0sjzW/ctl6C8LEtSribP/+q+54lqW5zi5F5tuDhV2Ta3WMKOm/lnpny/86/5dH8AOrhvfPXT9XZ9fb3AG930Nzr7ufz38NfPk7u6RGWI/rXwX4V+F4D/8VBAAZ/8ARiBtHA=");
    private static final byte[] MP3 = Base64.getDecoder()
        .decode(
            "SUQzBAAAAAAAIlRTU0UAAAAOAAADTGF2ZjYzLjEuMTAwAAAAAAAAAAAAAAD/+0DAAAAAAAAAAAAAAAAAAAAAAABJbmZvAAAADwAAAAMAAAMoAHt7e3t7e3t7e3t7e3t7e3t7e3t7e3t7e3t7e3t7e3t7e729vb29vb29vb29vb29vb29vb29vb29vb29vb29vb29vf///////////////////////////////////////////wAAAABMYXZjNjMuMS4AAAAAAAAAAAAAAAAkAqMAAAAAAAADKIIss4AAAAAAAP/7UMQAAAosQy5VlIABfRVotzbQAAeqw44zhdNk8aHBCBxYH14fGBxGBB6J5bMuWg+oOxNr8PhgUBQEAwKCRAxOaNGjbB94gBAEMTg/wQdOdPnOX85y/u6fdy4Pg+D4fBAMSgDB/SAAB5oBAIBgKBgKBQOADQoE2kAGAxEgMJIjTg9wgV0XQ8koTSyYyMkaF1+thNQV3wVkT4TL8S4kR6jh/x3DDCXEiPX/yRMi8XjEu//l0yLxeRLpd/iIKgqIj3/BX//vckQAFB7ZbI24yln/+1LEBQAHVCE9vcEAMQ+DpT66EARKQrCG5GjZOZoGYGDKdLWmdNeZ137LtICzNOv1OQlHD29mVb/1rn/6v1J+9ON9//94AAAAEkJX7bJJFVVzAcBwQEhhEMB0975xyjZkaLJh+CJgCDZgqCJgmB6mdsCIZRwp66lxb35Kjv03dun/RZ+5f6PorooAACV6sWazW2TKBqw4BnRhZDJfGDIAJFkz+sCbZSQHTJzwU7jCYEEg/kRLcwyCzLgNFjvhwSTck5zAFT/+XTVHDouEb6BnD//7UsQjgBLgxz+5zIARMI2jA7LwAP++K7ZvGXHDijJELKqDfj/+386/bz2TPOAhJe4wRhEH/8/925/lu3xuytzPWwNKbH/6///+//f94HSeN9IKfd9PTfiMIA4CZzuv+aS5+6boC5BrwHHAbaAsEX1MY81FzSLWHMhU2mTQHVCuZkywJaUkxPRcUJgK6OX0elVNqtiwn12FDX2YurWy9YY2a+1vV6esKgt1///+/lvPdNVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV");
    private static final byte[] VORBIS = Base64.getDecoder()
        .decode(
            "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");
    private static final byte[] M4A = Base64.getDecoder()
        .decode(
            "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");
    private static final byte[] WEBM_OPUS = Base64.getDecoder()
        .decode(
            "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");

    @Test
    public void normalizesRawAndMonoWave() throws Exception {
        Recording raw = new Recording();
        new RawPcmDecoder(new PcmFormat(22050, 1, 16, true, true))
            .decode(new ByteArrayInputStream(new byte[] { 1, 0, 2, 0 }), raw);
        assertEquals(16, raw.bytes.size());
        Recording wave = new Recording();
        RawPcmDecoder.forWave()
            .decode(new ByteArrayInputStream(wave(22050, 1, new byte[] { 1, 0, 2, 0 })), wave);
        assertEquals(16, wave.bytes.size());
    }

    @Test
    public void abortsAfterThrowingDownstreamFinish() throws Exception {
        Recording downstream = new Recording();
        downstream.failFinish = true;
        ResamplingPcmSink sink = new ResamplingPcmSink(PcmFormat.normalized(), downstream);
        sink.write(new byte[] { 1, 0, 2, 0 }, 0, 4);
        try {
            sink.finish();
            fail();
        } catch (IOException expected) {}
        assertEquals(1, downstream.finishCalls);
        assertEquals(1, downstream.abortCalls);
    }

    @Test
    public void validatesOggCrcSingleSerialAndContiguousSequence() throws Exception {
        byte[] first = ogg(2, 7, 0, 0, new byte[] { 1 });
        byte[] corrupt = first.clone();
        corrupt[corrupt.length - 1] ^= 1;
        rejected(corrupt, "CRC");
        rejected(join(first, ogg(4, 8, 1, 1, new byte[] { 2 })), "serial");
        rejected(join(first, ogg(4, 7, 2, 1, new byte[] { 2 })), "sequence");
    }

    @Test
    public void decodesVorbisWithBosHeader() throws Exception {
        Recording sink = new Recording();
        new OggVorbisDecoder().decode(new ByteArrayInputStream(VORBIS), sink);
        assertTrue(sink.bytes.size() > 0);
        assertEquals(1, sink.finishCalls);
    }

    @Test
    public void trimsFinalVorbisPcmToTheEosGranuleBeforeNormalization() throws Exception {
        Recording sink = new Recording();
        new OggVorbisDecoder().decode(new ByteArrayInputStream(withFinalOggGranule(VORBIS, 2000L)), sink);
        assertEquals(8000, sink.bytes.size());
        assertEquals(1, sink.finishCalls);
        assertEquals(0, sink.abortCalls);
    }

    @Test
    public void trimsFinalVorbisPacketFromGranuleOnZeroSegmentEosPage() throws Exception {
        Recording sink = new Recording();
        new OggVorbisDecoder().decode(new ByteArrayInputStream(withZeroSegmentEos(VORBIS, 2000L)), sink);
        assertEquals(8000, sink.bytes.size());
        assertEquals(1, sink.finishCalls);
        assertEquals(0, sink.abortCalls);
    }

    @Test
    public void rejectsMissingOrInconsistentFinalVorbisGranule() throws Exception {
        assertVorbisAbort(withFinalOggGranule(VORBIS, -1L));
        assertVorbisAbort(withFinalOggGranule(VORBIS, 3000L));
    }

    @Test
    public void decodesAdtsAndTreatsCleanEofAsNormal() throws Exception {
        Recording sink = new Recording();
        new AacAudioDecoder().decode(new ByteArrayInputStream(AAC), sink);
        assertTrue(sink.bytes.size() > 0);
        assertEquals(1, sink.finishCalls);
    }

    @Test
    public void decodesInFileAacM4aThroughThePublicJaadMp4Api() throws Exception {
        Recording sink = new Recording();
        new M4aAacDecoder().decode(new ByteArrayInputStream(M4A), sink);
        assertTrue(sink.bytes.size() > 0);
        assertEquals(0, sink.bytes.size() % 4);
        assertEquals(1, sink.finishCalls);
    }

    @Test
    public void rejectsM4aSampleTableBeforeJaadCanMaterializeIt() throws Exception {
        Recording sink = new Recording();
        try {
            new M4aAacDecoder().decode(new ByteArrayInputStream(m4aWithExcessiveStszCount()), sink);
            fail();
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("preflight"));
        }
        assertEquals(0, sink.finishCalls);
        assertEquals(1, sink.abortCalls);
    }

    @Test
    public void decodesBoundedWebmOpusSimpleBlocksToNormalizedPcm() throws Exception {
        Recording sink = new Recording();
        new WebmOpusDecoder().decode(new ByteArrayInputStream(WEBM_OPUS), sink);
        assertEquals(10584, sink.bytes.size());
        assertEquals(0, sink.bytes.size() % 4);
        assertEquals(1, sink.finishCalls);
    }

    @Test
    public void trimsFinalWebmBlockGroupDiscardPaddingWithoutInfoDuration() throws Exception {
        Recording sink = new Recording();
        new WebmOpusDecoder().decode(new ByteArrayInputStream(withFinalWebmDiscardPadding(WEBM_OPUS, 10000000L)), sink);
        assertEquals(11204, sink.bytes.size());
        assertEquals(1, sink.finishCalls);
        assertEquals(0, sink.abortCalls);
    }

    @Test
    public void rejectsLacedWebmSimpleBlocksBeforePublishingPcm() throws Exception {
        byte[] laced = WEBM_OPUS.clone();
        int flags = indexOf(laced, new byte[] { (byte) 0xa3, 0x41, 0x37, (byte) 0x81, 0, 0, (byte) 0x80 }) + 6;
        assertTrue(flags >= 6);
        laced[flags] = (byte) 0x82;
        Recording sink = new Recording();
        try {
            new WebmOpusDecoder().decode(new ByteArrayInputStream(laced), sink);
            fail();
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("lacing"));
        }
        assertEquals(0, sink.finishCalls);
        assertEquals(1, sink.abortCalls);
    }

    @Test
    public void rejectsUnknownSizeWebmSegmentBeforePublishingPcm() throws Exception {
        byte[] unknownSize = WEBM_OPUS.clone();
        int segment = indexOf(unknownSize, new byte[] { 0x18, 0x53, (byte) 0x80, 0x67 });
        assertTrue(segment >= 0);
        unknownSize[segment + 4] = 1;
        for (int i = 1; i < 8; i++) unknownSize[segment + 4 + i] = (byte) 0xff;
        Recording sink = new Recording();
        try {
            new WebmOpusDecoder().decode(new ByteArrayInputStream(unknownSize), sink);
            fail();
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("unknown"));
        }
        assertEquals(0, sink.finishCalls);
        assertEquals(1, sink.abortCalls);
    }

    @Test
    public void acceptsWebmSegmentLargerThanNestedElementLimit() throws Exception {
        Recording sink = new Recording();
        new WebmOpusDecoder().decode(new ByteArrayInputStream(withLargeWebmSegment()), sink);
        assertEquals(10584, sink.bytes.size());
        assertEquals(1, sink.finishCalls);
        assertEquals(0, sink.abortCalls);
    }

    @Test
    public void rejectsAbsentEbmlHeaderBeforePublishingPcm() throws Exception {
        int segment = indexOf(WEBM_OPUS, new byte[] { 0x18, 0x53, (byte) 0x80, 0x67 });
        Recording sink = new Recording();
        try {
            new WebmOpusDecoder()
                .decode(new ByteArrayInputStream(Arrays.copyOfRange(WEBM_OPUS, segment, WEBM_OPUS.length)), sink);
            fail();
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("EBML"));
        }
        assertEquals(0, sink.finishCalls);
        assertEquals(1, sink.abortCalls);
    }

    @Test
    public void rejectsMalformedEbmlDoctypeBeforePublishingPcm() throws Exception {
        byte[] malformed = WEBM_OPUS.clone();
        int doctype = indexOf(malformed, new byte[] { 'w', 'e', 'b', 'm' });
        assertTrue(doctype >= 0);
        malformed[doctype] = 'm';
        Recording sink = new Recording();
        try {
            new WebmOpusDecoder().decode(new ByteArrayInputStream(malformed), sink);
            fail();
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("EBML"));
        }
        assertEquals(0, sink.finishCalls);
        assertEquals(1, sink.abortCalls);
    }

    @Test
    public void rejectsSegmentSizeThatContradictsDeclaredEbmlMaxSizeLength() throws Exception {
        byte[] contradictory = WEBM_OPUS.clone();
        int maxSize = indexOf(contradictory, new byte[] { 0x42, (byte) 0xf3, (byte) 0x81, 0x08 });
        assertTrue(maxSize >= 0);
        contradictory[maxSize + 3] = 1;
        Recording sink = new Recording();
        try {
            new WebmOpusDecoder().decode(new ByteArrayInputStream(contradictory), sink);
            fail();
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("size length"));
        }
        assertEquals(0, sink.finishCalls);
        assertEquals(1, sink.abortCalls);
    }

    @Test
    public void rejectsTruncatedFinalMpegFrame() throws Exception {
        byte[] truncated = new byte[MP3.length - 20];
        System.arraycopy(MP3, 0, truncated, 0, truncated.length);
        Recording sink = new Recording();
        try {
            new MpegAudioDecoder().decode(new ByteArrayInputStream(truncated), sink);
            fail();
        } catch (MediaException expected) {}
        assertEquals(1, sink.abortCalls);
        assertEquals(0, sink.finishCalls);
    }

    @Test
    public void decodesPositiveMpegFixtureToBoundedNormalizedPcm() throws Exception {
        Recording sink = new Recording();
        new MpegAudioDecoder().decode(new ByteArrayInputStream(MP3), sink);
        assertTrue(sink.bytes.size() > 0);
        assertEquals(0, sink.bytes.size() % 4);
        assertEquals(1, sink.finishCalls);
    }

    @Test
    public void appliesOpusPreSkipAndEosGranuleTrim() throws Exception {
        byte[] head = new byte[] { 'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1, (byte) 0xc0, 3, (byte) 0x80,
            (byte) 0xbb, 0, 0, 0, 0, 0 };
        byte[] tags = new byte[] { 'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0 };
        Recording sink = new Recording();
        new OggOpusDecoder().decode(
            new ByteArrayInputStream(
                join(
                    ogg(2, 4, 0, 0, head),
                    ogg(0, 4, 1, 0, tags),
                    ogg(4, 4, 2, 960, new byte[] { (byte) 0xf8, (byte) 0xff, (byte) 0xfe }))),
            sink);
        assertEquals(0, sink.bytes.size());
        assertEquals(1, sink.finishCalls);
    }

    @Test
    public void trimsFinalOpusPacketAfterPreSkipRatherThanOnlySkippingIt() throws Exception {
        byte[] head = new byte[] { 'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1, 56, 1, (byte) 0x80, (byte) 0xbb, 0, 0,
            0, 0, 0 };
        byte[] tags = new byte[] { 'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0 };
        Recording sink = new Recording();
        new OggOpusDecoder().decode(
            new ByteArrayInputStream(
                join(
                    ogg(2, 5, 0, 0, head),
                    ogg(0, 5, 1, 0, tags),
                    ogg(4, 5, 2, 812, new byte[] { (byte) 0xf8, (byte) 0xff, (byte) 0xfe }))),
            sink);
        assertEquals(1840, sink.bytes.size());
        assertEquals(1, sink.finishCalls);
    }

    @Test
    public void trimsLastOpusPacketFromGranuleOnZeroSegmentEosPage() throws Exception {
        byte[] head = new byte[] { 'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1, 56, 1, (byte) 0x80, (byte) 0xbb, 0, 0,
            0, 0, 0 };
        byte[] tags = new byte[] { 'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0 };
        Recording sink = new Recording();
        new OggOpusDecoder().decode(
            new ByteArrayInputStream(
                join(
                    ogg(2, 6, 0, 0, head),
                    ogg(0, 6, 1, 0, tags),
                    ogg(0, 6, 2, 812, new byte[] { (byte) 0xf8, (byte) 0xff, (byte) 0xfe }),
                    oggEmptyPage(4, 6, 3, 812))),
            sink);
        assertEquals(1840, sink.bytes.size());
        assertEquals(1, sink.finishCalls);
        assertEquals(0, sink.abortCalls);
    }

    private static void rejected(byte[] bytes, String reason) throws Exception {
        try {
            OggPageReader reader = new OggPageReader(new ByteArrayInputStream(bytes));
            while (reader.nextPacket() != null) {}
            fail();
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .contains(reason));
        }
    }

    private static byte[] wave(int rate, int channels, byte[] pcm) {
        byte[] b = new byte[44 + pcm.length];
        ascii(b, 0, "RIFF");
        leInt(b, 4, 36 + pcm.length);
        ascii(b, 8, "WAVEfmt ");
        leInt(b, 16, 16);
        leShort(b, 20, 1);
        leShort(b, 22, channels);
        leInt(b, 24, rate);
        leInt(b, 28, rate * channels * 2);
        leShort(b, 32, channels * 2);
        leShort(b, 34, 16);
        ascii(b, 36, "data");
        leInt(b, 40, pcm.length);
        System.arraycopy(pcm, 0, b, 44, pcm.length);
        return b;
    }

    private static byte[] m4aWithExcessiveStszCount() {
        byte[] stsz = new byte[12];
        beInt(stsz, 4, 0);
        beInt(stsz, 8, 300000);
        return join(
            box("ftyp", join(asciiBytes("M4A "), new byte[] { 0, 0, 0, 0 }, asciiBytes("isom"))),
            box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stsz", stsz)))))));
    }

    private static byte[] withFinalWebmDiscardPadding(byte[] source, long nanos) {
        byte[] result = source.clone();
        int duration = indexOf(result, new byte[] { 0x44, (byte) 0x89, (byte) 0x88 });
        assertTrue(duration >= 0);
        result[duration + 1] = (byte) 0x88;
        int padding = indexOf(result, new byte[] { 0x75, (byte) 0xa2, (byte) 0x84 });
        assertTrue(padding >= 0);
        beInt(result, padding + 3, (int) nanos);
        return result;
    }

    private static byte[] withLargeWebmSegment() {
        int segment = indexOf(WEBM_OPUS, new byte[] { 0x18, 0x53, (byte) 0x80, 0x67 });
        assertTrue(segment >= 0);
        int sizeOffset = segment + 4;
        int sizeLength = ebmlLength(WEBM_OPUS[sizeOffset]);
        int segmentData = sizeOffset + sizeLength;
        int segmentEnd = segmentData + readEbmlSize(WEBM_OPUS, sizeOffset);
        assertEquals(WEBM_OPUS.length, segmentEnd);

        byte[][] padding = { webmVoid(3_500_000), webmVoid(3_500_000), webmVoid(3_500_000), webmVoid(3_500_000),
            webmVoid(300_000) };
        int paddingLength = 0;
        for (byte[] part : padding) paddingLength += part.length;
        byte[] result = new byte[WEBM_OPUS.length + paddingLength];
        System.arraycopy(WEBM_OPUS, 0, result, 0, segmentEnd);
        int paddingOffset = segmentEnd;
        for (byte[] part : padding) {
            System.arraycopy(part, 0, result, paddingOffset, part.length);
            paddingOffset += part.length;
        }
        System.arraycopy(WEBM_OPUS, segmentEnd, result, paddingOffset, WEBM_OPUS.length - segmentEnd);
        writeEbmlSize(result, sizeOffset, sizeLength, readEbmlSize(WEBM_OPUS, sizeOffset) + paddingLength);
        return result;
    }

    private static byte[] webmVoid(int payloadLength) {
        byte[] result = new byte[1 + 4 + payloadLength];
        result[0] = (byte) 0xec;
        writeEbmlSize(result, 1, 4, payloadLength);
        return result;
    }

    private static int ebmlLength(byte first) {
        int value = first & 255, length = 1, mask = 128;
        while ((value & mask) == 0) {
            length++;
            mask >>>= 1;
        }
        return length;
    }

    private static int readEbmlSize(byte[] data, int offset) {
        int length = ebmlLength(data[offset]);
        int value = data[offset] & ((1 << (8 - length)) - 1);
        for (int i = 1; i < length; i++) value = value << 8 | data[offset + i] & 255;
        return value;
    }

    private static void writeEbmlSize(byte[] data, int offset, int length, int value) {
        for (int i = length - 1; i > 0; i--) {
            data[offset + i] = (byte) value;
            value >>>= 8;
        }
        data[offset] = (byte) ((1 << (8 - length)) | value);
    }

    private static byte[] box(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        beInt(out, 8 + payload.length);
        byte[] name = asciiBytes(type);
        out.write(name, 0, name.length);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] asciiBytes(String text) {
        byte[] bytes = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) bytes[i] = (byte) text.charAt(i);
        return bytes;
    }

    private static byte[] ogg(int flags, int serial, int sequence, long granule, byte[] packet) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('O');
        out.write('g');
        out.write('g');
        out.write('S');
        out.write(0);
        out.write(flags);
        leLong(out, granule);
        leInt(out, serial);
        leInt(out, sequence);
        leInt(out, 0);
        out.write(1);
        out.write(packet.length);
        out.write(packet, 0, packet.length);
        byte[] b = out.toByteArray();
        leInt(b, 22, crc(b));
        return b;
    }

    private static byte[] oggEmptyPage(int flags, int serial, int sequence, long granule) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('O');
        out.write('g');
        out.write('g');
        out.write('S');
        out.write(0);
        out.write(flags);
        leLong(out, granule);
        leInt(out, serial);
        leInt(out, sequence);
        leInt(out, 0);
        out.write(0);
        byte[] b = out.toByteArray();
        leInt(b, 22, crc(b));
        return b;
    }

    private static int crc(byte[] b) {
        int crc = 0;
        for (int i = 0; i < b.length; i++) {
            int x = i >= 22 && i < 26 ? 0 : b[i] & 255;
            crc ^= x << 24;
            for (int n = 0; n < 8; n++) crc = (crc << 1) ^ ((crc & 0x80000000) == 0 ? 0 : 0x04c11db7);
        }
        return crc;
    }

    private static void ascii(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }

    private static void leShort(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
    }

    private static void leInt(byte[] b, int o, int v) {
        for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> 8 * i);
    }

    private static void leLong(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> 8 * i);
    }

    private static void leInt(ByteArrayOutputStream o, int v) {
        for (int i = 0; i < 4; i++) o.write(v >>> 8 * i);
    }

    private static void beInt(byte[] b, int o, int v) {
        for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> 24 - 8 * i);
    }

    private static void beInt(ByteArrayOutputStream o, int v) {
        for (int i = 3; i >= 0; i--) o.write(v >>> 8 * i);
    }

    private static void leLong(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 8; i++) o.write((int) (v >>> 8 * i));
    }

    private static byte[] join(byte[]... a) {
        int n = 0;
        for (byte[] x : a) n += x.length;
        byte[] r = new byte[n];
        int p = 0;
        for (byte[] x : a) {
            System.arraycopy(x, 0, r, p, x.length);
            p += x.length;
        }
        return r;
    }

    private static byte[] withFinalOggGranule(byte[] source, long granule) {
        byte[] bytes = source.clone();
        int page = 0, last = -1;
        while (page < bytes.length) {
            if (page + 27 > bytes.length || bytes[page] != 'O'
                || bytes[page + 1] != 'g'
                || bytes[page + 2] != 'g'
                || bytes[page + 3] != 'S') throw new AssertionError("invalid Ogg fixture");
            int segments = bytes[page + 26] & 255, body = 0;
            for (int i = 0; i < segments; i++) body += bytes[page + 27 + i] & 255;
            last = page;
            page += 27 + segments + body;
        }
        if (page != bytes.length) throw new AssertionError("truncated Ogg fixture");
        leLong(bytes, last + 6, granule);
        leInt(bytes, last + 22, crc(bytes, last));
        return bytes;
    }

    private static byte[] withZeroSegmentEos(byte[] source, long granule) {
        byte[] bytes = source.clone();
        int page = 0, last = -1;
        while (page < bytes.length) {
            int segments = bytes[page + 26] & 255, body = 0;
            for (int i = 0; i < segments; i++) body += bytes[page + 27 + i] & 255;
            last = page;
            page += 27 + segments + body;
        }
        bytes[last + 5] &= ~4;
        leLong(bytes, last + 6, granule);
        leInt(bytes, last + 22, crc(bytes, last));
        return join(bytes, oggEmptyPage(4, readLeInt(bytes, last + 14), readLeInt(bytes, last + 18) + 1, granule));
    }

    private static void assertVorbisAbort(byte[] bytes) throws Exception {
        Recording sink = new Recording();
        try {
            new OggVorbisDecoder().decode(new ByteArrayInputStream(bytes), sink);
            fail();
        } catch (MediaException expected) {}
        assertEquals(0, sink.finishCalls);
        assertEquals(1, sink.abortCalls);
    }

    private static int crc(byte[] b, int start) {
        int segments = b[start + 26] & 255, lacingEnd = start + 27 + segments, end = lacingEnd;
        for (int i = start + 27; i < lacingEnd; i++) end += b[i] & 255;
        int crc = 0;
        for (int i = start; i < end; i++) {
            int x = i >= start + 22 && i < start + 26 ? 0 : b[i] & 255;
            crc ^= x << 24;
            for (int n = 0; n < 8; n++) crc = (crc << 1) ^ ((crc & 0x80000000) == 0 ? 0 : 0x04c11db7);
        }
        return crc;
    }

    private static int readLeInt(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | (b[o + 3] << 24);
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer: for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static final class Recording implements PcmSink {

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int finishCalls;
        int abortCalls;
        boolean failFinish;

        public void write(byte[] b, int o, int l) throws IOException {
            if (l % 4 != 0) throw new IOException("not normalized");
            bytes.write(b, o, l);
        }

        public void finish() throws IOException {
            finishCalls++;
            if (failFinish) throw new IOException("finish");
        }

        public void abort() {
            abortCalls++;
        }

        public void close() {}
    }
}
