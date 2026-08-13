package com.horizonradio.client.audio;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.BiConsumer;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.server.media.RadioInputSession;

/** Resolves a selected station locally and streams its normalized PCM to the local audio player. */
public final class ClientRadioPlayback {

    private static final Logger LOGGER = Logger.getLogger(ClientRadioPlayback.class.getName());

    private final StationResolver stationResolver;
    private final SessionFactory sessionFactory;
    private final AudioSink audioSink;
    private final StatusListener statusListener;
    private long activeGeneration = -1L;
    private String activeStationUuid = "";
    private RadioInputSession activeSession;

    public ClientRadioPlayback(StationResolver stationResolver, SessionFactory sessionFactory, AudioSink audioSink) {
        this(stationResolver, sessionFactory, audioSink, new NoopStatusListener());
    }

    public ClientRadioPlayback(StationResolver stationResolver, SessionFactory sessionFactory, AudioSink audioSink,
        StatusListener statusListener) {
        if (stationResolver == null || sessionFactory == null || audioSink == null || statusListener == null) {
            throw new IllegalArgumentException("client radio playback dependencies are required");
        }
        this.stationResolver = stationResolver;
        this.sessionFactory = sessionFactory;
        this.audioSink = audioSink;
        this.statusListener = statusListener;
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
            failActiveLocked(generation, stationUuid, "Station lookup failed", exception);
            return;
        }
        if (lookup == null) {
            failActiveLocked(generation, stationUuid, "Station is unavailable", null);
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
                failActiveLocked(generation, stationUuid, "Station is unavailable", failure);
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
                        stopFailedSession(generation, stationUuid, message);
                    }
                });
            } catch (RuntimeException exception) {
                failActiveLocked(generation, stationUuid, "Radio stream could not be opened", exception);
                return;
            }
            if (session == null || !audioSink.beginLocalRadioPcm(generation)) {
                closeQuietly(session);
                failActiveLocked(generation, stationUuid, "Radio audio is unavailable", null);
                return;
            }
            activeSession = session;
            try {
                session.start();
            } catch (RuntimeException exception) {
                failActiveLocked(generation, stationUuid, "Radio stream could not be started", exception);
                return;
            }
            if (isActive(generation, stationUuid)) {
                statusListener.onStarted(generation, stationUuid, station.getName());
            }
        }
    }

    private synchronized void forwardPcm(long generation, String stationUuid, byte[] pcm) {
        if (isActive(generation, stationUuid) && pcm != null && pcm.length > 0) {
            audioSink.bufferLocalRadioPcm(generation, pcm);
        }
    }

    private synchronized void stopFailedSession(long generation, String stationUuid, String message) {
        if (isActive(generation, stationUuid)) {
            failActiveLocked(
                generation,
                stationUuid,
                message == null || message.trim().isEmpty() ? "Radio stream failed" : message,
                null);
        }
    }

    private boolean isActive(long generation, String stationUuid) {
        return activeGeneration == generation && activeStationUuid.equals(stationUuid);
    }

    private static boolean isUsableStation(RadioStation station, String stationUuid) {
        return station != null && stationUuid.equals(station.getStationUuid()) && station.isLastCheckOk()
            && station.getStreamUrl() != null
            && !station.getStreamUrl().trim().isEmpty();
    }

    private void stopActiveLocked() {
        RadioInputSession previous = activeSession;
        activeSession = null;
        activeGeneration = -1L;
        activeStationUuid = "";
        closeQuietly(previous);
        audioSink.stopLocalRadioPcm();
    }

    private void failActiveLocked(long generation, String stationUuid, String message, Throwable failure) {
        if (!isActive(generation, stationUuid)) {
            return;
        }
        if (failure == null) {
            LOGGER.warning("HorizonRadio local radio failure for " + stationUuid + ": " + message);
        } else {
            LOGGER.log(Level.WARNING, "HorizonRadio local radio failure for " + stationUuid + ": " + message, failure);
        }
        stopActiveLocked();
        statusListener.onFailure(generation, stationUuid, message);
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

        boolean beginLocalRadioPcm(long generation);

        void bufferLocalRadioPcm(long generation, byte[] pcm);

        void stopLocalRadioPcm();
    }

    public interface StatusListener {

        void onStarted(long generation, String stationUuid, String stationName);

        void onFailure(long generation, String stationUuid, String message);
    }

    private static final class NoopStatusListener implements StatusListener {

        @Override
        public void onStarted(long generation, String stationUuid, String stationName) {}

        @Override
        public void onFailure(long generation, String stationUuid, String message) {}
    }
}
