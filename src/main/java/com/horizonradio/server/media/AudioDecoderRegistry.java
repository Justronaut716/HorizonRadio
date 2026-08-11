package com.horizonradio.server.media;

import java.io.IOException;
import java.io.InputStream;

/** Selects a decoder only for a recognized media format. */
public final class AudioDecoderRegistry {

    /** Format-selection policy for future resolvers: detected is not necessarily decodable. */
    public boolean supports(MediaFormat format) {
        return format == MediaFormat.WAV || format == MediaFormat.MP3
            || format == MediaFormat.AAC
            || format == MediaFormat.M4A
            || format == MediaFormat.OGG_VORBIS
            || format == MediaFormat.OGG_OPUS
            || format == MediaFormat.WEBM_OPUS;
    }

    public AudioDecoder find(MediaFormat format, InputStream prefix, InputStream input) throws IOException {
        if (!supports(format)) {
            throw new MediaException("Unsupported media format");
        }
        if (prefix == null || input == null) {
            throw new MediaException("Media prefix and input are required");
        }
        if (format == MediaFormat.WAV) {
            return RawPcmDecoder.forWave();
        }
        if (format == MediaFormat.MP3) {
            return new MpegAudioDecoder();
        }
        if (format == MediaFormat.AAC) {
            return new AacAudioDecoder();
        }
        if (format == MediaFormat.M4A) {
            return new M4aAacDecoder();
        }
        if (format == MediaFormat.OGG_VORBIS) {
            return new OggVorbisDecoder();
        }
        if (format == MediaFormat.OGG_OPUS) {
            return new OggOpusDecoder();
        }
        if (format == MediaFormat.WEBM_OPUS) {
            return new WebmOpusDecoder();
        }
        throw new MediaException("Unsupported media format: " + format);
    }
}
