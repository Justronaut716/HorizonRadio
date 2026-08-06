package com.horizonradio.core.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioChunkAssemblyTest {

    @Test
    public void rejectsChunkBeforeChunkZero() {
        AudioChunkAssembler assembler = new AudioChunkAssembler();

        assertNull(assembler.accept(new AudioChunkAssembler.Chunk("track", "Track", 1, 2, 0L, new byte[] { 2 })));
    }

    @Test
    public void assemblesCompleteTrackInIndexOrder() {
        AudioChunkAssembler assembler = new AudioChunkAssembler();

        assertNull(assembler.accept(new AudioChunkAssembler.Chunk("track", "Track", 0, 3, 42L, new byte[] { 1, 2 })));
        assertNull(assembler.accept(new AudioChunkAssembler.Chunk("track", "Track", 2, 3, 42L, new byte[] { 5 })));
        AudioChunkAssembler.CompletedTrack result = assembler
            .accept(new AudioChunkAssembler.Chunk("track", "Track", 1, 3, 42L, new byte[] { 3, 4 }));

        assertTrue(result != null);
        assertEquals("track", result.getVideoId());
        assertEquals("Track", result.getTitle());
        assertEquals(42L, result.getStartOffsetMs());
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, result.getAudioBytes());
        assertEquals(0, assembler.getBufferedTrackCount());
    }

    @Test
    public void rejectsDuplicateWithoutCountingItTwice() {
        AudioChunkAssembler assembler = new AudioChunkAssembler();

        assertNull(assembler.accept(new AudioChunkAssembler.Chunk("track", "Track", 0, 2, 0L, new byte[] { 1 })));
        assertNull(assembler.accept(new AudioChunkAssembler.Chunk("track", "Track", 0, 2, 0L, new byte[] { 9 })));
        AudioChunkAssembler.CompletedTrack result = assembler
            .accept(new AudioChunkAssembler.Chunk("track", "Track", 1, 2, 0L, new byte[] { 2 }));
        assertTrue(result != null);
        assertArrayEquals(new byte[] { 1, 2 }, result.getAudioBytes());
        assertEquals(0, assembler.getBufferedTrackCount());
    }

    @Test
    public void chunkZeroDiscardsOlderInFlightTracks() {
        AudioChunkAssembler assembler = new AudioChunkAssembler();

        assertNull(assembler.accept(new AudioChunkAssembler.Chunk("old", "Old", 0, 2, 0L, new byte[] { 1 })));
        assertNull(assembler.accept(new AudioChunkAssembler.Chunk("new", "New", 0, 2, 0L, new byte[] { 2 })));
        assertNull(assembler.accept(new AudioChunkAssembler.Chunk("old", "Old", 1, 2, 0L, new byte[] { 3 })));
        AudioChunkAssembler.CompletedTrack result = assembler
            .accept(new AudioChunkAssembler.Chunk("new", "New", 1, 2, 0L, new byte[] { 4 }));

        assertTrue(result != null);
        assertEquals("new", result.getVideoId());
        assertArrayEquals(new byte[] { 2, 4 }, result.getAudioBytes());
    }

    @Test
    public void preservesLateJoinSentinel() {
        AudioChunkAssembler assembler = new AudioChunkAssembler();

        // A one-chunk track completes at chunk zero; the result is the value under test.
        AudioChunkAssembler.CompletedTrack result = assembler
            .accept(new AudioChunkAssembler.Chunk("late", "Late", 0, 1, -1L, new byte[] { 7 }));

        assertTrue(result != null);
        assertTrue(result.isLateJoin());
        assertEquals(-1L, result.getStartOffsetMs());
        assertArrayEquals(new byte[] { 7 }, result.getAudioBytes());
    }
}
