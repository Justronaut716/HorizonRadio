package com.horizonradio.core.audio;

/** Java-8-pure client audio state, independent of Java Sound device availability. */
public final class AudioPlayerState {

    private final ReadySender readySender;
    private boolean playing;
    private boolean awaitingResume;
    private boolean clipLoaded;
    private String currentTitle = "";
    private long positionMs;
    private float volume = 1.0f;

    public AudioPlayerState() {
        this(null);
    }

    public AudioPlayerState(ReadySender readySender) {
        this.readySender = readySender;
    }

    public synchronized void startPlayback(String title) {
        clipLoaded = true;
        playing = true;
        awaitingResume = false;
        currentTitle = title == null ? "" : title;
        positionMs = 0L;
    }

    public synchronized void markClipLoaded(String title) {
        clipLoaded = true;
        playing = false;
        awaitingResume = true;
        currentTitle = title == null ? "" : title;
        positionMs = 0L;
    }

    public void prepareLateJoin(String videoId, String title) {
        synchronized (this) {
            markClipLoaded(title);
        }
        if (readySender != null) {
            readySender.sendReady(videoId);
        }
    }

    public synchronized boolean resume(long positionMs) {
        this.positionMs = Math.max(0L, positionMs);
        awaitingResume = false;
        playing = clipLoaded;
        return clipLoaded;
    }

    public synchronized void pause(long positionMs) {
        this.positionMs = Math.max(0L, positionMs);
        awaitingResume = true;
        playing = false;
    }

    public synchronized void stop() {
        clipLoaded = false;
        playing = false;
        awaitingResume = false;
        currentTitle = "";
        positionMs = 0L;
    }

    public synchronized void playbackFinished() {
        stop();
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized boolean isAwaitingResume() {
        return awaitingResume;
    }

    public synchronized String getCurrentTitle() {
        return currentTitle;
    }

    public synchronized long getPositionMs() {
        return positionMs;
    }

    public synchronized float getVolume() {
        return volume;
    }

    public synchronized void setVolume(float value) {
        if (Float.isNaN(value) || value <= 0.0f) {
            volume = 0.0f;
        } else if (value >= 1.0f) {
            volume = 1.0f;
        } else {
            volume = value;
        }
    }

    public interface ReadySender {

        void sendReady(String videoId);
    }
}
