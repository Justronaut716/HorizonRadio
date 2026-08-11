package com.horizonradio.server.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.Test;

public class BoundedInputStreamTest {

    @Test
    public void exposesExactlyTheConfiguredMaximumBytes() throws Exception {
        BoundedInputStream input = new BoundedInputStream(
            new ByteArrayInputStream(new byte[] { 0, 1, 2, 3, 4 }), 4L);
        byte[] bytes = new byte[8];

        assertEquals(4, input.read(bytes, 0, bytes.length));
        assertArrayEquals(new byte[] { 0, 1, 2, 3, 0, 0, 0, 0 }, bytes);
        assertEquals(-1, input.read());
        assertEquals(-1, input.read(bytes, 0, 1));
    }

    @Test
    public void closesTheWrappedStream() throws Exception {
        CloseTrackingInputStream wrapped = new CloseTrackingInputStream(new byte[] { 1 });
        BoundedInputStream input = new BoundedInputStream(wrapped, 1L);

        input.close();

        assertTrue(wrapped.closed);
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
