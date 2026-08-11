package com.horizonradio.server.media;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/** JLayer adapter that decodes MPEG frames from one continuous bitstream. */
public final class MpegAudioDecoder implements AudioDecoder {

    @Override
    public void decode(InputStream input, PcmSink sink) throws IOException {
        ResamplingPcmSink pcm = null;
        Bitstream stream = null;
        boolean finished = false;
        try {
            BufferedInputStream source = new BufferedInputStream(input, 8192);
            skipId3(source);
            stream = new Bitstream(new MpegFrameInputStream(source));
            Decoder decoder = new Decoder();
            int frames = 0;
            Header header;

            while ((header = stream.readFrame()) != null) {
                try {
                    SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, stream);
                    if (pcm == null) {
                        pcm = new ResamplingPcmSink(
                            new PcmFormat(samples.getSampleFrequency(), samples.getChannelCount(), 16, true, true),
                            sink);
                    }
                    writeSamples(samples.getBuffer(), samples.getBufferLength(), pcm);
                    frames++;
                } finally {
                    // JLayer must retain the frame's side information and reservoir state
                    // while the next frame is read from this same Bitstream.
                    stream.closeFrame();
                }
            }

            if (frames == 0 || pcm == null) {
                throw new MediaException("MPEG stream contains no decodable frames");
            }
            pcm.finish();
            finished = true;
        } catch (MediaException exception) {
            abort(pcm, sink, finished, exception);
            throw exception;
        } catch (Exception exception) {
            MediaException wrapped = new MediaException("Unable to decode MPEG audio", exception);
            abort(pcm, sink, finished, wrapped);
            throw wrapped;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // The decode failure, if any, is already reported above.
                }
            }
        }
    }

    private static void skipId3(BufferedInputStream in) throws IOException {
        in.mark(10);
        byte[] header = new byte[10];
        int length = readAtMost(in, header);
        if (length < header.length || header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
            in.reset();
            return;
        }
        int size = (header[6] & 127) << 21 | (header[7] & 127) << 14 | (header[8] & 127) << 7 | (header[9] & 127);
        skipFully(in, size, "ID3 tag");
    }

    private static void writeSamples(short[] samples, int length, PcmSink sink) throws IOException {
        byte[] bytes = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            bytes[i * 2] = (byte) samples[i];
            bytes[i * 2 + 1] = (byte) (samples[i] >>> 8);
        }
        sink.write(bytes, 0, bytes.length);
    }

    private static int readAtMost(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        int count;
        while (offset < buffer.length && (count = in.read(buffer, offset, buffer.length - offset)) > 0) {
            offset += count;
        }
        return offset;
    }

    private static void skipFully(InputStream in, int length, String part) throws IOException {
        byte[] buffer = new byte[1024];
        while (length > 0) {
            int count = in.read(buffer, 0, Math.min(length, buffer.length));
            if (count < 0) {
                throw new MediaException("Truncated " + part);
            }
            length -= count;
        }
    }

    private static void readFully(InputStream in, byte[] buffer, int offset, int length, String part)
        throws IOException {
        int total = 0;
        while (total < length) {
            int count = in.read(buffer, offset + total, length - total);
            if (count < 0) {
                throw new MediaException("Truncated " + part);
            }
            total += count;
        }
    }

    /**
     * Validates and exposes complete MPEG frames without splitting the
     * decoder's input stream. JLayer must see the frames consecutively so its
     * Layer III bit reservoir remains intact.
     */
    private static final class MpegFrameInputStream extends InputStream {

        private final InputStream source;
        private byte[] frame;
        private int frameOffset;

        private MpegFrameInputStream(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) {
                throw new NullPointerException("buffer");
            }
            if (offset < 0 || length < 0 || offset > buffer.length - length) {
                throw new IndexOutOfBoundsException("Invalid buffer range");
            }
            if (length == 0) {
                return 0;
            }
            if (frame == null || frameOffset == frame.length) {
                frame = nextFrame(source);
                frameOffset = 0;
                if (frame == null) {
                    return -1;
                }
            }
            int count = Math.min(length, frame.length - frameOffset);
            System.arraycopy(frame, frameOffset, buffer, offset, count);
            frameOffset += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    private static byte[] nextFrame(InputStream in) throws IOException {
        int first;
        while ((first = in.read()) >= 0) {
            if (first != 0xff) {
                continue;
            }
            int second = in.read();
            if (second < 0) {
                throw new MediaException("Truncated MPEG frame header");
            }
            int third = in.read();
            int fourth = in.read();
            if (third < 0 || fourth < 0) {
                throw new MediaException("Truncated MPEG frame header");
            }
            int size = frameSize(first, second, third, fourth);
            if (size < 0) {
                continue;
            }
            byte[] frame = new byte[size];
            frame[0] = (byte) first;
            frame[1] = (byte) second;
            frame[2] = (byte) third;
            frame[3] = (byte) fourth;
            readFully(in, frame, 4, size - 4, "MPEG frame");
            return frame;
        }
        return null;
    }

    private static int frameSize(int first, int second, int third, int fourth) throws IOException {
        if (first != 0xff || (second & 0xe0) != 0xe0) {
            return -1;
        }
        int version = (second >>> 3) & 3;
        int layer = (second >>> 1) & 3;
        int bitrateIndex = (third >>> 4) & 15;
        int frequencyIndex = (third >>> 2) & 3;
        if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || frequencyIndex == 3) {
            return -1;
        }
        int rate = (version == 3 ? new int[] { 44100, 48000, 32000 }
            : version == 2 ? new int[] { 22050, 24000, 16000 } : new int[] { 11025, 12000, 8000 })[frequencyIndex];
        int kilobits = bitrate(version, layer, bitrateIndex);
        if (kilobits == 0) {
            return -1;
        }
        int padding = (third >>> 1) & 1;
        int size = layer == 3 ? (12 * kilobits * 1000 / rate + padding) * 4
            : (layer == 1 && version != 3 ? 72 : 144) * kilobits * 1000 / rate + padding;
        if (size < 4 || size > 4096) {
            throw new MediaException("MPEG frame exceeds limit");
        }
        return size;
    }

    private static int bitrate(int version, int layer, int index) {
        int[][] mpeg1 = { { 0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448 },
            { 0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384 },
            { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320 } };
        int[][] mpeg2 = { { 0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256 },
            { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160 },
            { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160 } };
        return (version == 3 ? mpeg1 : mpeg2)[layer == 3 ? 0 : layer == 2 ? 1 : 2][index];
    }

    private static void abort(PcmSink pcm, PcmSink sink, boolean finished, IOException failure) {
        if (finished) {
            return;
        }
        try {
            if (pcm == null) {
                sink.abort();
            } else {
                pcm.abort();
            }
        } catch (IOException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }
}
