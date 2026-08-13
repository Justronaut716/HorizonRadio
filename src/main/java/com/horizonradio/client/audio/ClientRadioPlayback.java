package com.horizonradio.client.audio;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.server.media.RadioInputSession;

/** Resolves a selected station locally and streams its normalized PCM to the local audio player. */
public final class ClientRadioPlayback {

    private final StationResolver stationResolver;
    private final SessionFactory sessionFactory;
    private final AudioSink audioSink;
    private long activeGeneration = -1L;
    private String activeStationUuid = "";
    private RadioInputSession activeSession;

    public ClientRadioPlayback(StationResolver stationResolver, SessionFactory sessionFactory, AudioSink audioSink) {
        if (stationResolver == null || sessionFactory == null || audioSink == null) {
            throw new IllegalArgumentException("client radio playback dependencies are required");
        }
        this.stationResolver = stationResolver;
        this.sessionFactory = sessionFactory;
        this.audioSink = audioSink;
    }

    public synchronized void start(final long generation, final String stationUuid) {
        stopActiveLocked();
        if (generation < 0L || stationUuid == null || stationUuid.trim().isEmpty()) {
            return;
        }
        activeGeneration = generation;
        activeStationUuid = stationUuid;
        CompletableFuture<RadioStation> lookup;
        try {
            lookup = stationResolver.lookup(stationUuid);
        } catch (RuntimeException exception) {
            stopActiveLocked();
            return;
        }
        if (lookup == null) {
            stopActiveLocked();
            return;
        }
        lookup.whenComplete(new BiConsumer<RadioStation, Throwable>() {

            @Override
            public void accept(RadioStation station, Throwable failure) {
                startResolvedStation(generation, stationUuid, station, failure);
            }
        });
    }

    public synchronized void stop() {
        stopActiveLocked();
    }

    public synchronized long getActiveGeneration() {
        return activeGeneration;
    }

    private void startResolvedStation(final long generation, final String stationUuid, RadioStation station,
        Throwable failure) {
        synchronized (this) {
            if (!isActive(generation, stationUuid)) {
                return;
            }
            if (failure != null || !isUsableStation(station, stationUuid)) {
                stopActiveLocked();
                return;
            }
            final RadioInputSession session;
            try {
                session = sessionFactory.create(station.getStreamUrl(), new RadioInputSession.RadioPcmListener() {

                    @Override
                    public void onPcm(byte[] pcm) {
                        forwardPcm(generation, stationUuid, pcm);
                    }

                    @Override
                    public void onFailure(String message) {
                        stopFailedSession(generation, stationUuid);
                    }
                });
            } catch (RuntimeException exception) {
                stopActiveLocked();
                return;
            }
            if (session == null || !audioSink.startLocalRadio(generation)) {
                closeQuietly(session);
                stopActiveLocked();
                return;
            }
            activeSession = session;
            try {
                session.start();
            } catch (RuntimeException exception) {
                stopActiveLocked();
            }
        }
    }

    private synchronized void forwardPcm(long generation, String stationUuid, byte[] pcm) {
        if (isActive(generation, stationUuid) && pcm != null && pcm.length > 0) {
            audioSink.receiveLocalRadioPcm(generation, pcm);
        }
    }

    private synchronized void stopFailedSession(long generation, String stationUuid) {
        if (isActive(generation, stationUuid)) {
            stopActiveLocked();
        }
    }

    private boolean isActive(long generation, String stationUuid) {
        return activeGeneration == generation && activeStationUuid.equals(stationUuid);
    }

    private static boolean isUsableStation(RadioStation station, String stationUuid) {
        return station != null && stationUuid.equals(station.getStationUuid()) && station.getStreamUrl() != null
            && !station.getStreamUrl().trim().isEmpty();
    }

    private void stopActiveLocked() {
        RadioInputSession previous = activeSession;
        activeSession = null;
        activeGeneration = -1L;
        activeStationUuid = "";
        closeQuietly(previous);
        audioSink.stopLocalRadio();
    }

    private static void closeQuietly(RadioInputSession session) {
        if (session != null) {
            session.close();
        }
    }

    public interface StationResolver {

        CompletableFuture<RadioStation> lookup(String stationUuid);
    }

    public interface SessionFactory {

        RadioInputSession create(String streamUrl, RadioInputSession.RadioPcmListener listener);
    }

    public interface AudioSink {

        boolean startLocalRadio(long generation);

        void receiveLocalRadioPcm(long generation, byte[] pcm);

        void stopLocalRadio();
    }
}
