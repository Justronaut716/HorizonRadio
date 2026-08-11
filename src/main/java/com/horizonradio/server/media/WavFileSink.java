package com.horizonradio.server.media;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Atomically publishes normalized PCM as a canonical 44.1 kHz stereo WAV file. */
public final class WavFileSink implements PcmSink {

    private static final int HEADER_BYTES = 44;
    interface MoveStrategy {
        void move(Path source, Path destination) throws IOException;
    }

    private final Path destination;
    private final Path temporary;
    private final long maximumPcmBytes;
    private final MoveStrategy moveStrategy;
    private OutputStream output;
    private long pcmBytes;
    private boolean finished;
    private boolean aborted;

    public WavFileSink(Path destination, long maximumPcmBytes) throws IOException {
        this(destination, maximumPcmBytes, new MoveStrategy() {
            @Override
            public void move(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    WavFileSink(Path destination, long maximumPcmBytes, MoveStrategy moveStrategy) throws IOException {
        if (destination == null || maximumPcmBytes < 4L) throw new IllegalArgumentException("WAV destination and limit are required");
        if (moveStrategy == null) throw new IllegalArgumentException("WAV move strategy is required");
        this.destination = destination;
        this.maximumPcmBytes = Math.min(maximumPcmBytes, 0xffffffffL - 36L);
        this.moveStrategy = moveStrategy;
        Path parent = destination.toAbsolutePath().getParent();
        if (parent == null) throw new IllegalArgumentException("WAV destination requires a parent directory");
        Files.createDirectories(parent);
        temporary = Files.createTempFile(parent, destination.getFileName().toString() + ".part-", ".wav");
        output = Files.newOutputStream(temporary);
        output.write(new byte[HEADER_BYTES]);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        if (finished || aborted) throw new MediaException("WAV sink is not open");
        if (data == null || offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException("Invalid PCM range");
        if (length % PcmFormat.normalized().getFrameSize() != 0) throw new MediaException("Normalized PCM must contain complete stereo frames");
        if (pcmBytes > maximumPcmBytes - length) throw new MediaException("WAV output exceeds its byte limit");
        output.write(data, offset, length);
        pcmBytes += length;
    }

    @Override
    public void finish() throws IOException {
        if (finished) return;
        if (aborted) throw new MediaException("WAV sink was aborted");
        try {
            output.close();
            output = null;
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(temporary.toFile(), "rw")) {
                writeHeader(file, pcmBytes);
            }
            moveStrategy.move(temporary, destination);
            finished = true;
        } catch (IOException exception) {
            abort();
            throw exception;
        }
    }

    @Override
    public void abort() throws IOException {
        if (finished || aborted) return;
        aborted = true;
        try { if (output != null) output.close(); } finally { output = null; Files.deleteIfExists(temporary); }
    }

    @Override
    public void close() throws IOException { if (!finished) abort(); }

    private static void writeHeader(java.io.RandomAccessFile file, long pcmBytes) throws IOException {
        file.seek(0L);
        file.writeBytes("RIFF"); writeLeInt(file, 36L + pcmBytes); file.writeBytes("WAVEfmt "); writeLeInt(file, 16L);
        writeLeShort(file, 1); writeLeShort(file, 2); writeLeInt(file, 44100L); writeLeInt(file, 176400L);
        writeLeShort(file, 4); writeLeShort(file, 16); file.writeBytes("data"); writeLeInt(file, pcmBytes);
    }
    private static void writeLeShort(java.io.RandomAccessFile file, int value) throws IOException { file.write(value); file.write(value >>> 8); }
    private static void writeLeInt(java.io.RandomAccessFile file, long value) throws IOException { for (int i = 0; i < 4; i++) file.write((int) (value >>> (i * 8))); }
}
