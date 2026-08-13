package com.horizonradio.core.model;

import java.util.Objects;

public final class PlaylistEntry {

    private final MediaSourceType sourceType;
    private final String sourceId;
    private final long durationMs;
    private final String addedBy;

    public PlaylistEntry(MediaSourceType sourceType, String sourceId, long durationMs, String addedBy) {
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType must not be null");
        }
        if (sourceId == null || sourceId.trim()
            .isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be empty");
        }
        if (durationMs < 0L || (sourceType == MediaSourceType.RADIO && durationMs != 0L)) {
            throw new IllegalArgumentException("invalid duration for " + sourceType + ": " + durationMs);
        }
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.durationMs = durationMs;
        this.addedBy = addedBy;
    }

    public static PlaylistEntry of(MediaSourceType sourceType, String sourceId, long durationMs, String addedBy) {
        return new PlaylistEntry(sourceType, sourceId, durationMs, addedBy);
    }

    public static PlaylistEntry youtube(String videoId, long durationMs, String addedBy) {
        return of(MediaSourceType.YOUTUBE, videoId, durationMs, addedBy);
    }

    public static PlaylistEntry radio(String stationUuid, String addedBy) {
        return of(MediaSourceType.RADIO, stationUuid, 0L, addedBy);
    }

    /** Compatibility constructor for pre-source-aware callers. */
    @Deprecated
    public PlaylistEntry(String videoId, String title, String duration, String addedBy) {
        this(MediaSourceType.YOUTUBE, videoId, legacyDurationMillis(duration), addedBy);
    }

    public MediaSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public boolean isFinite() {
        return sourceType == MediaSourceType.YOUTUBE;
    }

    public boolean isRadio() {
        return sourceType == MediaSourceType.RADIO;
    }

    /** Compatibility accessor retained until playlist protocol migration. */
    @Deprecated
    public String getVideoId() {
        return isFinite() ? sourceId : null;
    }

    /** Compatibility accessor retained until playlist protocol migration. */
    @Deprecated
    public String getTitle() {
        return sourceId;
    }

    /** Compatibility accessor retained until playlist protocol migration. */
    @Deprecated
    public String getDuration() {
        if (durationMs == 0L) {
            return "";
        }
        long totalSeconds = durationMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10L ? "0" : "") + seconds;
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
        return durationMs == that.durationMs && sourceType == that.sourceType
            && Objects.equals(sourceId, that.sourceId)
            && Objects.equals(addedBy, that.addedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceType, sourceId, durationMs, addedBy);
    }

    @Override
    public String toString() {
        return "PlaylistEntry{" + "sourceType="
            + sourceType
            + ", sourceId='"
            + sourceId
            + '\''
            + ", durationMs="
            + durationMs
            + ", addedBy='"
            + addedBy
            + '\''
            + '}';
    }

    private static long legacyDurationMillis(String duration) {
        long parsed = DurationParser.parseMillisStrict(duration);
        return parsed < 0L ? 0L : parsed;
    }
}
