package com.horizonradio.core.model;

import java.util.Objects;

/**
 * A validated station record from the Radio Browser directory.
 */
public final class RadioStation {

    private final String stationUuid;
    private final String name;
    private final String streamUrl;
    private final boolean lastCheckOk;
    private final boolean hls;

    public RadioStation(String stationUuid, String name, String streamUrl, boolean lastCheckOk, boolean hls) {
        this.stationUuid = stationUuid;
        this.name = name;
        this.streamUrl = streamUrl;
        this.lastCheckOk = lastCheckOk;
        this.hls = hls;
    }

    public String getStationUuid() {
        return stationUuid;
    }

    public String getName() {
        return name;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public boolean isLastCheckOk() {
        return lastCheckOk;
    }

    public boolean isHls() {
        return hls;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RadioStation)) {
            return false;
        }
        RadioStation that = (RadioStation) other;
        return lastCheckOk == that.lastCheckOk && hls == that.hls
            && Objects.equals(stationUuid, that.stationUuid)
            && Objects.equals(name, that.name)
            && Objects.equals(streamUrl, that.streamUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stationUuid, name, streamUrl, lastCheckOk, hls);
    }

    @Override
    public String toString() {
        return "RadioStation{" + "stationUuid='" + stationUuid + '\'' + ", name='" + name + '\'' + '}';
    }
}
