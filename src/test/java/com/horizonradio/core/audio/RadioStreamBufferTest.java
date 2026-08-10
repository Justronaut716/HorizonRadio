package com.horizonradio.core.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadioStreamBufferTest {

    @Test
    public void beginsOnlyWithTheFixedPcmFormat() {
        RadioStreamBuffer buffer = new RadioStreamBuffer();

        assertFalse(buffer.begin(4L, 0L, 48000, 2, 16, false));
        assertFalse(buffer.begin(4L, 0L, 44100, 1, 16, false));
        assertFalse(buffer.begin(4L, 0L, 44100, 2, 8, false));
        assertFalse(buffer.begin(4L, 0L, 44100, 2, 16, true));
        assertTrue(buffer.begin(4L, 0L, 44100, 2, 16, false));
    }

    @Test
    public void acceptsOnlyTheCurrentGenerationAndNextSequence() {
        RadioStreamBuffer buffer = new RadioStreamBuffer();
        assertTrue(buffer.begin(7L, 9L, 44100, 2, 16, false));

        assertFalse(buffer.accept(6L, 9L, new byte[] { 1 }));
        assertFalse(buffer.accept(7L, 8L, new byte[] { 1 }));
        assertTrue(buffer.accept(7L, 9L, new byte[] { 2 }));
        assertFalse(buffer.accept(7L, 9L, new byte[] { 3 }));
        assertFalse(buffer.accept(7L, 11L, new byte[] { 4 }));
        assertTrue(buffer.accept(7L, 10L, new byte[] { 5 }));
        assertArrayEquals(new byte[] { 2 }, buffer.poll());
        assertArrayEquals(new byte[] { 5 }, buffer.poll());
    }

    @Test
    public void newGenerationClearsPendingPacketsAndRestartsSequence() {
        RadioStreamBuffer buffer = new RadioStreamBuffer();
        assertTrue(buffer.begin(1L, 0L, 44100, 2, 16, false));
        assertTrue(buffer.accept(1L, 0L, new byte[] { 1 }));

        assertTrue(buffer.begin(2L, 3L, 44100, 2, 16, false));
        assertNull(buffer.poll());
        assertFalse(buffer.accept(1L, 1L, new byte[] { 2 }));
        assertTrue(buffer.accept(2L, 3L, new byte[] { 3 }));
    }

    @Test
    public void staleAndDuplicateStartsCannotClearTheActiveGeneration() {
        RadioStreamBuffer buffer = new RadioStreamBuffer();
        assertTrue(buffer.begin(7L, 10L, 44100, 2, 16, false));
        assertTrue(buffer.accept(7L, 10L, new byte[] { 1, 2, 3, 4 }));

        assertFalse(buffer.begin(7L, 20L, 44100, 2, 16, false));
        assertFalse(buffer.begin(6L, 30L, 44100, 2, 16, false));

        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, buffer.poll());
        assertTrue(buffer.accept(7L, 11L, new byte[] { 5, 6, 7, 8 }));
    }

    @Test
    public void newerStartStillReplacesTheActiveGeneration() {
        RadioStreamBuffer buffer = new RadioStreamBuffer();
        assertTrue(buffer.begin(7L, 10L, 44100, 2, 16, false));
        assertTrue(buffer.accept(7L, 10L, new byte[] { 1, 2, 3, 4 }));

        assertTrue(buffer.begin(8L, 20L, 44100, 2, 16, false));

        assertNull(buffer.poll());
        assertFalse(buffer.accept(7L, 11L, new byte[] { 5, 6, 7, 8 }));
        assertTrue(buffer.accept(8L, 20L, new byte[] { 9, 10, 11, 12 }));
    }

    @Test
    public void clearKeepsGenerationHighWatermarkUntilDisconnectReset() {
        RadioStreamBuffer buffer = new RadioStreamBuffer();
        assertTrue(buffer.begin(7L, 10L, 44100, 2, 16, false));

        buffer.clear();

        assertFalse(buffer.begin(7L, 20L, 44100, 2, 16, false));
        assertFalse(buffer.begin(6L, 20L, 44100, 2, 16, false));
        assertTrue(buffer.begin(8L, 20L, 44100, 2, 16, false));

        buffer.reset();

        assertTrue(buffer.begin(1L, 0L, 44100, 2, 16, false));
    }

    @Test
    public void capsPendingPacketsAtTwelveAndCopiesAcceptedAndPolledBytes() {
        RadioStreamBuffer buffer = new RadioStreamBuffer();
        assertTrue(buffer.begin(1L, 0L, 44100, 2, 16, false));
        for (long sequence = 0L; sequence < 12L; sequence++) {
            assertTrue(buffer.accept(1L, sequence, new byte[] { (byte) sequence }));
        }
        assertFalse(buffer.accept(1L, 12L, new byte[] { 12 }));
        assertTrue(buffer.isReady());

        for (long sequence = 0L; sequence < 12L; sequence++) {
            assertArrayEquals(new byte[] { (byte) sequence }, buffer.poll());
        }
        assertNull(buffer.poll());
    }

    @Test
    public void becomesReadyAfterEightPackets() {
        RadioStreamBuffer buffer = new RadioStreamBuffer();
        assertTrue(buffer.begin(1L, 0L, 44100, 2, 16, false));

        for (long sequence = 0L; sequence < 7L; sequence++) {
            assertTrue(buffer.accept(1L, sequence, new byte[] { (byte) sequence }));
            assertFalse(buffer.isReady());
        }

        assertTrue(buffer.accept(1L, 7L, new byte[] { 7 }));
        assertTrue(buffer.isReady());
    }
}
