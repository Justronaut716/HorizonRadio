package com.horizonradio.server.media;

import java.io.IOException;
import java.io.InputStream;

/** Streams supplied raw PCM metadata or a RIFF/WAVE PCM payload without buffering it. */
public final class RawPcmDecoder implements AudioDecoder {

    private static final long MAX_WAV_DATA_BYTES = 192L * 1024L * 1024L;
    private static final int COPY_BUFFER_SIZE = 8192;

    private final PcmFormat rawFormat;
    private final boolean waveContainer;

    public RawPcmDecoder(PcmFormat rawFormat) {
        if (rawFormat == null) {
            throw new IllegalArgumentException("PCM format is required");
        }
        this.rawFormat = rawFormat;
        waveContainer = false;
    }

    private RawPcmDecoder() {
        rawFormat = null;
        waveContainer = true;
    }

    public static RawPcmDecoder forWave() {
        return new RawPcmDecoder();
    }

    @Override
    public void decode(InputStream input, PcmSink sink) throws IOException {
        if (input == null || sink == null) {
            throw new IllegalArgumentException("Input and PCM sink are required");
        }
        ResamplingPcmSink normalized = null;
        boolean finished = false;
        try {
            if (waveContainer) {
                normalized = streamWave(input, sink);
            } else {
                normalized = new ResamplingPcmSink(rawFormat, sink);
                streamExact(input, normalized, rawFormat, -1L);
            }
            normalized.finish();
            finished = true;
        } catch (IOException exception) {
            if (!finished) {
                abortAfterFailure(normalized == null ? sink : normalized, exception);
            }
            throw exception;
        }
    }

    private static ResamplingPcmSink streamWave(InputStream input, PcmSink sink) throws IOException {
        byte[] riff = new byte[12];
        readFully(input, riff, 0, riff.length, "WAV header");
        if (!matches(riff, 0, "RIFF") || !matches(riff, 8, "WAVE")) {
            throw new MediaException("Invalid WAV header");
        }
        PcmFormat format = null;
        while (true) {
            byte[] chunkHeader = new byte[8];
            readFully(input, chunkHeader, 0, chunkHeader.length, "WAV chunk header");
            long length = unsignedInt(chunkHeader, 4);
            if (length > MAX_WAV_DATA_BYTES) {
                throw new MediaException("WAV chunk exceeds limit");
            }
            if (matches(chunkHeader, 0, "fmt ")) {
                if (length < 16) {
                    throw new MediaException("Truncated WAV format chunk");
                }
                byte[] fmt = new byte[16];
                readFully(input, fmt, 0, fmt.length, "WAV format chunk");
                int audioFormat = unsignedShort(fmt, 0);
                int channels = unsignedShort(fmt, 2);
                long sampleRate = unsignedInt(fmt, 4);
                int blockAlign = unsignedShort(fmt, 12);
                int bitsPerSample = unsignedShort(fmt, 14);
                if (audioFormat != 1 || sampleRate > Integer.MAX_VALUE) {
                    throw new MediaException("Only PCM WAV is supported");
                }
                try {
                    format = new PcmFormat((int) sampleRate, channels, bitsPerSample, true, true);
                } catch (IllegalArgumentException exception) {
                    throw new MediaException("Unsupported WAV PCM format", exception);
                }
                if (blockAlign != format.getFrameSize()) {
                    throw new MediaException("Invalid WAV PCM block alignment");
                }
                skipFully(input, length - fmt.length, "WAV format chunk");
            } else if (matches(chunkHeader, 0, "data")) {
                if (format == null) {
                    throw new MediaException("WAV data appears before its format");
                }
                ResamplingPcmSink normalized = new ResamplingPcmSink(format, sink);
                streamExact(input, normalized, format, length);
                return normalized;
            } else {
                skipFully(input, length, "WAV chunk");
            }
            if ((length & 1L) != 0L) {
                skipFully(input, 1L, "WAV chunk padding");
            }
        }
    }

    private static void streamExact(InputStream input, PcmSink sink, PcmFormat format, long remaining)
        throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0L;
        while (remaining != 0L) {
            int requested = remaining < 0L ? buffer.length : (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, requested);
            if (read < 0) {
                if (remaining > 0L) {
                    throw new MediaException("Truncated PCM payload");
                }
                break;
            }
            if (read == 0) {
                continue;
            }
            total += read;
            if (remaining > 0L) {
                remaining -= read;
            }
            if (total % format.getFrameSize() != 0L && remaining == 0L) {
                throw new MediaException("PCM payload ends with a partial frame");
            }
            sink.write(buffer, 0, read);
        }
        if (total % format.getFrameSize() != 0L) {
            throw new MediaException("PCM payload ends with a partial frame");
        }
    }

    private static void abortAfterFailure(PcmSink sink, IOException original) {
        try {
            sink.abort();
        } catch (IOException abortFailure) {
            original.addSuppressed(abortFailure);
        }
    }

    private static void readFully(InputStream input, byte[] bytes, int offset, int length, String part)
        throws IOException {
        int total = 0;
        while (total < length) {
            int count = input.read(bytes, offset + total, length - total);
            if (count < 0) {
                throw new MediaException("Truncated " + part);
            }
            total += count;
        }
    }

    private static void skipFully(InputStream input, long length, String part) throws IOException {
        byte[] buffer = new byte[1024];
        long remaining = length;
        while (remaining > 0L) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new MediaException("Truncated " + part);
            }
            remaining -= read;
        }
    }

    private static boolean matches(byte[] bytes, int offset, String expected) {
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xffL) | (((long) bytes[offset + 1] & 0xffL) << 8)
            | (((long) bytes[offset + 2] & 0xffL) << 16)
            | (((long) bytes[offset + 3] & 0xffL) << 24);
    }
}
