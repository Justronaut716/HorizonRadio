package com.horizonradio.core.audio;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Java-8-pure state machine for assembling bounded audio packet chunks.
 * Client-only code supplies the Forge packet values and owns the resulting bytes.
 */
public final class AudioChunkAssembler {

    private static final int CHUNK_SIZE = 30 * 1024;
    private static final int MAX_CHUNKS = 4096;

    private final Map<String, TrackBuffer> buffers = new HashMap<String, TrackBuffer>();

    /**
     * Accepts one chunk. A null result means that the chunk was incomplete or rejected.
     * Chunk zero starts a new track and invalidates every older in-flight track.
     */
    public synchronized CompletedTrack accept(Chunk chunk) {
        if (!isValid(chunk)) {
            return null;
        }

        TrackBuffer buffer;
        if (chunk.getChunkIndex() == 0) {
            if (buffers.containsKey(chunk.getVideoId())) {
                // A second zero for the same in-flight track is a duplicate, not
                // permission to replace already accepted bytes.
                return null;
            }
            buffers.clear();
            buffer = new TrackBuffer(chunk.getTotalChunks(), chunk.getStartOffsetMs(), chunk.getTitle());
            buffers.put(chunk.getVideoId(), buffer);
        } else {
            buffer = buffers.get(chunk.getVideoId());
            if (buffer == null || buffer.totalChunks != chunk.getTotalChunks()
                || buffer.startOffsetMs != chunk.getStartOffsetMs()
                || !buffer.title.equals(chunk.getTitle())) {
                return null;
            }
        }

        int index = chunk.getChunkIndex();
        if (buffer.chunks[index] != null) {
            return null;
        }
        buffer.chunks[index] = chunk.getData();
        buffer.received++;
        if (buffer.received != buffer.totalChunks) {
            return null;
        }

        buffers.remove(chunk.getVideoId());
        return assemble(chunk.getVideoId(), buffer);
    }

    public synchronized void clear() {
        buffers.clear();
    }

    public synchronized int getBufferedTrackCount() {
        return buffers.size();
    }

    public synchronized boolean hasBufferedTrack(String videoId) {
        return videoId != null && buffers.containsKey(videoId);
    }

    private static CompletedTrack assemble(String videoId, TrackBuffer buffer) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] chunk : buffer.chunks) {
            output.write(chunk, 0, chunk.length);
        }
        return new CompletedTrack(videoId, buffer.title, buffer.startOffsetMs, output.toByteArray());
    }

    private static boolean isValid(Chunk chunk) {
        if (chunk == null || chunk.getVideoId() == null
            || chunk.getVideoId()
                .length() == 0
            || chunk.getTitle() == null
            || chunk.getTotalChunks() <= 0
            || chunk.getTotalChunks() > MAX_CHUNKS
            || chunk.getChunkIndex() < 0
            || chunk.getChunkIndex() >= chunk.getTotalChunks()
            || chunk.getData() == null
            || chunk.getData().length > CHUNK_SIZE) {
            return false;
        }
        return true;
    }

    public static final class Chunk {

        private final String videoId;
        private final String title;
        private final int chunkIndex;
        private final int totalChunks;
        private final long startOffsetMs;
        private final byte[] data;

        public Chunk(String videoId, String title, int chunkIndex, int totalChunks, long startOffsetMs, byte[] data) {
            this.videoId = videoId;
            this.title = title;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.startOffsetMs = startOffsetMs;
            this.data = data == null ? null : Arrays.copyOf(data, data.length);
        }

        public String getVideoId() {
            return videoId;
        }

        public String getTitle() {
            return title;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public int getTotalChunks() {
            return totalChunks;
        }

        public long getStartOffsetMs() {
            return startOffsetMs;
        }

        public byte[] getData() {
            return data == null ? null : Arrays.copyOf(data, data.length);
        }
    }

    public static final class CompletedTrack {

        private final String videoId;
        private final String title;
        private final long startOffsetMs;
        private final byte[] audioBytes;

        private CompletedTrack(String videoId, String title, long startOffsetMs, byte[] audioBytes) {
            this.videoId = videoId;
            this.title = title;
            this.startOffsetMs = startOffsetMs;
            this.audioBytes = audioBytes;
        }

        public String getVideoId() {
            return videoId;
        }

        public String getTitle() {
            return title;
        }

        public long getStartOffsetMs() {
            return startOffsetMs;
        }

        public boolean isLateJoin() {
            return startOffsetMs < 0L;
        }

        public byte[] getAudioBytes() {
            return Arrays.copyOf(audioBytes, audioBytes.length);
        }
    }

    private static final class TrackBuffer {

        private final byte[][] chunks;
        private final int totalChunks;
        private final long startOffsetMs;
        private final String title;
        private int received;

        private TrackBuffer(int totalChunks, long startOffsetMs, String title) {
            this.chunks = new byte[totalChunks][];
            this.totalChunks = totalChunks;
            this.startOffsetMs = startOffsetMs;
            this.title = title;
        }
    }
}
