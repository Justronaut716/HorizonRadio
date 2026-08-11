package com.horizonradio.server.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.Test;

public class IcyMetadataInputStreamTest {

    @Test
    public void stripsMetadataThatIsSplitAcrossArbitraryReadsAndKeepsZeroLengthFrames() throws Exception {
        byte[] metadata = new byte[16];
        byte[] title = "StreamTitle='x';".getBytes("US-ASCII");
        System.arraycopy(title, 0, metadata, 0, title.length);
        byte[] source = join(
            new byte[] { 'A', 'B', 'C', 'D' },
            new byte[] { 1 },
            metadata,
            new byte[] { 'E', 'F', 'G', 'H' },
            new byte[] { 0 },
            new byte[] { 'I', 'J', 'K', 'L' });

        InputStream input = new IcyMetadataInputStream(new ChunkedInputStream(source, 2), 4);

        assertArrayEquals("ABCDEFGHIJKL".getBytes("US-ASCII"), readAll(input));
    }

    @Test
    public void rejectsTruncatedMetadataFramesInsteadOfReturningPartialAudio() throws Exception {
        byte[] truncated = join(new byte[] { 1, 2, 3, 4 }, new byte[] { 1 }, new byte[] { 9, 8, 7 });
        InputStream input = new IcyMetadataInputStream(new ByteArrayInputStream(truncated), 4);

        try {
            readAll(input);
            fail("Expected truncated ICY metadata to be rejected");
        } catch (MediaException expected) {
            assertTrue(
                expected.getMessage()
                    .toLowerCase()
                    .contains("metadata"));
        }
    }

    @Test
    public void closeClosesTheWrappedStreamAndPreventsFurtherReads() throws Exception {
        CloseTrackingInputStream source = new CloseTrackingInputStream(new byte[] { 1, 2, 3, 4 });
        IcyMetadataInputStream input = new IcyMetadataInputStream(source, 4);

        input.close();

        assertTrue(source.closed);
        assertEqualsEndOfStream(input);
    }

    private static void assertEqualsEndOfStream(InputStream input) throws IOException {
        assertTrue(input.read() == -1);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[3];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > 0) {
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.write(part);
        }
        return output.toByteArray();
    }

    private static final class ChunkedInputStream extends InputStream {

        private final ByteArrayInputStream delegate;
        private final int maximumRead;

        private ChunkedInputStream(byte[] bytes, int maximumRead) {
            delegate = new ByteArrayInputStream(bytes);
            this.maximumRead = maximumRead;
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, Math.min(length, maximumRead));
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
