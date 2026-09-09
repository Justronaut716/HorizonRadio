package com.horizonradio.core.model;

/** A YouTube playlist discovery result, separate from playable songs. */
public final class PlaylistSearchResult {

    public final String id, title, author, videoCount;

    public PlaylistSearchResult(String id, String title, String author, String videoCount) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.videoCount = videoCount;
    }

    public String url() {
        return "https://www.youtube.com/playlist?list=" + id;
    }
}
