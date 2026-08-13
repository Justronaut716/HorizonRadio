package com.horizonradio.core.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.network.packets.PlaylistDeltaPacket;

/** Authoritative, revisioned client view of the server playlist. */
public final class ClientQueueState {

    private long revision;
    private boolean shuffling;
    private boolean looping;
    private boolean snapshotRequired;
    private List<PlaylistEntry> entries = new ArrayList<PlaylistEntry>();

    public void applySnapshot(long revision, boolean shuffling, boolean looping, List<PlaylistEntry> entries) {
        if (revision < 0L || entries == null) {
            throw new IllegalArgumentException("invalid playlist snapshot");
        }
        this.revision = revision;
        this.shuffling = shuffling;
        this.looping = looping;
        this.entries = new ArrayList<PlaylistEntry>(entries);
        snapshotRequired = false;
    }

    public boolean applyDelta(PlaylistDeltaPacket delta) {
        if (snapshotRequired || delta == null || delta.getQueueRevision() != revision + 1L) {
            snapshotRequired = true;
            return false;
        }
        List<PlaylistEntry> candidate = new ArrayList<PlaylistEntry>(entries);
        try {
            switch (delta.getOperation()) {
                case ADD:
                    if (delta.getIndex() < 0 || delta.getIndex() > candidate.size()) {
                        return rejectDelta();
                    }
                    candidate.add(delta.getIndex(), toPlaylistEntry(delta.getEntry()));
                    break;
                case REMOVE:
                    if (delta.getIndex() < 0 || delta.getIndex() >= candidate.size()) {
                        return rejectDelta();
                    }
                    candidate.remove(delta.getIndex());
                    break;
                case MOVE:
                    if (delta.getIndex() < 0 || delta.getIndex() >= candidate.size()
                        || delta.getTargetIndex() < 0
                        || delta.getTargetIndex() >= candidate.size()) {
                        return rejectDelta();
                    }
                    PlaylistEntry entry = candidate.remove(delta.getIndex());
                    candidate.add(delta.getTargetIndex(), entry);
                    break;
                case CLEAR:
                    candidate.clear();
                    break;
                case REPLACE:
                    candidate.clear();
                    for (PlaylistDeltaPacket.Entry replacement : delta.getEntries()) {
                        candidate.add(toPlaylistEntry(replacement));
                    }
                    break;
                default:
                    return rejectDelta();
            }
        } catch (RuntimeException exception) {
            return rejectDelta();
        }
        entries = candidate;
        revision = delta.getQueueRevision();
        return true;
    }

    public boolean isSnapshotRequired() {
        return snapshotRequired;
    }

    public long getRevision() {
        return revision;
    }

    public boolean isShuffling() {
        return shuffling;
    }

    public boolean isLooping() {
        return looping;
    }

    public List<PlaylistEntry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<PlaylistEntry>(entries));
    }

    private boolean rejectDelta() {
        snapshotRequired = true;
        return false;
    }

    private static PlaylistEntry toPlaylistEntry(PlaylistDeltaPacket.Entry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("playlist delta entry is required");
        }
        return PlaylistEntry.of(entry.getSourceType(), entry.getSourceId(), 0L, entry.getAddedBy());
    }
}
