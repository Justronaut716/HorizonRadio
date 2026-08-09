package com.horizonradio.core.server;

import com.horizonradio.core.model.RadioStation;

/**
 * Server-thread-owned publication state for the shared radio relay.
 */
public final class RadioPlaybackState {

    public enum Mode {
        IDLE,
        MUSIC,
        RADIO
    }

    private Mode mode = Mode.IDLE;
    private long generation;
    private long nextGeneration;
    private long candidateGeneration;
    private String stationUuid = "";
    private String stationName = "";
    private String status = "";

    /** Allocates a new candidate generation without changing the published source. */
    public long beginCandidate() {
        candidateGeneration = ++nextGeneration;
        return candidateGeneration;
    }

    /** Publishes the ready candidate if it is still current. */
    public boolean promoteCandidate(long candidate, RadioStation station) {
        if (!isCandidateGeneration(candidate) || station == null) {
            return false;
        }
        mode = Mode.RADIO;
        generation = candidate;
        candidateGeneration = 0L;
        stationUuid = safe(station.getStationUuid());
        stationName = safe(station.getName());
        status = "Playing " + stationName;
        return true;
    }

    /** Discards a failed candidate while preserving a previously published source. */
    public boolean failCandidate(long candidate, String failureStatus) {
        if (!isCandidateGeneration(candidate)) {
            return false;
        }
        if (mode != Mode.RADIO) {
            status = safe(failureStatus);
        }
        candidateGeneration = 0L;
        return true;
    }

    /** Invalidates the outstanding candidate without disturbing a published station. */
    public long cancelCandidate() {
        long cancelled = candidateGeneration;
        candidateGeneration = 0L;
        return cancelled;
    }

    public void stop() {
        stop("");
    }

    public void stop(String inactiveStatus) {
        mode = Mode.IDLE;
        cancelCandidate();
        status = safe(inactiveStatus);
    }

    public void startMusic() {
        mode = Mode.MUSIC;
        candidateGeneration = 0L;
        status = "";
    }

    public Mode getMode() {
        return mode;
    }

    public long getGeneration() {
        return generation;
    }

    public String getStationUuid() {
        return stationUuid;
    }

    public String getStationName() {
        return stationName;
    }

    public String getStatus() {
        return status;
    }

    public boolean isRadioActive() {
        return mode == Mode.RADIO;
    }

    public boolean isCandidateGeneration(long candidate) {
        return candidate != 0L && candidate == candidateGeneration;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
