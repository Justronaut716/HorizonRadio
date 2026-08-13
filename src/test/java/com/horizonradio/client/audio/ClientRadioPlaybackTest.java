package com.horizonradio.client.audio;

import static org.junit.Assert.assertEquals;
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

        private FakeSession(RadioPcmListener listener) {
            super("https://radio.example/fake", listener);
        }

        @Override
        public void start() {
            started = true;
        }
    }

    private static final class RecordingAudioSink implements ClientRadioPlayback.AudioSink {

        private long startedGeneration = -1L;

        @Override
        public boolean startLocalRadio(long generation) {
            startedGeneration = generation;
            return true;
        }

        @Override
        public void receiveLocalRadioPcm(long generation, byte[] pcm) {}

        @Override
        public void stopLocalRadio() {}
    }
}
