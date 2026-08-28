package com.horizonradio.media.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class BoundedResponseReaderTest {

    @Test
    public void acceptsBodyAtExactByteLimitAndClosesStream() throws Exception {
        CloseTrackingInputStream input = new CloseTrackingInputStream(new byte[] { '1', '2', '3', '4' });

        assertEquals("1234", BoundedResponseReader.readUtf8(input, 4L, 4));

        assertTrue(input.closed);
    }

    @Test
    public void rejectsUnknownLengthAtLimitPlusOneAndClosesStream() throws Exception {
        CloseTrackingInputStream input = new CloseTrackingInputStream(new byte[] { '1', '2', '3', '4', '5' });

        assertLimitExceeded(input, -1L, 4);

        assertTrue(input.closed);
    }

    @Test
    public void rejectsDeclaredLengthBeforeReadingAndClosesStream() throws Exception {
        CloseTrackingInputStream input = new CloseTrackingInputStream(new byte[] { '1' });

        assertLimitExceeded(input, 5L, 4);

        assertEquals(0, input.readCalls);
        assertTrue(input.closed);
    }

    @Test
    public void appliesLimitToUtf8BytesInsteadOfCharacters() throws Exception {
        byte[] body = "界".getBytes(StandardCharsets.UTF_8);

        assertEquals("界", BoundedResponseReader.readUtf8(new ByteArrayInputStream(body), -1L, 3));
        assertLimitExceeded(new ByteArrayInputStream(body), -1L, 2);
    }

    @Test
    public void rejectsMissingInput() throws Exception {
        try {
            BoundedResponseReader.readUtf8(null, -1L, 4);
            fail("expected missing input rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("input"));
        }
    }

    @Test
    public void rejectsNonPositiveLimit() throws Exception {
        try {
            BoundedResponseReader.readUtf8(new ByteArrayInputStream(new byte[0]), -1L, 0);
            fail("expected invalid limit rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("positive"));
        }
    }

    private static void assertLimitExceeded(ByteArrayInputStream input, long declaredLength, int maximumBytes)
        throws Exception {
        try {
            BoundedResponseReader.readUtf8(input, declaredLength, maximumBytes);
            fail("expected response limit rejection");
        } catch (IOException expected) {
            assertTrue(
                expected.getMessage()
                    .contains(String.valueOf(maximumBytes)));
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private boolean closed;
        private int readCalls;

        private CloseTrackingInputStream(byte[] body) {
            super(body);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            readCalls++;
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
