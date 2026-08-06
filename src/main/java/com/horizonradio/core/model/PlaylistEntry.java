package com.horizonradio.core.model;

import java.util.Objects;

public final class PlaylistEntry {

    private final String videoId;
    private final String title;
    private final String duration;
    private final String addedBy;

    public PlaylistEntry(String videoId, String title, String duration, String addedBy) {
        this.videoId = videoId;
        this.title = title;
        this.duration = duration;
        this.addedBy = addedBy;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getTitle() {
        return title;
    }

    public String getDuration() {
        return duration;
    }

    public String getAddedBy() {
        return addedBy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PlaylistEntry)) {
            return false;
        }
        PlaylistEntry that = (PlaylistEntry) other;
        return Objects.equals(videoId, that.videoId) && Objects.equals(title, that.title)
            && Objects.equals(duration, that.duration)
            && Objects.equals(addedBy, that.addedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(videoId, title, duration, addedBy);
    }

    @Override
    public String toString() {
        return "PlaylistEntry{" + "videoId='"
            + videoId
            + '\''
            + ", title='"
            + title
            + '\''
            + ", duration='"
            + duration
            + '\''
            + ", addedBy='"
            + addedBy
            + '\''
            + '}';
    }
}
