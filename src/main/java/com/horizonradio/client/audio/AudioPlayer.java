package com.horizonradio.client.audio;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;

import com.horizonradio.client.HorizonRadioClient;
import com.horizonradio.core.audio.AudioChunkAssembler;
import com.horizonradio.network.packets.AudioChunkPacket;

/**
 * Client-only Java Sound playback for the Forge 1.7.10 port.
 *
 * <p>
 * All potentially blocking sound work runs on one daemon thread. Packet
 * handlers only validate/assemble data and enqueue work, so a missing sound
 * device cannot stall the Minecraft client thread.
 * </p>
 */
public final class AudioPlayer {

    private static final Logger LOGGER = Logger.getLogger(AudioPlayer.class.getName());
    private static AudioPlayer instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "HorizonRadio-Audio");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final AudioChunkAssembler assembler = new AudioChunkAssembler();
    private final AtomicLong generation = new AtomicLong();
    private final Object stateLock = new Object();

    private volatile Clip currentClip;
    private volatile String currentTitle = "";
    private volatile boolean playing;
    private volatile boolean awaitingResume;
    private volatile boolean shuttingDown;
    private volatile long resumePositionMs;
    private volatile float volume = 1.0f;

    private AudioPlayer() {}

    public static synchronized AudioPlayer getInstance() {
        if (instance == null) {
            instance = new AudioPlayer();
        }
        return instance;
    }

    /** Accepts an incoming chunk and starts or prepares the completed track. */
    public void receiveChunk(AudioChunkPacket packet) {
        if (packet == null || shuttingDown) {
            return;
        }

        if (packet.getChunkIndex() == 0 && isValidChunkZero(packet)
            && !assembler.hasBufferedTrack(packet.getVideoId())) {
            // A new zero supersedes work for the previous track. The assembler
            // still rejects duplicate zeros for the same in-flight transfer.
            generation.incrementAndGet();
        }

        AudioChunkAssembler.CompletedTrack completed = assembler.accept(
            new AudioChunkAssembler.Chunk(
                packet.getVideoId(),
                packet.getTitle(),
                packet.getChunkIndex(),
                packet.getTotalChunks(),
                packet.getStartOffsetMs(),
                packet.getData()));
        if (completed == null) {
            return;
        }

        final long requestGeneration = generation.get();
        final boolean lateJoin = awaitingResume || completed.isLateJoin();
        if (lateJoin) {
            awaitingResume = true;
        }
        enqueue(new Runnable() {

            @Override
            public void run() {
                loadTrack(completed, lateJoin, requestGeneration);
            }
        });
    }

    /** Pauses the current clip and records the server position for resume. */
    public void pause(final long positionMs) {
        final long safePosition = Math.max(0L, positionMs);
        awaitingResume = true;
        resumePositionMs = safePosition;
        playing = false;
        enqueue(new Runnable() {

            @Override
            public void run() {
                Clip clip = currentClip;
                if (clip != null && clip.isOpen()) {
                    safeSeek(clip, safePosition);
                    clip.stop();
                }
            }
        });
    }

    /** Seeks the loaded clip and starts it, if a clip is available. */
    public void resume(final long positionMs) {
        final long safePosition = Math.max(0L, positionMs);
        resumePositionMs = safePosition;
        awaitingResume = false;
        enqueue(new Runnable() {

            @Override
            public void run() {
                Clip clip = currentClip;
                if (clip != null && clip.isOpen()) {
                    safeSeek(clip, safePosition);
                    playing = true;
                    clip.start();
                }
            }
        });
    }

    /** Stops playback and discards all incomplete packet buffers. */
    public void stop() {
        synchronized (stateLock) {
            generation.incrementAndGet();
            assembler.clear();
            awaitingResume = false;
            resumePositionMs = 0L;
            playing = false;
            currentTitle = "";
        }
        enqueue(new Runnable() {

            @Override
            public void run() {
                closeCurrentClip();
            }
        });
    }

    public boolean isPlaying() {
        return playing;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public boolean isAwaitingResume() {
        return awaitingResume;
    }

    public float getProgress() {
        Clip clip = currentClip;
        if (clip == null || !clip.isOpen()) {
            return 0.0f;
        }
        try {
            long length = clip.getMicrosecondLength();
            return length <= 0L ? 0.0f : (float) clip.getMicrosecondPosition() / (float) length;
        } catch (IllegalStateException exception) {
            return 0.0f;
        }
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float value) {
        if (Float.isNaN(value) || value <= 0.0f) {
            volume = 0.0f;
        } else if (value >= 1.0f) {
            volume = 1.0f;
        } else {
            volume = value;
        }
        final Clip clip = currentClip;
        if (clip != null && clip.isOpen()) {
            enqueue(new Runnable() {

                @Override
                public void run() {
                    applyVolume(clip);
                }
            });
        }
    }

    /** Releases the singleton's sound resources when the client is shutting down. */
    public void shutdown() {
        synchronized (stateLock) {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
            generation.incrementAndGet();
            assembler.clear();
            awaitingResume = false;
            playing = false;
            currentTitle = "";
        }
        closeCurrentClip();
        executor.shutdownNow();
    }

    private void loadTrack(AudioChunkAssembler.CompletedTrack track, boolean lateJoin, long requestGeneration) {
        if (!isCurrent(requestGeneration)) {
            return;
        }

        Clip clip = null;
        try {
            clip = createClip(track.getAudioBytes());
            if (!isCurrent(requestGeneration)) {
                closeClip(clip);
                return;
            }

            final Clip loadedClip = clip;
            loadedClip.addLineListener(new LineListener() {

                @Override
                public void update(LineEvent event) {
                    if (event.getType() == LineEvent.Type.STOP && loadedClip == currentClip && playing) {
                        playing = false;
                        currentTitle = "";
                        currentClip = null;
                        try {
                            loadedClip.close();
                        } catch (RuntimeException ignored) {
                            // Cleanup must not crash the client.
                        }
                    }
                }
            });

            synchronized (stateLock) {
                // stop() can be called while createClip is blocking. Recheck
                // under the same lock used by stop before installing audio.
                if (!isCurrent(requestGeneration)) {
                    closeClip(loadedClip);
                    return;
                }
                closeCurrentClip();
                currentClip = loadedClip;
                currentTitle = track.getTitle() == null ? "" : track.getTitle();
                applyVolume(loadedClip);

                if (lateJoin) {
                    // A ResumePacket may have arrived while the clip was loading.
                    if (awaitingResume) {
                        playing = false;
                    } else {
                        safeSeek(loadedClip, resumePositionMs);
                        playing = true;
                        loadedClip.start();
                    }
                } else {
                    safeSeek(loadedClip, track.getStartOffsetMs());
                    awaitingResume = false;
                    playing = true;
                    loadedClip.start();
                }
            }
            clip = null;
        } catch (Exception exception) {
            closeClip(clip);
            if (isCurrent(requestGeneration)) {
                playing = false;
                currentTitle = "";
                LOGGER.log(
                    Level.WARNING,
                    "HorizonRadio audio playback is unavailable for " + track.getTitle(),
                    exception);
            }
        } finally {
            if (lateJoin && isCurrent(requestGeneration)) {
                // Signal readiness even when Java Sound is unavailable; the
                // server's timeout must not leave every other client paused.
                HorizonRadioClient.sendReady(track.getVideoId());
            }
        }
    }

    private Clip createClip(byte[] audioBytes) throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(audioBytes);
        AudioInputStream source = null;
        AudioInputStream playback = null;
        Clip clip = null;
        try {
            source = AudioSystem.getAudioInputStream(input);
            AudioFormat base = source.getFormat();
            if (base.getChannels() <= 0 || base.getSampleRate() <= 0.0f) {
                throw new IllegalArgumentException("audio stream has no usable PCM dimensions");
            }
            AudioFormat target = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                base.getSampleRate(),
                16,
                base.getChannels(),
                base.getChannels() * 2,
                base.getSampleRate(),
                false);
            playback = base.matches(target) ? source : AudioSystem.getAudioInputStream(target, source);
            clip = AudioSystem.getClip();
            clip.open(playback);
            return clip;
        } catch (Exception exception) {
            closeClip(clip);
            throw exception;
        } finally {
            closeStream(playback);
            if (playback != source) {
                closeStream(source);
            }
            try {
                input.close();
            } catch (IOException ignored) {
                // ByteArrayInputStream.close() is a no-op.
            }
        }
    }

    private static boolean isValidChunkZero(AudioChunkPacket packet) {
        if (packet.getVideoId() == null || packet.getVideoId()
            .length() == 0
            || packet.getTitle() == null
            || packet.getTotalChunks() <= 0
            || packet.getTotalChunks() > AudioChunkPacket.MAX_CHUNKS
            || packet.getChunkIndex() != 0) {
            return false;
        }
        byte[] data = packet.getData();
        return data != null && data.length <= AudioChunkPacket.CHUNK_SIZE;
    }

    private void enqueue(Runnable task) {
        if (shuttingDown) {
            return;
        }
        try {
            executor.execute(task);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.FINE, "HorizonRadio audio task was rejected during shutdown", exception);
        }
    }

    private boolean isCurrent(long requestGeneration) {
        return !shuttingDown && generation.get() == requestGeneration;
    }

    private void closeCurrentClip() {
        Clip clip = currentClip;
        currentClip = null;
        closeClip(clip);
    }

    private static void closeClip(Clip clip) {
        if (clip == null) {
            return;
        }
        try {
            clip.stop();
        } catch (RuntimeException ignored) {
            // A provider may already have closed the line.
        }
        try {
            clip.close();
        } catch (RuntimeException ignored) {
            // Cleanup must not crash the client.
        }
    }

    private static void closeStream(AudioInputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Cleanup must not crash the client.
            }
        }
    }

    private static void safeSeek(Clip clip, long positionMs) {
        if (clip == null || !clip.isOpen()) {
            return;
        }
        long safePosition = Math.max(0L, positionMs);
        long lengthMs = clip.getMicrosecondLength() / 1000L;
        if (lengthMs > 0L) {
            safePosition = Math.min(safePosition, Math.max(0L, lengthMs - 1L));
        }
        clip.setMicrosecondPosition(safePosition * 1000L);
    }

    private void applyVolume(Clip clip) {
        try {
            javax.sound.sampled.FloatControl control = (javax.sound.sampled.FloatControl) clip
                .getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
            float decibels = (float) (20.0d * Math.log10(Math.max(volume, 0.0001f)));
            decibels = Math.max(control.getMinimum(), Math.min(control.getMaximum(), decibels));
            control.setValue(decibels);
        } catch (IllegalArgumentException exception) {
            // Some sound providers do not expose MASTER_GAIN.
        } catch (IllegalStateException exception) {
            // The line may have closed while the volume was being applied.
        }
    }
}
