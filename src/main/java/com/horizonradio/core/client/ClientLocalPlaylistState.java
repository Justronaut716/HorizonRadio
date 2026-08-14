package com.horizonradio.core.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;

/** Deterministic client-only state for a private playlist and its playback. */
public final class ClientLocalPlaylistState {

    private final ArrayList<PlaylistEntry> playlist = new ArrayList<PlaylistEntry>();
    private final int maxPlaylistSize;

    private int currentIndex = -1;
    private boolean playing;
    private boolean paused;
    private boolean looping;
    private boolean shuffling;
    private boolean previousRestarted;
    private PlaylistEntry lastTrack;
    private MediaSourceType currentSourceType;
    private String currentSourceId;
    private long playbackStartTime;
    private long pausedPositionMs;
    private long pauseStartTime;

    public ClientLocalPlaylistState(int maxPlaylistSize) {
        if (maxPlaylistSize < 0) {
            throw new IllegalArgumentException("maxPlaylistSize must not be negative");
        }
        this.maxPlaylistSize = maxPlaylistSize;
    }

    public boolean add(PlaylistEntry entry) {
        if (!isValidEntry(entry) || playlist.size() >= maxPlaylistSize
            || findIndex(entry.getSourceType(), entry.getSourceId()) >= 0) {
            return false;
        }
        playlist.add(entry);
        return true;
    }

    public List<PlaylistEntry> snapshot() {
        return new ArrayList<PlaylistEntry>(playlist);
    }

    public PlaylistEntry get(int index) {
        return playlist.get(index);
    }

    public int size() {
        return playlist.size();
    }

    public int findIndex(MediaSourceType sourceType, String sourceId) {
        if (sourceType == null || sourceId == null) {
            return -1;
        }
        for (int index = 0; index < playlist.size(); index++) {
            PlaylistEntry entry = playlist.get(index);
            if (entry.getSourceType() == sourceType && sourceId.equals(entry.getSourceId())) {
                return index;
            }
        }
        return -1;
    }

    public int remove(MediaSourceType sourceType, String sourceId) {
        int index = findIndex(sourceType, sourceId);
        if (index < 0) {
            return -1;
        }
        if (index == currentIndex) {
            removeCurrent();
        } else {
            playlist.remove(index);
            if (index < currentIndex) {
                currentIndex--;
            }
        }
        return index;
    }

    public PlaylistEntry removeCurrent() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return null;
        }
        PlaylistEntry removed = playlist.remove(currentIndex);
        if (removed.isFinite()) {
            lastTrack = removed;
        }
        resetPlayback();
        return removed;
    }

    public void clear() {
        playlist.clear();
        lastTrack = null;
        looping = false;
        shuffling = false;
        resetPlayback();
    }

    public boolean moveQueued(int fromIndex, int targetIndex) {
        if (fromIndex < 0 || fromIndex >= playlist.size() || targetIndex < 0 || targetIndex >= playlist.size()
            || fromIndex == targetIndex || (currentIndex >= 0 && (fromIndex <= currentIndex || targetIndex <= currentIndex))) {
            return false;
        }
        PlaylistEntry entry = playlist.remove(fromIndex);
        playlist.add(targetIndex, entry);
        return true;
    }

    public PlaylistEntry prepareImmediatePlayback(PlaylistEntry requested) {
        if (!isValidEntry(requested) || maxPlaylistSize == 0) {
            return null;
        }

        int selectedIndex = findIndex(requested.getSourceType(), requested.getSourceId());
        boolean selectedCurrent = selectedIndex == currentIndex;
        PlaylistEntry selected = selectedIndex >= 0 ? playlist.remove(selectedIndex) : requested;
        if (selectedIndex >= 0 && selectedIndex < currentIndex) {
            currentIndex--;
        }

        if (!selectedCurrent && currentIndex >= 0 && currentIndex < playlist.size()) {
            PlaylistEntry interrupted = playlist.remove(currentIndex);
            if (interrupted.isFinite() && interrupted != selected) {
                lastTrack = interrupted;
            }
        }
        evictFrontIfFull();
        playlist.add(0, selected);
        resetPlayback();
        return selected;
    }

    public boolean selectRadioAtFront(PlaylistEntry station) {
        if (station == null || !station.isRadio() || maxPlaylistSize == 0) {
            return false;
        }

        int existingIndex = findIndex(station.getSourceType(), station.getSourceId());
        if (existingIndex >= 0) {
            if (existingIndex == currentIndex) {
                playlist.remove(existingIndex);
                resetPlayback();
            } else {
                playlist.remove(existingIndex);
                if (existingIndex < currentIndex) {
                    currentIndex--;
                }
            }
        }
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            removeCurrent();
        }
        evictFrontIfFull();
        playlist.add(0, station);
        startRadioTrack(0);
        return true;
    }

    public boolean pauseRadioPlayback() {
        if (currentSourceType != MediaSourceType.RADIO || currentIndex < 0 || currentIndex >= playlist.size()) {
            return false;
        }
        playing = false;
        paused = false;
        return true;
    }

    public void startFiniteTrack(int index, long startAtMs) {
        PlaylistEntry entry = requireEntry(index, MediaSourceType.YOUTUBE);
        currentIndex = index;
        currentSourceType = entry.getSourceType();
        currentSourceId = entry.getSourceId();
        playbackStartTime = startAtMs;
        pausedPositionMs = 0L;
        pauseStartTime = 0L;
        playing = true;
        paused = false;
        previousRestarted = false;
    }

    public void startRadioTrack(int index) {
        PlaylistEntry entry = requireEntry(index, MediaSourceType.RADIO);
        currentIndex = index;
        currentSourceType = entry.getSourceType();
        currentSourceId = entry.getSourceId();
        playbackStartTime = 0L;
        pausedPositionMs = 0L;
        pauseStartTime = 0L;
        playing = true;
        paused = false;
        previousRestarted = false;
    }

    public long currentPositionMs(long nowMs) {
        if (!hasFiniteTrack()) {
            return -1L;
        }
        if (paused) {
            return pausedPositionMs;
        }
        return clampFinitePosition(nowMs - playbackStartTime);
    }

    public long pausePlayback(long positionMs, long nowMs) {
        if (!playing || !hasFiniteTrack()) {
            return -1L;
        }
        pausedPositionMs = clampFinitePosition(positionMs);
        pauseStartTime = nowMs;
        paused = true;
        return pausedPositionMs;
    }

    public long resumePlayback(long nowMs) {
        if (!paused || !hasFiniteTrack()) {
            return -1L;
        }
        playbackStartTime += Math.max(0L, nowMs - pauseStartTime);
        pauseStartTime = 0L;
        paused = false;
        return pausedPositionMs;
    }

    public long seek(long positionMs, long nowMs) {
        if (!playing || !hasFiniteTrack()) {
            return -1L;
        }
        long safePosition = clampFinitePosition(positionMs);
        if (paused) {
            pausedPositionMs = safePosition;
            pauseStartTime = nowMs;
        } else {
            playbackStartTime = nowMs - safePosition;
        }
        return safePosition;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public PlaylistEntry getCurrentEntry() {
        return currentIndex >= 0 && currentIndex < playlist.size() ? playlist.get(currentIndex) : null;
    }

    public MediaSourceType getCurrentSourceType() {
        return currentSourceType;
    }

    public String getCurrentSourceId() {
        return currentSourceId;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isPaused() {
        return paused;
    }

    public long getPlaybackStartTime() {
        return playbackStartTime;
    }

    public boolean toggleLooping() {
        looping = !looping;
        return looping;
    }

    public boolean isLooping() {
        return looping;
    }

    public boolean toggleShuffling() {
        shuffling = !shuffling;
        return shuffling;
    }

    public boolean isShuffling() {
        return shuffling;
    }

    public void shuffleQueued(Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random is required");
        }
        int firstQueuedIndex = currentIndex < 0 ? 0 : currentIndex + 1;
        if (firstQueuedIndex < playlist.size() - 1) {
            Collections.shuffle(playlist.subList(firstQueuedIndex, playlist.size()), random);
        }
    }

    public boolean wasPreviousRestarted() {
        return previousRestarted;
    }

    public void markPreviousRestarted() {
        previousRestarted = true;
    }

    public PlaylistEntry takeLastTrack() {
        PlaylistEntry previous = lastTrack;
        lastTrack = null;
        return previous;
    }

    public void resetPlayback() {
        currentIndex = -1;
        playing = false;
        paused = false;
        previousRestarted = false;
        currentSourceType = null;
        currentSourceId = null;
        playbackStartTime = 0L;
        pausedPositionMs = 0L;
        pauseStartTime = 0L;
    }

    private boolean isValidEntry(PlaylistEntry entry) {
        return entry != null && (!entry.isFinite() || entry.getDurationMs() > 0L);
    }

    private void evictFrontIfFull() {
        if (playlist.size() < maxPlaylistSize) {
            return;
        }
        playlist.remove(0);
        if (currentIndex > 0) {
            currentIndex--;
        } else if (currentIndex == 0) {
            resetPlayback();
        }
    }

    private PlaylistEntry requireEntry(int index, MediaSourceType sourceType) {
        if (index < 0 || index >= playlist.size() || playlist.get(index).getSourceType() != sourceType) {
            throw new IllegalArgumentException("current source does not match playlist entry");
        }
        return playlist.get(index);
    }

    private boolean hasFiniteTrack() {
        PlaylistEntry entry = getCurrentEntry();
        return currentSourceType == MediaSourceType.YOUTUBE && currentSourceId != null && entry != null && entry.isFinite()
            && entry.getDurationMs() > 0L;
    }

    private long clampFinitePosition(long positionMs) {
        PlaylistEntry entry = getCurrentEntry();
        long maximumPosition = Math.max(0L, entry.getDurationMs() - 1L);
        return Math.max(0L, Math.min(maximumPosition, positionMs));
    }
}
