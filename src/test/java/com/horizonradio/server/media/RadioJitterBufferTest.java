package com.horizonradio.server.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class RadioJitterBufferTest {

    @Test
    public void waitsForTheConfiguredStartupThresholdBeforePolling() {
        RadioJitterBuffer buffer = new RadioJitterBuffer(8, 16);

        assertTrue(buffer.offer(new byte[] { 1, 2, 3, 4 }));
        assertNull(buffer.poll());

        assertTrue(buffer.offer(new byte[] { 5, 6, 7, 8 }));
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, buffer.poll());
        assertArrayEquals(new byte[] { 5, 6, 7, 8 }, buffer.poll());
        assertNull(buffer.poll());
    }

    @Test
    public void dropsOldestChunksWhenTheMaximumByteBudgetWouldOverflow() {
        RadioJitterBuffer buffer = new RadioJitterBuffer(1, 8);

        assertTrue(buffer.offer(new byte[] { 1, 2, 3, 4 }));
        assertTrue(buffer.offer(new byte[] { 5, 6, 7, 8 }));
        assertTrue(buffer.offer(new byte[] { 9, 10, 11, 12 }));

        assertArrayEquals(new byte[] { 5, 6, 7, 8 }, buffer.poll());
        assertArrayEquals(new byte[] { 9, 10, 11, 12 }, buffer.poll());
        assertNull(buffer.poll());
    }

    @Test
    public void copiesOfferedBytesAndRejectsInvalidOrClosedBuffers() {
        RadioJitterBuffer buffer = new RadioJitterBuffer(1, 8);
        byte[] offered = new byte[] { 1, 2, 3, 4 };

        assertTrue(buffer.offer(offered));
        offered[0] = 9;
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, buffer.poll());

        buffer.close();
        assertFalse(buffer.offer(new byte[] { 5, 6, 7, 8 }));
        assertNull(buffer.poll());

        try {
            new RadioJitterBuffer(0, 8);
            fail("Expected a positive startup threshold");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
