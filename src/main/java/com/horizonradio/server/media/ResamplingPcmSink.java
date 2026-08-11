package com.horizonradio.server.media;

import java.io.IOException;

/**
 * Converts signed 16-bit mono or stereo PCM into the server's normalized PCM
 * format while retaining only a partial input frame and one resampling frame.
 */
public final class ResamplingPcmSink implements PcmSink {

    private static final int OUTPUT_FRAME_SIZE = 4;
    private static final int OUTPUT_BUFFER_SIZE = 4096;
    private static final int OPEN = 0;
    private static final int FINISHED = 1;
    private static final int ABORTED = 2;

    private final PcmFormat inputFormat;
    private final PcmSink downstream;
    private final byte[] partialInput;
    private final byte[] outputBuffer = new byte[OUTPUT_BUFFER_SIZE];

    private int partialLength;
    private int outputLength;
    private int state = OPEN;
    private boolean hasPreviousFrame;
    private long inputFrameIndex;
    private long inputFrameCount;
    private long emittedOutputFrames;
    private double nextOutputPosition;
    private short previousLeft;
    private short previousRight;

    public ResamplingPcmSink(PcmFormat inputFormat, PcmSink downstream) {
        if (inputFormat == null) {
            throw new NullPointerException("inputFormat");
        }
        if (downstream == null) {
            throw new NullPointerException("downstream");
        }
        this.inputFormat = inputFormat;
        this.downstream = downstream;
        partialInput = new byte[inputFormat.getFrameSize()];
    }

    public ResamplingPcmSink(PcmSink downstream, PcmFormat inputFormat) {
        this(inputFormat, downstream);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        if (state != OPEN) {
            throw new IOException("PCM sink is not open");
        }
        validateRange(data, offset, length);
        if (length == 0) {
            return;
        }

        try {
            int end = offset + length;
            if (partialLength > 0) {
                int copied = Math.min(partialInput.length - partialLength, end - offset);
                System.arraycopy(data, offset, partialInput, partialLength, copied);
                partialLength += copied;
                offset += copied;
                if (partialLength == partialInput.length) {
                    consumeFrame(partialInput, 0);
                    partialLength = 0;
                }
            }

            while (offset + partialInput.length <= end) {
                consumeFrame(data, offset);
                offset += partialInput.length;
            }
            if (offset < end) {
                partialLength = end - offset;
                System.arraycopy(data, offset, partialInput, 0, partialLength);
            }
            flushOutput();
        } catch (IOException exception) {
            throw abortAfterFailure(exception);
        }
    }

    @Override
    public void finish() throws IOException {
        if (state == FINISHED) {
            return;
        }
        if (state == ABORTED) {
            throw new IOException("PCM sink was aborted");
        }
        if (partialLength != 0) {
            throw abortAfterFailure(new MediaException("Input ended with a partial PCM frame"));
        }
        try {
            flushTerminalFrames();
            flushOutput();
        } catch (IOException exception) {
            throw abortAfterFailure(exception);
        }
        try {
            downstream.finish();
            state = FINISHED;
        } catch (IOException exception) {
            throw abortAfterFailure(exception);
        }
    }

    @Override
    public void abort() throws IOException {
        if (state != OPEN) {
            return;
        }
        state = ABORTED;
        partialLength = 0;
        outputLength = 0;
        downstream.abort();
    }

    @Override
    public void close() throws IOException {
        if (state == OPEN && partialLength != 0) {
            MediaException failure = new MediaException("Input ended with a partial PCM frame");
            throw abortAfterFailure(failure);
        }
        abort();
    }

    private void consumeFrame(byte[] data, int offset) throws IOException {
        short left = readSample(data, offset);
        short right = inputFormat.getChannels() == 1 ? left : readSample(data, offset + 2);
        inputFrameCount++;
        if (inputFormat.getSampleRate() == PcmFormat.normalized().getSampleRate()) {
            writeNormalizedFrame(left, right);
            return;
        }

        if (!hasPreviousFrame) {
            previousLeft = left;
            previousRight = right;
            hasPreviousFrame = true;
            inputFrameIndex = 0L;
            nextOutputPosition = 0.0d;
            return;
        }

        inputFrameIndex++;
        while (nextOutputPosition <= inputFrameIndex) {
            double fraction = nextOutputPosition - (inputFrameIndex - 1L);
            if (fraction < 0.0d) {
                fraction = 0.0d;
            } else if (fraction > 1.0d) {
                fraction = 1.0d;
            }
            writeNormalizedFrame(
                interpolate(previousLeft, left, fraction),
                interpolate(previousRight, right, fraction));
            nextOutputPosition += (double) inputFormat.getSampleRate() / PcmFormat.normalized().getSampleRate();
        }
        previousLeft = left;
        previousRight = right;
    }

    /**
     * Endpoint policy: a finished stream contains ceil(inputFrames * 44100 /
     * inputRate) output frames. Linear interpolation uses adjacent frames while
     * streaming; the final incomplete interval holds the last complete frame.
     */
    private void flushTerminalFrames() throws IOException {
        if (!hasPreviousFrame) {
            return;
        }
        long expectedFrames = expectedOutputFrameCount();
        while (emittedOutputFrames < expectedFrames) {
            writeNormalizedFrame(previousLeft, previousRight);
        }
    }

    private long expectedOutputFrameCount() throws MediaException {
        long fullSeconds = inputFrameCount / inputFormat.getSampleRate();
        if (fullSeconds > Long.MAX_VALUE / PcmFormat.normalized().getSampleRate()) {
            throw new MediaException("PCM stream is too long to resample safely");
        }
        long completedSecondsOutput = fullSeconds * PcmFormat.normalized().getSampleRate();
        long remainderFrames = inputFrameCount % inputFormat.getSampleRate();
        long numerator = remainderFrames * (long) PcmFormat.normalized().getSampleRate();
        long remainderOutput = (numerator + inputFormat.getSampleRate() - 1L) / inputFormat.getSampleRate();
        if (completedSecondsOutput > Long.MAX_VALUE - remainderOutput) {
            throw new MediaException("PCM stream is too long to resample safely");
        }
        return completedSecondsOutput + remainderOutput;
    }

    private short readSample(byte[] data, int offset) {
        int low;
        int high;
        if (inputFormat.isLittleEndian()) {
            low = data[offset] & 0xff;
            high = data[offset + 1];
        } else {
            low = data[offset + 1] & 0xff;
            high = data[offset];
        }
        return (short) ((high << 8) | low);
    }

    private static short interpolate(short previous, short current, double fraction) {
        long value = Math.round(previous + (current - previous) * fraction);
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }

    private void writeNormalizedFrame(short left, short right) throws IOException {
        if (outputLength + OUTPUT_FRAME_SIZE > outputBuffer.length) {
            flushOutput();
        }
        writeLittleEndian(left, outputBuffer, outputLength);
        writeLittleEndian(right, outputBuffer, outputLength + 2);
        outputLength += OUTPUT_FRAME_SIZE;
        emittedOutputFrames++;
    }

    private static void writeLittleEndian(short value, byte[] data, int offset) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private void flushOutput() throws IOException {
        if (outputLength > 0) {
            downstream.write(outputBuffer, 0, outputLength);
            outputLength = 0;
        }
    }

    private IOException abortAfterFailure(IOException failure) {
        try {
            abort();
        } catch (IOException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
        return failure;
    }

    private static void validateRange(byte[] data, int offset, int length) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("Invalid buffer range");
        }
    }
}
