package com.horizonradio.server.media;

/**
 * Immutable description of the signed 16-bit PCM accepted by the media pipeline.
 */
public final class PcmFormat {

    private static final int NORMALIZED_SAMPLE_RATE = 44100;
    private static final int NORMALIZED_CHANNELS = 2;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int MAX_SAMPLE_RATE = 384000;

    private final int sampleRate;
    private final int channels;
    private final int sampleSizeBits;
    private final boolean signed;
    private final boolean littleEndian;

    public PcmFormat(int sampleRate, int channels, int sampleSizeBits, boolean signed, boolean littleEndian) {
        if (sampleRate <= 0 || sampleRate > MAX_SAMPLE_RATE) {
            throw new IllegalArgumentException("PCM sample rate must be between 1 and " + MAX_SAMPLE_RATE);
        }
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("PCM channels must be mono or stereo");
        }
        if (sampleSizeBits != SAMPLE_SIZE_BITS) {
            throw new IllegalArgumentException("Only 16-bit PCM is supported");
        }
        if (!signed) {
            throw new IllegalArgumentException("Only signed PCM is supported");
        }

        this.sampleRate = sampleRate;
        this.channels = channels;
        this.sampleSizeBits = sampleSizeBits;
        this.signed = signed;
        this.littleEndian = littleEndian;
    }

    public static PcmFormat normalized() {
        return new PcmFormat(NORMALIZED_SAMPLE_RATE, NORMALIZED_CHANNELS, SAMPLE_SIZE_BITS, true, true);
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public int getSampleSizeBits() {
        return sampleSizeBits;
    }

    public int getBitsPerSample() {
        return sampleSizeBits;
    }

    public boolean isSigned() {
        return signed;
    }

    public boolean isLittleEndian() {
        return littleEndian;
    }

    public int getFrameSize() {
        return channels * (sampleSizeBits / Byte.SIZE);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PcmFormat)) {
            return false;
        }
        PcmFormat that = (PcmFormat) other;
        return sampleRate == that.sampleRate
            && channels == that.channels
            && sampleSizeBits == that.sampleSizeBits
            && signed == that.signed
            && littleEndian == that.littleEndian;
    }

    @Override
    public int hashCode() {
        int result = sampleRate;
        result = 31 * result + channels;
        result = 31 * result + sampleSizeBits;
        result = 31 * result + (signed ? 1 : 0);
        result = 31 * result + (littleEndian ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return sampleRate + " Hz, " + channels + " channel, " + sampleSizeBits + "-bit signed PCM"
            + (littleEndian ? " little-endian" : " big-endian");
    }
}
