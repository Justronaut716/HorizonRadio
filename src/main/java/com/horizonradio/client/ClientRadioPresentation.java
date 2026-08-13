package com.horizonradio.client;

/** Client-local state rendered while a source-aware radio track is active. */
public final class ClientRadioPresentation {

    private final boolean active;
    private final long generation;
    private final String stationUuid;
    private final String stationName;
    private final String status;
    private final boolean musicMode;

    private ClientRadioPresentation(boolean active, long generation, String stationUuid, String stationName,
        String status, boolean musicMode) {
        this.active = active;
        this.generation = generation;
        this.stationUuid = stationUuid == null ? "" : stationUuid;
        this.stationName = stationName == null ? "" : stationName;
        this.status = status == null ? "" : status;
        this.musicMode = musicMode;
    }

    public static ClientRadioPresentation live(long generation, String stationUuid) {
        return active(generation, stationUuid, stationUuid, "LIVE");
    }

    public static ClientRadioPresentation active(long generation, String stationUuid, String stationName,
        String status) {
        return new ClientRadioPresentation(true, generation, stationUuid, stationName, status, false);
    }

    public static ClientRadioPresentation stopped(long generation, String status) {
        return inactive(generation, "", "", status, false);
    }

    public static ClientRadioPresentation inactive(long generation, String stationUuid, String stationName,
        String status, boolean stalled) {
        return new ClientRadioPresentation(false, generation, stationUuid, stationName, status, stalled);
    }

    public boolean isActive() { return active; }

    public long getGeneration() { return generation; }

    public String getStationUuid() { return stationUuid; }

    public String getStationName() { return stationName; }

    public String getStatus() { return status; }

    public boolean isMusicMode() { return musicMode; }
}
