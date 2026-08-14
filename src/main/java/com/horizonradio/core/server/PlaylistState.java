package com.horizonradio.core.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;

/**
 * Java-8-only playlist and late-join state. Minecraft and network side effects
 * stay in {@link PlaylistManager}; callers mutate this state on the server
 * thread.
 */
public final class PlaylistState {

    private static final long DEFAULT_TRACK_DURATION_MS = 3L * 60L * 1000L;
    private final CopyOnWriteArrayList<PlaylistEntry> playlist = new CopyOnWriteArrayList<PlaylistEntry>();
    private final int maxPlaylistSize;
    private final Set<UUID> pendingPlayers = new HashSet<UUID>();

    private int currentIndex = -1;
    private boolean playing;
    private boolean paused;
    private boolean looping;
    private boolean shuffling;
    private boolean previousRestarted;
    private PlaylistEntry lastTrack;
    private MediaSourceType currentSourceType;
    private String currentSourceId;
    private long queueRevision;
    private long playbackStartTime;
    private long currentTrackDurationMs;

    private boolean syncing;
    private long pausedPositionMs;
    private long pauseStartTime;

    public PlaylistState(int maxPlaylistSize) {
        this.maxPlaylistSize = maxPlaylistSize;
    }

    public boolean add(PlaylistEntry entry) {
        if (entry == null || playlist.size() >= maxPlaylistSize || (entry.isFinite() && entry.getDurationMs() <= 0L)) {
            return false;
        }
        playlist.add(entry);
        markQueueMutation();
        return true;
    }

    public List<PlaylistEntry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<PlaylistEntry>(playlist));
    }

    public PlaylistEntry get(int index) {
        return playlist.get(index);
    }

    public int size() {
        return playlist.size();
    }

    int findOwnedIndex(String videoId, String playerName) {
        if (videoId == null || playerName == null) {
            return -1;
        }
        for (int index = 0; index < playlist.size(); index++) {
            PlaylistEntry entry = playlist.get(index);
            if (entry.isFinite() && videoId.equals(entry.getSourceId()) && playerName.equals(entry.getAddedBy())) {
                return index;
            }
        }
        return -1;
    }

    public int findIndex(String videoId) {
        return findIndex(MediaSourceType.YOUTUBE, videoId);
    }

    public int findIndex(MediaSourceType sourceType, String sourceId) {
        if (sourceType == null || sourceId == null) {
            return -1;
        }
        for (int index = 0; index < playlist.size(); index++) {
            if (sourceType == playlist.get(index)
                .getSourceType() && sourceId.equals(
                    playlist.get(index)
                        .getSourceId())) {
                return index;
            }
        }
        return -1;
    }

    int removeOwned(String videoId, String playerName) {
        int index = findOwnedIndex(videoId, playerName);
        return removeAt(index);
    }

    public int remove(String videoId) {
        return removeAt(findIndex(videoId));
    }

    private int removeAt(int index) {
        if (index < 0) {
            return -1;
        }
        PlaylistEntry removed = playlist.remove(index);
        markQueueMutation();
        if (index < currentIndex) {
            currentIndex--;
        } else if (index == currentIndex) {
            currentIndex--;
            playing = false;
            paused = false;
            lastTrack = removed;
            currentSourceType = null;
            currentSourceId = null;
            playbackStartTime = 0L;
            currentTrackDurationMs = 0L;
        }
        return index;
    }

    public PlaylistEntry removeCurrent() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return null;
        }
        PlaylistEntry removed = playlist.remove(currentIndex);
        markQueueMutation();
        lastTrack = removed;
        currentIndex--;
        playing = false;
        paused = false;
        currentSourceType = null;
        currentSourceId = null;
        playbackStartTime = 0L;
        currentTrackDurationMs = 0L;
        return removed;
    }

    public PlaylistEntry prepareImmediatePlayback(PlaylistEntry requested) {
        if (requested == null) {
            return null;
        }
        validateServerQueueEntry(requested);

        int selectedIndex = findIndex(requested.getSourceType(), requested.getSourceId());
        PlaylistEntry selected = selectedIndex >= 0 ? playlist.get(selectedIndex) : requested;
        boolean selectedCurrent = selectedIndex == currentIndex;

        if (selectedIndex >= 0) {
            playlist.remove(selectedIndex);
            if (selectedIndex < currentIndex) {
                currentIndex--;
            }
        }

        if (!selectedCurrent && currentIndex >= 0 && currentIndex < playlist.size()) {
            lastTrack = playlist.remove(currentIndex);
            currentIndex--;
        }

        evictFrontForImmediateSelection();
        playlist.add(0, selected);
        if (currentIndex >= 0) {
            currentIndex++;
        }
        markQueueMutation();
        resetPlayback();
        return selected;
    }

    public boolean selectRadioAtFront(PlaylistEntry station) {
        if (!canSelectRadioAtFront(station)) {
            return false;
        }

        boolean replacesFrontRadio = !playlist.isEmpty() && playlist.get(0)
            .isRadio();
        if (replacesFrontRadio) {
            playlist.set(0, station);
        } else {
            evictFrontForImmediateSelection();
            playlist.add(0, station);
        }
        markQueueMutation();
        startRadioTrack(0, station.getSourceId());
        return true;
    }

    public boolean canSelectRadioAtFront(PlaylistEntry station) {
        if (station == null || !station.isRadio()) {
            return false;
        }
        return true;
    }

    private void evictFrontForImmediateSelection() {
        if (playlist.isEmpty() || playlist.size() < maxPlaylistSize) {
            return;
        }
        playlist.remove(0);
        if (currentIndex > 0) {
            currentIndex--;
        } else if (currentIndex == 0) {
            currentIndex = -1;
        }
    }

    public boolean moveQueued(int fromIndex, int targetIndex) {
        if (fromIndex < 0 || fromIndex >= playlist.size()
            || targetIndex < 0
            || targetIndex >= playlist.size()
            || fromIndex == targetIndex) {
            return false;
        }
        if (currentIndex >= 0 && (fromIndex <= currentIndex || targetIndex <= currentIndex)) {
            return false;
        }

        PlaylistEntry entry = playlist.remove(fromIndex);
        playlist.add(targetIndex, entry);
        markQueueMutation();
        return true;
    }

    public PlaylistEntry advanceToNext(long durationMs) {
        currentIndex++;
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            resetPlayback();
            return null;
        }

        PlaylistEntry next = playlist.get(currentIndex);
        if (next.isFinite() && durationMs <= 0L) {
            currentIndex--;
            throw new IllegalArgumentException("finite track duration must be positive");
        }

        playing = true;
        paused = false;
        previousRestarted = false;
        PlaylistEntry current = next;
        currentSourceType = current.getSourceType();
        currentSourceId = current.getSourceId();
        playbackStartTime = 0L;
        currentTrackDurationMs = current.isFinite() ? durationMs : 0L;
        return playlist.get(currentIndex);
    }

    public void updateCurrentTrackDuration(long durationMs) {
        if (durationMs > 0L) {
            currentTrackDurationMs = durationMs;
        }
    }

    public void startFiniteTrack(int index, String sourceId, long durationMs, long startAtMs) {
        if (durationMs <= 0L) {
            throw new IllegalArgumentException("finite track duration must be positive");
        }
        requireEntry(index, MediaSourceType.YOUTUBE, sourceId);
        currentIndex = index;
        playing = true;
        paused = false;
        previousRestarted = false;
        currentSourceType = MediaSourceType.YOUTUBE;
        currentSourceId = sourceId;
        currentTrackDurationMs = durationMs;
        playbackStartTime = startAtMs;
    }

    public void startRadioTrack(int index, String stationUuid) {
        requireEntry(index, MediaSourceType.RADIO, stationUuid);
        currentIndex = index;
        playing = true;
        paused = false;
        previousRestarted = false;
        currentSourceType = MediaSourceType.RADIO;
        currentSourceId = stationUuid;
        currentTrackDurationMs = 0L;
        playbackStartTime = 0L;
    }

    @Deprecated
    public void startTrack(int index, String videoId, long durationMs, long startTimeMs) {
        startFiniteTrack(index, videoId, durationMs, startTimeMs);
    }

    public void markLoaded(String videoId, long startTimeMs) {
        if (currentSourceType != MediaSourceType.YOUTUBE) {
            return;
        }
        currentSourceId = videoId;
        playbackStartTime = startTimeMs;
    }

    public long pausePlayback(long positionMs, long nowMs) {
        if (!playing || currentSourceType != MediaSourceType.YOUTUBE
            || currentSourceId == null
            || currentTrackDurationMs <= 0L) {
            return -1L;
        }
        long maximumPosition = Math.max(0L, currentTrackDurationMs - 1L);
        long safePosition = Math.max(0L, Math.min(maximumPosition, positionMs));
        paused = true;
        pausedPositionMs = safePosition;
        pauseStartTime = nowMs;
        return safePosition;
    }

    public long resumePlayback(long nowMs) {
        if (!paused) {
            return -1L;
        }
        long pauseDuration = Math.max(0L, nowMs - pauseStartTime);
        playbackStartTime += pauseDuration;
        paused = false;
        return pausedPositionMs;
    }

    public long seek(long positionMs, long nowMs) {
        if (!playing || currentSourceType != MediaSourceType.YOUTUBE
            || currentSourceId == null
            || currentTrackDurationMs <= 0L) {
            return -1L;
        }
        long maximumPosition = Math.max(0L, currentTrackDurationMs - 1L);
        long safePosition = Math.max(0L, Math.min(maximumPosition, positionMs));
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

    public String getCurrentVideoId() {
        return currentSourceType == MediaSourceType.YOUTUBE ? currentSourceId : null;
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

    public void shuffleQueued() {
        int firstQueuedIndex = currentIndex < 0 ? 0 : currentIndex + 1;
        if (firstQueuedIndex >= playlist.size() - 1) {
            return;
        }
        List<PlaylistEntry> queued = new ArrayList<PlaylistEntry>();
        for (int index = firstQueuedIndex; index < playlist.size(); index++) {
            queued.add(playlist.get(index));
        }
        Collections.shuffle(queued, new Random());
        for (int index = 0; index < queued.size(); index++) {
            playlist.set(firstQueuedIndex + index, queued.get(index));
        }
        markQueueMutation();
    }

    public boolean wasPreviousRestarted() {
        return previousRestarted;
    }

    public void markPreviousRestarted() {
        previousRestarted = true;
    }

    public void addAtFront(PlaylistEntry entry) {
        if (entry == null) {
            return;
        }
        validateServerQueueEntry(entry);
        playlist.add(0, entry);
        markQueueMutation();
        if (currentIndex >= 0) {
            currentIndex++;
        }
    }

    public PlaylistEntry takeLastTrack() {
        PlaylistEntry previous = lastTrack;
        lastTrack = null;
        return previous;
    }

    public PlaylistEntry peekLastTrack() {
        return lastTrack;
    }

    public long getPlaybackStartTime() {
        return playbackStartTime;
    }

    public long getCurrentTrackDurationMs() {
        return currentTrackDurationMs;
    }

    public void resetPlayback() {
        currentIndex = -1;
        playing = false;
        paused = false;
        previousRestarted = false;
        currentSourceType = null;
        currentSourceId = null;
        playbackStartTime = 0L;
        currentTrackDurationMs = 0L;
    }

    /** Stops music and discards late-join state without changing the queued entries. */
    public void stopPlayback() {
        syncing = false;
        pendingPlayers.clear();
        pausedPositionMs = 0L;
        pauseStartTime = 0L;
        resetPlayback();
    }

    public boolean beginLateJoin(UUID playerUuid, long elapsedMs, long nowMs) {
        if (playerUuid == null || !playing || currentSourceType != MediaSourceType.YOUTUBE || currentSourceId == null) {
            return false;
        }

        boolean firstJoiner = !syncing;
        if (firstJoiner) {
            syncing = true;
            pausedPositionMs = elapsedMs;
            pauseStartTime = nowMs;
        }
        pendingPlayers.add(playerUuid);
        return firstJoiner;
    }

    public boolean ready(UUID playerUuid, String videoId) {
        if (!syncing || playerUuid == null
            || videoId == null
            || currentSourceType != MediaSourceType.YOUTUBE
            || currentSourceId == null
            || !currentSourceId.equals(videoId)) {
            return false;
        }
        return pendingPlayers.remove(playerUuid) && pendingPlayers.isEmpty();
    }

    public boolean disconnect(UUID playerUuid) {
        return removePending(playerUuid);
    }

    public boolean removePending(UUID playerUuid) {
        return playerUuid != null && pendingPlayers.remove(playerUuid);
    }

    public boolean containsPending(UUID playerUuid) {
        return playerUuid != null && pendingPlayers.contains(playerUuid);
    }

    public boolean forceResume() {
        if (!syncing) {
            return false;
        }
        syncing = false;
        pendingPlayers.clear();
        return true;
    }

    public boolean resume(long nowMs) {
        if (!syncing) {
            return false;
        }
        long pauseDuration = nowMs - pauseStartTime;
        if (pauseDuration > 0L) {
            playbackStartTime += pauseDuration;
        }
        syncing = false;
        pendingPlayers.clear();
        return true;
    }

    public boolean isSyncing() {
        return syncing;
    }

    public long getPausedPositionMs() {
        return pausedPositionMs;
    }

    public long getPauseStartTime() {
        return pauseStartTime;
    }

    public long getQueueRevision() {
        return queueRevision;
    }

    public void markQueueMutation() {
        if (queueRevision < Long.MAX_VALUE) {
            queueRevision++;
        }
    }

    public int getPendingPlayerCount() {
        return pendingPlayers.size();
    }

    public boolean hasPendingPlayers() {
        return !pendingPlayers.isEmpty();
    }

    int getMaxPlaylistSize() {
        return maxPlaylistSize;
    }

    public static long parseDuration(String duration) {
        if (duration == null || duration.trim()
            .length() == 0) {
            return DEFAULT_TRACK_DURATION_MS;
        }
        try {
            String[] parts = duration.split(":");
            long seconds = 0L;
            for (String part : parts) {
                if (part == null || part.trim()
                    .length() == 0) {
                    return DEFAULT_TRACK_DURATION_MS;
                }
                long value = Long.parseLong(part.trim());
                if (value < 0L) {
                    return DEFAULT_TRACK_DURATION_MS;
                }
                seconds = seconds * 60L + value;
                if (seconds < 0L) {
                    return DEFAULT_TRACK_DURATION_MS;
                }
            }
            long milliseconds = seconds * 1000L;
            return milliseconds < 0L ? DEFAULT_TRACK_DURATION_MS : milliseconds;
        } catch (NumberFormatException exception) {
            return DEFAULT_TRACK_DURATION_MS;
        }
    }

    public void clear() {
        if (!playlist.isEmpty()) {
            markQueueMutation();
        }
        playlist.clear();
        pendingPlayers.clear();
        lastTrack = null;
        looping = false;
        shuffling = false;
        syncing = false;
        resetPlayback();
    }

    private void requireEntry(int index, MediaSourceType sourceType, String sourceId) {
        if (index < 0 || index >= playlist.size()
            || sourceType != playlist.get(index)
                .getSourceType()
            || sourceId == null
            || !sourceId.equals(
                playlist.get(index)
                    .getSourceId())) {
            throw new IllegalArgumentException("current source does not match playlist entry");
        }
    }

    private void validateServerQueueEntry(PlaylistEntry entry) {
        if (entry.isFinite() && entry.getDurationMs() <= 0L) {
            throw new IllegalArgumentException("finite queue entry duration must be positive");
        }
    }
}
