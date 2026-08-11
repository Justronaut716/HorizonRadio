package com.horizonradio.server.media;

import java.io.IOException;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

/** Shared, container-independent Opus packet decoder with OpusHead pre-skip handling. */
final class OpusPacketDecoder {

    private static final int OPUS_RATE = 48000;
    private static final int MAX_SAMPLES_PER_PACKET = 5760;

    private final int channels;
    private final int preSkip;
    private final OpusDecoder decoder;
    private final ResamplingPcmSink pcm;
    private final short[] decoded;

    private long remainingPreSkip;
    private long emittedSamples;
    private int packetCount;
    private boolean finished;

    OpusPacketDecoder(byte[] head, PcmSink sink) throws IOException {
        channels = parseChannels(head);
        preSkip = unsignedShort(head, 10);
        remainingPreSkip = preSkip;
        try {
            decoder = new OpusDecoder(OPUS_RATE, channels);
        } catch (OpusException exception) {
            throw new MediaException("Unable to initialize Opus decoder", exception);
        }
        pcm = new ResamplingPcmSink(new PcmFormat(OPUS_RATE, channels, 16, true, true), sink);
        decoded = new short[MAX_SAMPLES_PER_PACKET * channels];
    }

    /**
     * Decodes one packet. {@code finalOutputSamples} is the total number of
     * presentation samples (after pre-skip) when this is the final packet, or
     * {@code -1} when no container end trim is available.
     */
    void decodePacket(byte[] packet, long finalOutputSamples) throws IOException {
        decodePacket(packet, finalOutputSamples, 0L);
    }

    /** Decodes one packet with an optional final-packet presentation discard. */
    void decodePacket(byte[] packet, long finalOutputSamples, long discardOutputSamples) throws IOException {
        if (finished) {
            throw new MediaException("Opus decoder is already finished");
        }
        if (packet == null || packet.length == 0) {
            throw new MediaException("Empty Opus packet");
        }
        int sampleCount;
        try {
            sampleCount = decoder.decode(packet, 0, packet.length, decoded, 0, MAX_SAMPLES_PER_PACKET, false);
        } catch (OpusException exception) {
            throw new MediaException("Invalid Opus packet", exception);
        }
        if (sampleCount <= 0 || sampleCount > MAX_SAMPLES_PER_PACKET) {
            throw new MediaException("Invalid Opus packet sample count");
        }
        int skipped = (int) Math.min(remainingPreSkip, sampleCount);
        remainingPreSkip -= skipped;
        int emit = sampleCount - skipped;
        if (discardOutputSamples < 0L || discardOutputSamples > emit) {
            throw new MediaException("Invalid Opus final discard padding");
        }
        if (finalOutputSamples >= 0) {
            if (finalOutputSamples < emittedSamples || finalOutputSamples > emittedSamples + emit) {
                throw new MediaException("Invalid Opus final sample count: " + finalOutputSamples
                    + " after " + emittedSamples + " samples with " + emit + " available");
            }
            int limited = (int) (finalOutputSamples - emittedSamples);
            if (discardOutputSamples != 0L && limited != emit - discardOutputSamples) {
                throw new MediaException("Inconsistent Opus duration and discard padding");
            }
            emit = limited;
        } else {
            emit -= (int) discardOutputSamples;
        }
        byte[] bytes = new byte[emit * channels * 2];
        for (int i = 0; i < emit * channels; i++) {
            short sample = decoded[skipped * channels + i];
            bytes[i * 2] = (byte) sample;
            bytes[i * 2 + 1] = (byte) (sample >>> 8);
        }
        pcm.write(bytes, 0, bytes.length);
        emittedSamples += emit;
        packetCount++;
    }

    void finish() throws IOException {
        if (finished) {
            return;
        }
        if (packetCount == 0) {
            throw new MediaException("Opus stream contains no audio packets");
        }
        pcm.finish();
        finished = true;
    }

    void abort() throws IOException {
        if (!finished) {
            pcm.abort();
        }
    }

    int getPreSkip() {
        return preSkip;
    }

    private static int parseChannels(byte[] head) throws IOException {
        if (head == null || head.length < 19 || !startsWith(head, "OpusHead") || head[8] != 1) {
            throw new MediaException("Invalid Opus identification header");
        }
        int channels = head[9] & 0xff;
        if (channels < 1 || channels > 2 || head[18] != 0) {
            throw new MediaException("Unsupported Opus channel mapping");
        }
        return channels;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static boolean startsWith(byte[] bytes, String text) {
        if (bytes.length < text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (bytes[i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
