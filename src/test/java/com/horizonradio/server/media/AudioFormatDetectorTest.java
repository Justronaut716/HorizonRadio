package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioFormatDetectorTest {

    private final AudioFormatDetector detector = new AudioFormatDetector();

    @Test
    public void recognizesValidContainerAndFrameSignaturesBeforeContentTypeHints() {
        assertEquals(
            MediaFormat.MP3,
            detector.detect("audio/unknown", new byte[] { (byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64 }));
        assertEquals(
            MediaFormat.AAC,
            detector.detect(null, new byte[] { (byte) 0xff, (byte) 0xf1, 0x50, (byte) 0x80 }));
        assertEquals(MediaFormat.WAV, detector.detect(null, wavePrefix()));
        assertEquals(
            MediaFormat.M4A,
            detector.detect("audio/mp4", new byte[] { 0, 0, 0, 0, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ' }));
        assertEquals(MediaFormat.OGG_OPUS, detector.detect(null, oggPrefix("OpusHead")));
        assertEquals(MediaFormat.OGG_VORBIS, detector.detect(null, oggPrefix("\u0001vorbis")));
        assertEquals(
            MediaFormat.WEBM_OPUS,
            detector.detect(null, new byte[] { 0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, (byte) 0x93 }));
    }

    @Test
    public void usesSafeAudioContentTypeHintsWhenThePrefixIsTooShortToIdentifyAFormat() {
        assertEquals(MediaFormat.MP3, detector.detect("audio/mpeg; charset=binary", new byte[] { 1 }));
        assertEquals(MediaFormat.AAC, detector.detect("audio/aac", new byte[0]));
        assertEquals(MediaFormat.WAV, detector.detect("audio/wav", new byte[0]));
        assertEquals(MediaFormat.OGG_OPUS, detector.detect("audio/ogg; codecs=opus", new byte[0]));
        assertEquals(MediaFormat.OGG_VORBIS, detector.detect("application/ogg", new byte[0]));
        assertEquals(MediaFormat.WEBM_OPUS, detector.detect("audio/webm", new byte[0]));
    }

    @Test
    public void rejectsMalformedAndTruncatedPrefixesInsteadOfGuessing() {
        assertEquals(
            MediaFormat.UNKNOWN,
            detector.detect("application/octet-stream", new byte[] { (byte) 0xff, (byte) 0xe0 }));
        assertEquals(
            MediaFormat.UNKNOWN,
            detector.detect(null, new byte[] { 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'N', 'O', 'P', 'E' }));
        assertEquals(MediaFormat.UNKNOWN, detector.detect(null, new byte[] { 'O', 'g', 'g' }));
        assertEquals(MediaFormat.UNKNOWN, detector.detect("text/plain", new byte[] { 1, 2, 3, 4 }));
    }

    private static byte[] wavePrefix() {
        return new byte[] { 'R', 'I', 'F', 'F', 36, 0, 0, 0, 'W', 'A', 'V', 'E' };
    }

    private static byte[] oggPrefix(String identification) {
        byte[] bytes = new byte[28 + identification.length()];
        bytes[0] = 'O';
        bytes[1] = 'g';
        bytes[2] = 'g';
        bytes[3] = 'S';
        bytes[26] = 1;
        bytes[27] = (byte) identification.length();
        for (int i = 0; i < identification.length(); i++) {
            bytes[28 + i] = (byte) identification.charAt(i);
        }
        return bytes;
    }
}
