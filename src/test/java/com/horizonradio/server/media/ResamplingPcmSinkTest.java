package com.horizonradio.server.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

public class ResamplingPcmSinkTest {

    @Test
    public void retainsPartialInputFramesUntilTheyAreComplete() throws Exception {
        RecordingSink downstream = new RecordingSink();
        ResamplingPcmSink sink = new ResamplingPcmSink(PcmFormat.normalized(), downstream);

        sink.write(new byte[] { 1, 0, 2 }, 0, 3);
        assertEquals(0, downstream.bytes.size());

        sink.write(new byte[] { 0 }, 0, 1);
        assertArrayEquals(new byte[] { 1, 0, 2, 0 }, downstream.bytes.toByteArray());
    }

    @Test
    public void expandsMonoSamplesToStereoWithoutChangingTheirValues() throws Exception {
        RecordingSink downstream = new RecordingSink();
        ResamplingPcmSink sink = new ResamplingPcmSink(
            new PcmFormat(44100, 1, 16, true, true), downstream);

        sink.write(new byte[] { 0x34, 0x12, (byte) 0xfe, (byte) 0xff }, 0, 4);

        assertArrayEquals(
            new byte[] { 0x34, 0x12, 0x34, 0x12, (byte) 0xfe, (byte) 0xff, (byte) 0xfe, (byte) 0xff },
            downstream.bytes.toByteArray());
    }

    @Test
    public void linearlyResamplesMonoFramesToTheNormalizedRate() throws Exception {
        RecordingSink downstream = new RecordingSink();
        ResamplingPcmSink sink = new ResamplingPcmSink(
            new PcmFormat(22050, 1, 16, true, true), downstream);

        sink.write(new byte[] { 0, 0, (byte) 0xe8, 0x03 }, 0, 4);

        assertArrayEquals(
            new byte[] {
                0, 0, 0, 0,
                (byte) 0xf4, 0x01, (byte) 0xf4, 0x01,
                (byte) 0xe8, 0x03, (byte) 0xe8, 0x03 },
            downstream.bytes.toByteArray());
    }

    @Test
    public void finishPadsAOneFrameLowRateInputToItsCeilingFrameCount() throws Exception {
        RecordingSink downstream = new RecordingSink();
        ResamplingPcmSink sink = new ResamplingPcmSink(
            new PcmFormat(22050, 1, 16, true, true), downstream);

        sink.write(new byte[] { (byte) 0xe8, 0x03 }, 0, 2);
        sink.finish();

        assertArrayEquals(
            new byte[] { (byte) 0xe8, 0x03, (byte) 0xe8, 0x03, (byte) 0xe8, 0x03, (byte) 0xe8, 0x03 },
            downstream.bytes.toByteArray());
        assertEquals(1, downstream.finishCalls);
        assertEquals(0, downstream.abortCalls);
    }

    @Test
    public void finishEmitsOneTerminalFrameForOneFrame48KhzInput() throws Exception {
        RecordingSink downstream = new RecordingSink();
        ResamplingPcmSink sink = new ResamplingPcmSink(
            new PcmFormat(48000, 2, 16, true, true), downstream);

        sink.write(new byte[] { 1, 0, 2, 0 }, 0, 4);
        sink.finish();

        assertArrayEquals(new byte[] { 1, 0, 2, 0 }, downstream.bytes.toByteArray());
    }

    @Test
    public void outputIsInvariantAcrossInputChunkBoundaries() throws Exception {
        byte[] input = new byte[] { 0, 0, (byte) 0xe8, 0x03 };
        RecordingSink wholeDownstream = new RecordingSink();
        ResamplingPcmSink whole = new ResamplingPcmSink(
            new PcmFormat(22050, 1, 16, true, true), wholeDownstream);
        whole.write(input, 0, input.length);
        whole.finish();

        RecordingSink splitDownstream = new RecordingSink();
        ResamplingPcmSink split = new ResamplingPcmSink(
            new PcmFormat(22050, 1, 16, true, true), splitDownstream);
        split.write(input, 0, 3);
        split.write(input, 3, 1);
        split.finish();

        assertArrayEquals(
            new byte[] {
                0, 0, 0, 0,
                (byte) 0xf4, 0x01, (byte) 0xf4, 0x01,
                (byte) 0xe8, 0x03, (byte) 0xe8, 0x03,
                (byte) 0xe8, 0x03, (byte) 0xe8, 0x03 },
            wholeDownstream.bytes.toByteArray());
        assertArrayEquals(wholeDownstream.bytes.toByteArray(), splitDownstream.bytes.toByteArray());
    }

    @Test
    public void closeAbortsAPartialFrameWithoutFinishingDownstream() throws Exception {
        RecordingSink downstream = new RecordingSink();
        ResamplingPcmSink sink = new ResamplingPcmSink(PcmFormat.normalized(), downstream);

        sink.write(new byte[] { 1, 0, 2 }, 0, 3);
        try {
            sink.close();
            fail("Expected a partial frame failure");
        } catch (MediaException expected) {
            assertTrue(expected.getMessage().contains("partial"));
        }

        assertEquals(0, downstream.finishCalls);
        assertEquals(1, downstream.abortCalls);
    }

    @Test
    public void downstreamWriteFailureAbortsInsteadOfFinishing() throws Exception {
        RecordingSink downstream = new RecordingSink();
        downstream.failWrites = true;
        ResamplingPcmSink sink = new ResamplingPcmSink(PcmFormat.normalized(), downstream);

        try {
            sink.write(new byte[] { 1, 0, 2, 0 }, 0, 4);
            fail("Expected downstream write failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("write"));
        }

        assertEquals(0, downstream.finishCalls);
        assertEquals(1, downstream.abortCalls);
    }

    @Test
    public void downstreamFinishFailureAbortsSoIncompleteOutputsCanBeDiscarded() throws Exception {
        RecordingSink downstream = new RecordingSink();
        downstream.failFinish = true;
        ResamplingPcmSink sink = new ResamplingPcmSink(PcmFormat.normalized(), downstream);

        sink.write(new byte[] { 1, 0, 2, 0 }, 0, 4);
        try {
            sink.finish();
            fail("Expected downstream finish failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("finish"));
        }

        assertEquals(1, downstream.finishCalls);
        assertEquals(1, downstream.abortCalls);
    }

    @Test
    public void convertsBigEndianStereoAndDownsamplesAtTheEndpointFrameCount() throws Exception {
        RecordingSink endianDownstream = new RecordingSink();
        ResamplingPcmSink endian = new ResamplingPcmSink(
            new PcmFormat(44100, 2, 16, true, false), endianDownstream);
        endian.write(new byte[] { 0x12, 0x34, (byte) 0xfe, (byte) 0xff }, 0, 4);
        endian.finish();
        assertArrayEquals(new byte[] { 0x34, 0x12, (byte) 0xff, (byte) 0xfe }, endianDownstream.bytes.toByteArray());

        RecordingSink downsampled = new RecordingSink();
        ResamplingPcmSink sink = new ResamplingPcmSink(
            new PcmFormat(88200, 1, 16, true, true), downsampled);
        sink.write(new byte[] { 0, 0, (byte) 0xe8, 0x03, (byte) 0xd0, 0x07, (byte) 0xb8, 0x0b }, 0, 8);
        sink.finish();
        assertArrayEquals(
            new byte[] { 0, 0, 0, 0, (byte) 0xd0, 0x07, (byte) 0xd0, 0x07 },
            downsampled.bytes.toByteArray());
    }

    private static final class RecordingSink implements PcmSink {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int finishCalls;
        private int abortCalls;
        private boolean failWrites;
        private boolean failFinish;

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            if (failWrites) {
                throw new IOException("write failed");
            }
            if (length % PcmFormat.normalized().getFrameSize() != 0) {
                throw new IOException("Expected complete normalized PCM frames");
            }
            bytes.write(data, offset, length);
        }

        @Override
        public void close() {
        }

        @Override
        public void finish() throws IOException {
            finishCalls++;
            if (failFinish) {
                throw new IOException("finish failed");
            }
        }

        @Override
        public void abort() {
            abortCalls++;
        }
    }
}
