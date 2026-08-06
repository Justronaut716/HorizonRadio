package com.horizonradio.core.model;

import java.util.Objects;

public final class SearchResult {

    private final String videoId;
    private final String title;
    private final String channel;
    private final String duration;
    private final String thumbnail;

    public SearchResult(String videoId, String title, String channel, String duration, String thumbnail) {
        this.videoId = videoId;
        this.title = title;
        this.channel = channel;
        this.duration = duration;
        this.thumbnail = thumbnail;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getTitle() {
        return title;
    }

    public String getChannel() {
        return channel;
    }

    public String getDuration() {
        return duration;
    }

    public String getThumbnail() {
        return thumbnail;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SearchResult)) {
            return false;
        }
        SearchResult that = (SearchResult) other;
        return Objects.equals(videoId, that.videoId) && Objects.equals(title, that.title)
            && Objects.equals(channel, that.channel)
            && Objects.equals(duration, that.duration)
            && Objects.equals(thumbnail, that.thumbnail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(videoId, title, channel, duration, thumbnail);
    }

    @Override
    public String toString() {
        return "SearchResult{" + "videoId='"
            + videoId
            + '\''
            + ", title='"
            + title
            + '\''
            + ", channel='"
            + channel
            + '\''
            + ", duration='"
            + duration
            + '\''
            + ", thumbnail='"
            + thumbnail
            + '\''
            + '}';
    }
}
