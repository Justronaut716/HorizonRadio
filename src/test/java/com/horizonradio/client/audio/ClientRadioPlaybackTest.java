package com.horizonradio.client.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.server.media.RadioInputSession;

public class ClientRadioPlaybackTest {

    @Test
    public void localRadioStartsOnlyAfterStationLookupAndUsesLiveEdge() {
        FakeStationResolver resolver = new FakeStationResolver();
        FakeRadioSessionFactory sessions = new FakeRadioSessionFactory();
        RecordingAudioSink audio = new RecordingAudioSink();
        ClientRadioPlayback playback = new ClientRadioPlayback(resolver, sessions, audio);

        playback.start(21L, "station-id");
        resolver.complete("station-id", station("station-id", "https://radio.example/live"));

        assertEquals("https://radio.example/live", sessions.lastUrl);
        assertEquals(21L, playback.getActiveGeneration());
        assertEquals(21L, audio.startedGeneration);
        assertTrue(sessions.lastSession.started);
    }

    @Test
    public void staleStationLookupCannotReplaceNewerRadioGeneration() {
        FakeStationResolver resolver = new FakeStationResolver();
        FakeRadioSessionFactory sessions = new FakeRadioSessionFactory();
        ClientRadioPlayback playback = new ClientRadioPlayback(resolver, sessions, new RecordingAudioSink());

        playback.start(21L, "station-old");
        playback.start(22L, "station-new");
        resolver.complete("station-new", station("station-new", "https://radio.example/new"));
        resolver.complete("station-old", station("station-old", "https://radio.example/old"));

        assertEquals(22L, playback.getActiveGeneration());
        assertEquals("https://radio.example/new", sessions.lastUrl);
        assertFalse(sessions.openedUrls.contains("https://radio.example/old"));
    }

    @Test
    public void activeSessionForwardsPcmOnlyWhileItsGenerationIsCurrent() {
        FakeStationResolver resolver = new FakeStationResolver();
        FakeRadioSessionFactory sessions = new FakeRadioSessionFactory();
        RecordingAudioSink audio = new RecordingAudioSink();
        ClientRadioPlayback playback = new ClientRadioPlayback(resolver, sessions, audio);

        playback.start(21L, "station-old");
        resolver.complete("station-old", station("station-old", "https://radio.example/old"));
        FakeSession oldSession = sessions.lastSession;
        oldSession.emitPcm(new byte[] { 1, 2, 3, 4 });

        playback.start(22L, "station-new");
        oldSession.emitPcm(new byte[] { 5, 6, 7, 8 });

        assertEquals(1, audio.receivedPcm.size());
        assertEquals(21L, audio.receivedGenerations.get(0).longValue());
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, audio.receivedPcm.get(0));
    }

    private static RadioStation station(String uuid, String streamUrl) {
        return new RadioStation(uuid, "Station", streamUrl, true, false);
    }

    private static final class FakeStationResolver implements ClientRadioPlayback.StationResolver {

        private final Map<String, CompletableFuture<RadioStation>> lookups = new HashMap<String, CompletableFuture<RadioStation>>();

        @Override
        public CompletableFuture<RadioStation> lookup(String stationUuid) {
            CompletableFuture<RadioStation> lookup = new CompletableFuture<RadioStation>();
            lookups.put(stationUuid, lookup);
            return lookup;
        }

        private void complete(String stationUuid, RadioStation station) {
            lookups.get(stationUuid).complete(station);
        }
    }

    private static final class FakeRadioSessionFactory implements ClientRadioPlayback.SessionFactory {

        private final List<String> openedUrls = new ArrayList<String>();
        private String lastUrl;
        private FakeSession lastSession;

        @Override
        public RadioInputSession create(String streamUrl, RadioInputSession.RadioPcmListener listener) {
            lastUrl = streamUrl;
            openedUrls.add(streamUrl);
            lastSession = new FakeSession(listener);
            return lastSession;
        }
    }

    private static final class FakeSession extends RadioInputSession {

        private boolean started;
        private final RadioPcmListener listener;

        private FakeSession(RadioPcmListener listener) {
            super("https://radio.example/fake", listener);
            this.listener = listener;
        }

        @Override
        public void start() {
            started = true;
        }

        private void emitPcm(byte[] pcm) {
            listener.onPcm(pcm);
        }
    }

    private static final class RecordingAudioSink implements ClientRadioPlayback.AudioSink {

        private long startedGeneration = -1L;
        private final List<Long> receivedGenerations = new ArrayList<Long>();
        private final List<byte[]> receivedPcm = new ArrayList<byte[]>();

        @Override
        public boolean beginLocalRadioPcm(long generation) {
            startedGeneration = generation;
            return true;
        }

        @Override
        public void bufferLocalRadioPcm(long generation, byte[] pcm) {
            receivedGenerations.add(generation);
            receivedPcm.add(pcm);
        }

        @Override
        public void stopLocalRadioPcm() {}
    }
}
