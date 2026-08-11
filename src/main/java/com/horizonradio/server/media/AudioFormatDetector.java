package com.horizonradio.server.media;

import java.util.Locale;

/** Identifies a supported audio container from a bounded response prefix. */
public final class AudioFormatDetector {

    public MediaFormat detect(String contentType, byte[] prefix) {
        if (prefix == null) {
            prefix = new byte[0];
        }
        if (isAdts(prefix)) {
            return MediaFormat.AAC;
        }
        if (isMpeg(prefix)) {
            return MediaFormat.MP3;
        }
        if (isWave(prefix)) {
            return MediaFormat.WAV;
        }
        if (isIsoBmff(prefix)) {
            return MediaFormat.M4A;
        }
        if (isWebm(prefix)) {
            return MediaFormat.WEBM_OPUS;
        }
        if (isOgg(prefix)) {
            if (hasOggIdentification(prefix, "OpusHead")) {
                return MediaFormat.OGG_OPUS;
            }
            if (hasOggIdentification(prefix, "\u0001vorbis")) {
                return MediaFormat.OGG_VORBIS;
            }
            return isOggOpusContentType(contentType) ? MediaFormat.OGG_OPUS : MediaFormat.OGG_VORBIS;
        }
        return fromContentType(contentType);
    }

    private static MediaFormat fromContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("mpeg")) {
            return MediaFormat.MP3;
        }
        if (normalized.contains("mp4") || normalized.contains("m4a")) {
            return MediaFormat.M4A;
        }
        if (normalized.contains("aac")) {
            return MediaFormat.AAC;
        }
        if (normalized.contains("wav") || normalized.contains("wave")) {
            return MediaFormat.WAV;
        }
        if (normalized.contains("webm")) {
            return MediaFormat.WEBM_OPUS;
        }
        if (normalized.contains("ogg")) {
            return isOggOpusContentType(normalized) ? MediaFormat.OGG_OPUS : MediaFormat.OGG_VORBIS;
        }
        return MediaFormat.UNKNOWN;
    }

    private static boolean isOggOpusContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("opus");
    }

    private static boolean isAdts(byte[] prefix) {
        return prefix.length >= 4
            && (prefix[0] & 0xff) == 0xff
            && (prefix[1] & 0xf6) == 0xf0
            && (prefix[2] & 0x3c) != 0x3c;
    }

    private static boolean isMpeg(byte[] prefix) {
        return prefix.length >= 4
            && (prefix[0] & 0xff) == 0xff
            && (prefix[1] & 0xe0) == 0xe0
            && (prefix[1] & 0x18) != 0x08
            && (prefix[1] & 0x06) != 0
            && (prefix[2] & 0x0c) != 0x0c;
    }

    private static boolean isWave(byte[] prefix) {
        return prefix.length >= 12
            && matches(prefix, 0, "RIFF")
            && matches(prefix, 8, "WAVE");
    }

    private static boolean isOgg(byte[] prefix) {
        return prefix.length >= 4 && matches(prefix, 0, "OggS");
    }

    private static boolean isIsoBmff(byte[] prefix) {
        return prefix.length >= 12 && matches(prefix, 4, "ftyp");
    }

    private static boolean isWebm(byte[] prefix) {
        return prefix.length >= 4
            && (prefix[0] & 0xff) == 0x1a
            && (prefix[1] & 0xff) == 0x45
            && (prefix[2] & 0xff) == 0xdf
            && (prefix[3] & 0xff) == 0xa3;
    }

    static boolean hasOggIdentification(byte[] prefix) {
        return hasOggIdentification(prefix, "OpusHead") || hasOggIdentification(prefix, "\u0001vorbis");
    }

    private static boolean hasOggIdentification(byte[] prefix, String identifier) {
        if (prefix.length < 27) {
            return false;
        }
        int segments = prefix[26] & 0xff;
        int packetOffset = 27 + segments;
        return segments > 0 && prefix.length >= packetOffset + identifier.length()
            && matches(prefix, packetOffset, identifier);
    }

    private static boolean matches(byte[] bytes, int offset, String text) {
        if (offset < 0 || bytes.length - offset < text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (bytes[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
