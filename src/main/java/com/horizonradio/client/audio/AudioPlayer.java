package com.horizonradio.client.audio;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.SourceDataLine;

import com.horizonradio.client.HorizonRadioClient;
import com.horizonradio.core.audio.AudioChunkAssembler;
import com.horizonradio.core.audio.RadioStreamBuffer;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.RadioAudioChunkPacket;
import com.horizonradio.network.packets.RadioAudioStartPacket;

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
    private static final int LIVE_FRAME_SIZE = 4;
    /** Keeps roughly five seconds of live PCM available while Java Sound catches up. */
    private static final int MAX_LIVE_HANDOFF_PACKETS = 32;
    private static AudioPlayer instance;

    public interface SourceLineFactory {

        SourceDataLine create(AudioFormat format) throws Exception;
    }

    private static final class JavaSoundSourceLineFactory implements SourceLineFactory {

        @Override
        public SourceDataLine create(AudioFormat format) throws Exception {
            return AudioSystem.getSourceDataLine(format);
        }
    }

    private final ExecutorService executor;
    private final ExecutorService radioControlExecutor;
    private final ScheduledExecutorService resumeScheduler;
    private final SourceLineFactory sourceLineFactory;
    private final RadioStreamBuffer radioBuffer = new RadioStreamBuffer();
    private final Deque<byte[]> radioHandoff = new ArrayDeque<byte[]>(MAX_LIVE_HANDOFF_PACKETS);
    private final Object radioHandoffLock = new Object();
    private final AtomicBoolean radioDrainScheduled = new AtomicBoolean();
    private final AtomicLong radioEpoch = new AtomicLong();
    private final AudioChunkAssembler assembler = new AudioChunkAssembler();
    private final AtomicLong generation = new AtomicLong();
    private final Object stateLock = new Object();
    private final Object radioLineLock = new Object();

    private int radioHandoffDiscardBytes;
    private volatile Clip currentClip;
    private volatile SourceDataLine currentRadioLine;
    private volatile AudioFormat currentRadioFormat;
    private byte[] radioFrameRemainder = new byte[0];
    private volatile String currentTitle = "";
    private volatile boolean playing;
    private volatile boolean awaitingResume;
    private volatile boolean resumeReceived;
    private volatile boolean shuttingDown;
    private volatile long resumePositionMs;
    private volatile long resumeStartAtMs;
    private volatile String pendingLocalVideoId = "";
    private volatile long serverClockOffsetMs;
    private volatile boolean serverClockSynchronized;
    private volatile float volume = 1.0f;
    private ScheduledFuture<?> pendingResumeStart;

    private AudioPlayer() {
        this(new JavaSoundSourceLineFactory());
    }

    public AudioPlayer(SourceLineFactory sourceLineFactory) {
        this(sourceLineFactory, newAudioExecutor(), newRadioControlExecutor(), newResumeScheduler());
    }

    public AudioPlayer(SourceLineFactory sourceLineFactory, ExecutorService executor) {
        this(sourceLineFactory, executor, newRadioControlExecutor(), newResumeScheduler());
    }

    AudioPlayer(SourceLineFactory sourceLineFactory, ExecutorService executor, ExecutorService radioControlExecutor) {
        this(sourceLineFactory, executor, radioControlExecutor, newResumeScheduler());
    }

    AudioPlayer(SourceLineFactory sourceLineFactory, ExecutorService executor, ExecutorService radioControlExecutor,
        ScheduledExecutorService resumeScheduler) {
        if (sourceLineFactory == null) {
            throw new IllegalArgumentException("source line factory is required");
        }
        if (executor == null || radioControlExecutor == null || resumeScheduler == null) {
            throw new IllegalArgumentException("audio executors are required");
        }
        this.sourceLineFactory = sourceLineFactory;
        this.executor = executor;
        this.radioControlExecutor = radioControlExecutor;
        this.resumeScheduler = resumeScheduler;
    }

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
            cancelPendingResumeStart();
            pendingLocalVideoId = "";
            resumeReceived = false;
            stopRadio();
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
        final boolean lateJoin = completed.isLateJoin();
        if (lateJoin && !resumeReceived) {
            awaitingResume = true;
        }
        enqueue(new Runnable() {

            @Override
            public void run() {
                loadTrack(completed, lateJoin, requestGeneration);
            }
        });
    }

    /** Starts preparing a client-local track without waiting for its file to finish downloading. */
    public void beginLocalTrack(String videoId, long positionMs, long startAtMs, boolean paused) {
        if (videoId == null || videoId.trim()
            .length() == 0 || shuttingDown) {
            return;
        }
        final long requestGeneration;
        synchronized (stateLock) {
            generation.incrementAndGet();
            requestGeneration = generation.get();
            cancelPendingResumeStart();
            assembler.clear();
            pendingLocalVideoId = videoId;
            awaitingResume = paused;
            resumeReceived = !paused;
            resumePositionMs = Math.max(0L, positionMs);
            resumeStartAtMs = paused ? 0L : Math.max(0L, startAtMs);
            playing = false;
            currentTitle = "";
        }
        enqueue(new Runnable() {

            @Override
            public void run() {
                if (isCurrent(requestGeneration)) {
                    closeCurrentClip();
                }
            }
        });
    }

    /** Loads a completed client-local WAV and aligns it to the previously received sync timestamp. */
    public void loadLocalTrack(final String videoId, final Path filePath) {
        if (videoId == null || filePath == null || shuttingDown) {
            return;
        }
        final long requestGeneration = generation.get();
        enqueue(new Runnable() {

            @Override
            public void run() {
                if (!isCurrent(requestGeneration) || !videoId.equals(pendingLocalVideoId)) {
                    return;
                }
                try {
                    loadLocalTrackBytes(videoId, Files.readAllBytes(filePath), requestGeneration);
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "HorizonRadio could not read local audio for " + videoId, exception);
                } catch (RuntimeException exception) {
                    LOGGER.log(Level.WARNING, "HorizonRadio could not prepare local audio for " + videoId, exception);
                }
            }
        });
    }

    /** Pauses the current clip and records the server position for resume. */
    public void pause(final long positionMs) {
        final long safePosition = Math.max(0L, positionMs);
        cancelPendingResumeStart();
        awaitingResume = true;
        resumeReceived = false;
        resumePositionMs = safePosition;
        resumeStartAtMs = 0L;
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
        resume(positionMs, 0L);
    }

    /** Resumes at a shared server timestamp, catching up if the packet arrived late. */
    public void resume(final long positionMs, final long startAtMs) {
        final long safePosition = Math.max(0L, positionMs);
        final long safeStartAtMs = Math.max(0L, startAtMs);
        resumePositionMs = safePosition;
        resumeStartAtMs = safeStartAtMs;
        awaitingResume = false;
        resumeReceived = true;
        cancelPendingResumeStart();
        final long requestGeneration = generation.get();
        Clip clip = currentClip;
        if (clip != null && clip.isOpen()) {
            scheduleClipStart(requestGeneration, clip, safePosition, safeStartAtMs);
        }
    }

    /** Applies a measured server-to-client clock offset to pending playback. */
    public void updateServerClockOffset(final long offsetMs) {
        serverClockOffsetMs = offsetMs;
        serverClockSynchronized = true;
        final Clip clip = currentClip;
        if (clip == null || !resumeReceived || resumeStartAtMs <= 0L) {
            return;
        }
        final long requestGeneration = generation.get();
        enqueue(new Runnable() {

            @Override
            public void run() {
                realignCurrentResume(requestGeneration, clip);
            }
        });
    }

    public void resetServerClock() {
        serverClockOffsetMs = 0L;
        serverClockSynchronized = false;
    }

    /** Stops playback and discards all incomplete packet buffers. */
    public void stop() {
        synchronized (stateLock) {
            generation.incrementAndGet();
            cancelPendingResumeStart();
            assembler.clear();
            pendingLocalVideoId = "";
            awaitingResume = false;
            resumeReceived = false;
            resumePositionMs = 0L;
            resumeStartAtMs = 0L;
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

    /** Starts a bounded live PCM generation and invalidates finite playback. */
    public boolean startRadio(RadioAudioStartPacket packet) {
        if (packet == null || shuttingDown
            || !radioBuffer.begin(
                packet.getGeneration(),
                packet.getFirstSequence(),
                packet.getSampleRate(),
                packet.getChannels(),
                packet.getSampleSizeInBits(),
                packet.isBigEndian())) {
            return false;
        }

        final long requestEpoch = radioEpoch.incrementAndGet();
        clearRadioHandoff();
        abortCurrentRadioLine();
        currentRadioFormat = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            packet.getSampleRate(),
            packet.getSampleSizeInBits(),
            packet.getChannels(),
            packet.getChannels() * (packet.getSampleSizeInBits() / 8),
            packet.getSampleRate(),
            packet.isBigEndian());
        synchronized (stateLock) {
            generation.incrementAndGet();
            cancelPendingResumeStart();
            assembler.clear();
            pendingLocalVideoId = "";
            awaitingResume = false;
            resumeReceived = false;
            resumePositionMs = 0L;
            resumeStartAtMs = 0L;
            playing = false;
            currentTitle = "";
        }
        enqueue(new Runnable() {

            @Override
            public void run() {
                if (!isRadioCurrent(requestEpoch)) {
                    return;
                }
                closeCurrentClip();
            }
        });
        return true;
    }

    /** Buffers ordered live PCM and schedules line writes after startup readiness. */
    public void receiveRadioChunk(RadioAudioChunkPacket packet) {
        if (packet == null || shuttingDown
            || !radioBuffer.accept(packet.getGeneration(), packet.getSequence(), packet.getData())
            || !radioBuffer.isReady()) {
            return;
        }
        handoffReadyRadioPackets();
        requestRadioDrain();
    }

    private void requestRadioDrain() {
        final long requestEpoch = radioEpoch.get();
        final AudioFormat format = currentRadioFormat;
        if (shuttingDown || format == null || !hasRadioHandoff() || !radioDrainScheduled.compareAndSet(false, true)) {
            return;
        }
        if (!enqueue(new Runnable() {

            @Override
            public void run() {
                try {
                    drainRadio(requestEpoch, format);
                } finally {
                    radioDrainScheduled.set(false);
                    // A producer may have handed off data after drainRadio saw
                    // an empty queue but before the guard was released.
                    requestRadioDrain();
                }
            }
        })) {
            radioDrainScheduled.set(false);
        }
    }

    /** Stops the live source without altering finite music state or volume. */
    public void stopRadio() {
        radioEpoch.incrementAndGet();
        radioBuffer.clear();
        clearRadioHandoff();
        currentRadioFormat = null;
        abortCurrentRadioLine();
    }

    /** Stops radio and forgets generation history after the server connection ends. */
    public void resetRadio() {
        radioEpoch.incrementAndGet();
        radioBuffer.reset();
        clearRadioHandoff();
        currentRadioFormat = null;
        abortCurrentRadioLine();
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
        final SourceDataLine radioLine = currentRadioLine;
        if (clip != null && clip.isOpen()) {
            enqueue(new Runnable() {

                @Override
                public void run() {
                    if (clip != null && clip.isOpen()) {
                        applyVolume(clip);
                    }
                }
            });
        }
        if (radioLine != null) {
            enqueueRadioControl(new Runnable() {

                @Override
                public void run() {
                    if (radioLine.isOpen()) {
                        applyVolume(radioLine);
                    }
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
            cancelPendingResumeStart();
            radioEpoch.incrementAndGet();
            assembler.clear();
            radioBuffer.reset();
            clearRadioHandoff();
            currentRadioFormat = null;
            awaitingResume = false;
            resumeReceived = false;
            resumeStartAtMs = 0L;
            playing = false;
            currentTitle = "";
        }
        abortCurrentRadioLine();
        try {
            executor.execute(new Runnable() {

                @Override
                public void run() {
                    closeCurrentClip();
                    closeCurrentRadioLine();
                }
            });
        } catch (RuntimeException exception) {
            LOGGER.log(Level.FINE, "HorizonRadio audio cleanup task was rejected during shutdown", exception);
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1L, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
            executor.shutdownNow();
        }
        radioControlExecutor.shutdown();
        try {
            if (!radioControlExecutor.awaitTermination(1L, TimeUnit.SECONDS)) {
                radioControlExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
            radioControlExecutor.shutdownNow();
        }
        resumeScheduler.shutdownNow();
    }

    private void drainRadio(long requestEpoch, AudioFormat format) {
        if (!isRadioCurrent(requestEpoch) || format == null) {
            return;
        }

        SourceDataLine line = currentRadioLine;
        SourceDataLine candidate = null;
        try {
            if (line == null) {
                candidate = sourceLineFactory.create(format);
                candidate.open(format);
                applyVolume(candidate);
                candidate.start();
                if (!isRadioCurrent(requestEpoch)) {
                    closeSourceLine(candidate);
                    return;
                }
                synchronized (radioLineLock) {
                    if (isRadioCurrent(requestEpoch) && currentRadioLine == null) {
                        currentRadioLine = candidate;
                        line = candidate;
                        candidate = null;
                    }
                }
                if (candidate != null) {
                    closeSourceLine(candidate);
                    return;
                }
            }

            while (isRadioCurrent(requestEpoch)) {
                byte[] frames = pollRadioFrames(format);
                if (frames == null) {
                    return;
                }
                writeRadioFrames(line, frames, requestEpoch);
            }
        } catch (Exception exception) {
            closeSourceLine(candidate);
            if (isRadioCurrent(requestEpoch)) {
                radioBuffer.clear();
                clearRadioHandoff();
                closeCurrentRadioLine();
                LOGGER.log(Level.WARNING, "HorizonRadio live playback is unavailable", exception);
            }
        }
    }

    private void handoffReadyRadioPackets() {
        byte[] data;
        while ((data = radioBuffer.poll()) != null) {
            synchronized (radioHandoffLock) {
                if (radioHandoff.size() == MAX_LIVE_HANDOFF_PACKETS) {
                    byte[] removed = radioHandoff.removeFirst();
                    int discardedPrefixBytes = radioFrameRemainder.length + removed.length;
                    radioFrameRemainder = new byte[0];
                    radioHandoffDiscardBytes += (LIVE_FRAME_SIZE - discardedPrefixBytes % LIVE_FRAME_SIZE)
                        % LIVE_FRAME_SIZE;
                    discardRadioHandoffPrefixLocked();
                }
                if (radioHandoffDiscardBytes > 0) {
                    int discarded = Math.min(radioHandoffDiscardBytes, data.length);
                    radioHandoffDiscardBytes -= discarded;
                    data = Arrays.copyOfRange(data, discarded, data.length);
                }
                if (data.length > 0) {
                    radioHandoff.addLast(data);
                }
            }
        }
    }

    private void discardRadioHandoffPrefixLocked() {
        while (radioHandoffDiscardBytes > 0 && !radioHandoff.isEmpty()) {
            byte[] first = radioHandoff.removeFirst();
            if (first.length <= radioHandoffDiscardBytes) {
                radioHandoffDiscardBytes -= first.length;
            } else {
                radioHandoff.addFirst(Arrays.copyOfRange(first, radioHandoffDiscardBytes, first.length));
                radioHandoffDiscardBytes = 0;
            }
        }
    }

    private byte[] pollRadioFrames(AudioFormat format) {
        synchronized (radioHandoffLock) {
            byte[] data = radioHandoff.pollFirst();
            if (data == null) {
                return null;
            }

            byte[] framed = data;
            if (radioFrameRemainder.length > 0) {
                framed = new byte[radioFrameRemainder.length + data.length];
                System.arraycopy(radioFrameRemainder, 0, framed, 0, radioFrameRemainder.length);
                System.arraycopy(data, 0, framed, radioFrameRemainder.length, data.length);
            }

            int writableBytes = framed.length - framed.length % format.getFrameSize();
            radioFrameRemainder = Arrays.copyOfRange(framed, writableBytes, framed.length);
            return Arrays.copyOf(framed, writableBytes);
        }
    }

    private boolean hasRadioHandoff() {
        synchronized (radioHandoffLock) {
            return !radioHandoff.isEmpty();
        }
    }

    private void clearRadioHandoff() {
        synchronized (radioHandoffLock) {
            radioHandoff.clear();
            radioHandoffDiscardBytes = 0;
            radioFrameRemainder = new byte[0];
        }
    }

    private void writeRadioFrames(SourceDataLine line, byte[] frames, long requestEpoch) {
        int offset = 0;
        while (offset < frames.length && isRadioCurrent(requestEpoch)) {
            int written = line.write(frames, offset, frames.length - offset);
            if (written <= 0 || written % LIVE_FRAME_SIZE != 0) {
                throw new IllegalStateException("live audio line did not consume complete PCM frames");
            }
            offset += written;
        }
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
                        scheduleClipStart(requestGeneration, loadedClip, resumePositionMs, resumeStartAtMs);
                    }
                } else {
                    safeSeek(loadedClip, track.getStartOffsetMs());
                    awaitingResume = false;
                    resumeReceived = true;
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

    private void loadLocalTrackBytes(String videoId, byte[] audioBytes, long requestGeneration) {
        if (!isCurrent(requestGeneration) || !videoId.equals(pendingLocalVideoId)
            || audioBytes == null
            || audioBytes.length == 0) {
            return;
        }

        Clip clip = null;
        try {
            clip = createClip(audioBytes);
            if (!isCurrent(requestGeneration) || !videoId.equals(pendingLocalVideoId)) {
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
                if (!isCurrent(requestGeneration) || !videoId.equals(pendingLocalVideoId)) {
                    closeClip(loadedClip);
                    return;
                }
                closeCurrentClip();
                currentClip = loadedClip;
                currentTitle = "";
                applyVolume(loadedClip);
                if (awaitingResume || !resumeReceived) {
                    playing = false;
                } else {
                    scheduleClipStart(requestGeneration, loadedClip, resumePositionMs, resumeStartAtMs);
                }
            }
            clip = null;
        } catch (Exception exception) {
            closeClip(clip);
            if (isCurrent(requestGeneration)) {
                playing = false;
                currentTitle = "";
                LOGGER.log(Level.WARNING, "HorizonRadio local audio playback is unavailable for " + videoId, exception);
            }
        }
    }

    private void scheduleClipStart(final long requestGeneration, final Clip clip, final long positionMs,
        final long startAtMs) {
        cancelPendingResumeStart();
        long localStartAtMs = localStartAtMs(startAtMs);
        long delayMs = localStartAtMs <= 0L ? 0L : Math.max(0L, localStartAtMs - System.currentTimeMillis());
        Runnable startTask = new Runnable() {

            @Override
            public void run() {
                synchronized (AudioPlayer.this) {
                    pendingResumeStart = null;
                }
                enqueue(new Runnable() {

                    @Override
                    public void run() {
                        if (!isCurrent(requestGeneration) || clip != currentClip
                            || clip == null
                            || !clip.isOpen()
                            || awaitingResume
                            || !isResumeScheduleCurrent(localStartAtMs, localStartAtMs(startAtMs))) {
                            return;
                        }
                        safeSeek(clip, synchronizedPositionMs(positionMs, localStartAtMs, System.currentTimeMillis()));
                        playing = true;
                        clip.start();
                    }
                });
            }
        };

        if (delayMs <= 0L) {
            startTask.run();
            return;
        }
        synchronized (this) {
            if (!isCurrent(requestGeneration)) {
                return;
            }
            try {
                pendingResumeStart = resumeScheduler.schedule(startTask, delayMs, TimeUnit.MILLISECONDS);
            } catch (RuntimeException exception) {
                LOGGER.log(Level.FINE, "HorizonRadio synchronized playback start was rejected", exception);
            }
        }
    }

    private void realignCurrentResume(final long requestGeneration, final Clip clip) {
        if (!isCurrent(requestGeneration) || clip != currentClip
            || clip == null
            || !clip.isOpen()
            || awaitingResume
            || !resumeReceived) {
            return;
        }
        cancelPendingResumeStart();
        long localStartAtMs = localStartAtMs(resumeStartAtMs);
        long delayMs = Math.max(0L, localStartAtMs - System.currentTimeMillis());
        if (delayMs > 0L) {
            if (playing) {
                playing = false;
                clip.stop();
            }
            scheduleClipStart(requestGeneration, clip, resumePositionMs, resumeStartAtMs);
            return;
        }
        safeSeek(clip, synchronizedPositionMs(resumePositionMs, localStartAtMs, System.currentTimeMillis()));
        playing = true;
        clip.start();
    }

    private long localStartAtMs(long serverStartAtMs) {
        return !serverClockSynchronized ? serverStartAtMs
            : PlaybackClock.clientTimeForServerTime(serverStartAtMs, serverClockOffsetMs);
    }

    static boolean isResumeScheduleCurrent(long scheduledLocalStartAtMs, long currentLocalStartAtMs) {
        return scheduledLocalStartAtMs == currentLocalStartAtMs;
    }

    private synchronized void cancelPendingResumeStart() {
        if (pendingResumeStart != null) {
            pendingResumeStart.cancel(false);
            pendingResumeStart = null;
        }
    }

    static long synchronizedPositionMs(long positionMs, long startAtMs, long nowMs) {
        long safePosition = Math.max(0L, positionMs);
        long elapsed = startAtMs <= 0L ? 0L : Math.max(0L, nowMs - startAtMs);
        return safePosition + elapsed;
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

    private boolean enqueue(Runnable task) {
        if (shuttingDown) {
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.log(Level.FINE, "HorizonRadio audio task was rejected during shutdown", exception);
            return false;
        }
    }

    private boolean enqueueRadioControl(Runnable task) {
        if (shuttingDown) {
            return false;
        }
        try {
            radioControlExecutor.execute(task);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.log(Level.FINE, "HorizonRadio radio control task was rejected during shutdown", exception);
            return false;
        }
    }

    private boolean isCurrent(long requestGeneration) {
        return !shuttingDown && generation.get() == requestGeneration;
    }

    private boolean isRadioCurrent(long requestEpoch) {
        return !shuttingDown && radioEpoch.get() == requestEpoch;
    }

    private void closeCurrentClip() {
        Clip clip = currentClip;
        currentClip = null;
        closeClip(clip);
    }

    private void closeCurrentRadioLine() {
        SourceDataLine line = detachCurrentRadioLine();
        closeSourceLine(line);
    }

    private SourceDataLine detachCurrentRadioLine() {
        synchronized (radioLineLock) {
            SourceDataLine line = currentRadioLine;
            currentRadioLine = null;
            return line;
        }
    }

    private void abortCurrentRadioLine() {
        final SourceDataLine line = detachCurrentRadioLine();
        if (line == null) {
            return;
        }
        try {
            radioControlExecutor.execute(new Runnable() {

                @Override
                public void run() {
                    closeSourceLine(line);
                }
            });
        } catch (RuntimeException exception) {
            closeSourceLine(line);
        }
    }

    private static void closeSourceLine(SourceDataLine line) {
        if (line == null) {
            return;
        }
        try {
            line.stop();
        } catch (RuntimeException ignored) {
            // A provider may already have closed the line.
        }
        try {
            line.flush();
        } catch (RuntimeException ignored) {
            // Cleanup must not crash the client.
        }
        try {
            line.close();
        } catch (RuntimeException ignored) {
            // Cleanup must not crash the client.
        }
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

    private void applyVolume(javax.sound.sampled.Line line) {
        try {
            javax.sound.sampled.FloatControl control = (javax.sound.sampled.FloatControl) line
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

    private static ExecutorService newAudioExecutor() {
        return newSingleDaemonExecutor("HorizonRadio-Audio");
    }

    private static ExecutorService newRadioControlExecutor() {
        return newSingleDaemonExecutor("HorizonRadio-Audio-Control");
    }

    private static ScheduledExecutorService newResumeScheduler() {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "HorizonRadio-Audio-Start");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private static ExecutorService newSingleDaemonExecutor(final String name) {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        });
    }
}
