package com.horizonradio.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.SourceDataLine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.horizonradio.client.audio.AudioPlayer;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.RadioAudioChunkPacket;
import com.horizonradio.network.packets.RadioAudioStartPacket;
import com.horizonradio.server.AudioDownloadService;

public class RadioClientStateTest {

    private final RecordingTransport transport = new RecordingTransport();

    @Before
    public void setUp() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setTransport(transport);
    }

    @After
    public void tearDown() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setVolume(1.0f);
        HorizonRadioClient.setTransport(new HorizonRadioClient.NoopClientTransport());
    }

    @Test
    public void radioStateIsClearedOnDisconnectCacheReset() {
        HorizonRadioClient
            .updateRadioSearchResults(Arrays.asList(new RadioStation("uuid", "Station", "", true, false)));
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(3L, "uuid", "Station", "LIVE"));

        HorizonRadioClient.clearCache();

        assertFalse(HorizonRadioClient.isRadioActive());
        assertTrue(
            HorizonRadioClient.getCachedRadioResults()
                .isEmpty());
    }

    @Test
    public void chartCacheTracksRegionAndResetsToEmptyRegion() {
        HorizonRadioClient.updateChartResults(Collections.<HorizonRadioScreen.SearchResult>emptyList(), "US");

        assertEquals("US", HorizonRadioClient.getCachedChartRegionCode());

        HorizonRadioClient.clearCache();

        assertEquals("", HorizonRadioClient.getCachedChartRegionCode());
    }

    @Test
    public void staleChartResponseFromEarlierRegionCannotReplaceCurrentRegion() {
        HorizonRadioClient.sendChartsRequest("US", false);
        HorizonRadioClient.sendChartsRequest("DE", false);

        HorizonRadioClient.updateChartResults(
            Collections.singletonList(new HorizonRadioScreen.SearchResult("de", "DE", "", "2:00", "")),
            "DE");
        HorizonRadioClient.updateChartResults(
            Collections.singletonList(new HorizonRadioScreen.SearchResult("us", "US", "", "2:00", "")),
            "US");

        assertEquals("DE", HorizonRadioClient.getCachedChartRegionCode());
        assertEquals(
            "de",
            HorizonRadioClient.getCachedCharts()
                .get(0).videoId);
    }

    @Test
    public void radioResultsCacheReturnsDefensiveCopies() {
        HorizonRadioClient
            .updateRadioSearchResults(Arrays.asList(new RadioStation("uuid", "Station", "", true, false)));

        List<RadioStation> results = HorizonRadioClient.getCachedRadioResults();
        results.clear();

        assertEquals(
            1,
            HorizonRadioClient.getCachedRadioResults()
                .size());
        assertEquals(
            "uuid",
            HorizonRadioClient.getCachedRadioResults()
                .get(0)
                .getStationUuid());
    }

    @Test
    public void onlyRadioPlaybackActionsDelegateToConfiguredTransport() {
        HorizonRadioClient.sendRadioSearch("ambient");
        HorizonRadioClient.sendSelectRadio("station-uuid");
        HorizonRadioClient.sendStopRadio();

        assertNull(transport.radioSearchQuery);
        assertEquals("station-uuid", transport.selectedRadioUuid);
        assertTrue(transport.stopRadio);
    }

    @Test
    public void disconnectCacheResetPreservesVolume() {
        HorizonRadioClient.setVolume(0.35f);

        HorizonRadioClient.clearCache();

        assertEquals(0.35f, HorizonRadioClient.getVolume(), 0.0001f);
    }

    @Test
    public void disconnectResetClearsLocalRadioPresentationOnClientThread() throws Exception {
        QueueClientTaskScheduler scheduler = new QueueClientTaskScheduler();
        ClientProxy.ClientEvents events = new ClientProxy.ClientEvents(scheduler);

        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(12L, "uuid", "Station", "LIVE"));
        events.onDisconnect(null);

        assertEquals(1, scheduler.pendingCount());
        assertTrue(HorizonRadioClient.isRadioActive());

        scheduler.runAllOnClientThread();

        assertFalse(HorizonRadioClient.isRadioActive());
        assertEquals(Arrays.asList("Test-Minecraft-Client"), scheduler.executionThreads());
    }

    @Test
    public void clientShutdownCleansUpTheAudioCache() throws Exception {
        Path directory = Files.createTempDirectory("horizonradio-shutdown-cleanup");
        RecordingAudioDownloadService service = new RecordingAudioDownloadService(directory);
        try {
            HorizonRadioClient.setClientAudioDownloadService(service);
            int cleanUpsBefore = service.cleanUpCalls;
            HorizonRadioClient.cleanUpAudioCache();
            assertEquals(cleanUpsBefore + 1, service.cleanUpCalls);
        } finally {
            HorizonRadioClient.setClientAudioDownloadService(null);
            service.shutdown();
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void radioTransitionClearsMusicNowPlayingAndIgnoresStaleMusicUpdates() {
        HorizonRadioClient.updateNowPlaying("Old song", 0.75f);

        HorizonRadioClient
            .updateRadioPresentation(ClientRadioPresentation.active(20L, "uuid", "Station", "Playing Station"));
        HorizonRadioClient.updateNowPlaying("Stale song", 0.5f);

        assertNull(HorizonRadioClient.getCachedNowPlaying());
        assertEquals(0.0f, HorizonRadioClient.getCachedProgress(), 0.0f);
    }

    @Test
    public void inactiveRadioFailureRemainsCachedWithoutRestoringMusic() {
        HorizonRadioClient.updateNowPlaying("Old song", 0.75f);
        HorizonRadioClient
            .updateRadioPresentation(ClientRadioPresentation.active(20L, "uuid", "Station", "Playing Station"));

        HorizonRadioClient
            .updateRadioPresentation(ClientRadioPresentation.stopped(20L, "Radio stream stopped producing PCM data"));

        assertFalse(HorizonRadioClient.isRadioActive());
        assertNull(HorizonRadioClient.getCachedNowPlaying());
        assertEquals(
            "Radio stream stopped producing PCM data",
            HorizonRadioClient.getCachedRadioPresentation()
                .getStatus());
    }

    @Test
    public void liveRadioWaitsForEightPacketsAndWritesFixedPcmOnAudioExecutor() throws Exception {
        FakeSourceDataLine line = new FakeSourceDataLine(8);
        RecordingSourceLineFactory factory = new RecordingSourceLineFactory(line);
        AudioPlayer player = new AudioPlayer(factory);
        try {
            player.setVolume(0.5f);
            player.startRadio(new RadioAudioStartPacket(7L, 10L, 44100, 2, 16, false));
            player.receiveRadioChunk(new RadioAudioChunkPacket(7L, 10L, new byte[] { 1, 2, 3, 4 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(7L, 11L, new byte[] { 5, 6, 7, 8 }));

            assertFalse(factory.created.await(100L, TimeUnit.MILLISECONDS));

            for (long sequence = 12L; sequence < 18L; sequence++) {
                player.receiveRadioChunk(
                    new RadioAudioChunkPacket(
                        7L,
                        sequence,
                        new byte[] { (byte) sequence, (byte) (sequence + 1L), (byte) (sequence + 2L),
                            (byte) (sequence + 3L) }));
            }

            assertTrue(line.writesCompleted.await(1L, TimeUnit.SECONDS));
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, line.format.getEncoding());
            assertEquals(44100.0f, line.format.getSampleRate(), 0.0f);
            assertEquals(16, line.format.getSampleSizeInBits());
            assertEquals(2, line.format.getChannels());
            assertEquals(4, line.format.getFrameSize());
            assertFalse(line.format.isBigEndian());
            assertEquals(32, line.audioBytes().length);
            assertEquals((float) (20.0d * Math.log10(0.5d)), line.gain.getValue(), 0.01f);
            assertEquals(Collections.singletonList("HorizonRadio-Audio"), line.operationThreads());
        } finally {
            player.shutdown();
        }
    }

    @Test
    public void liveRadioWaitsForStartupBufferBeforeOpeningAudioLine() throws Exception {
        ControlledExecutorService executor = new ControlledExecutorService();
        FakeSourceDataLine line = new FakeSourceDataLine(8);
        RecordingSourceLineFactory factory = new RecordingSourceLineFactory(line);
        AudioPlayer player = new AudioPlayer(factory, executor);
        try {
            player.startRadio(new RadioAudioStartPacket(14L, 0L, 44100, 2, 16, false));
            for (long sequence = 0L; sequence < 3L; sequence++) {
                player.receiveRadioChunk(new RadioAudioChunkPacket(14L, sequence, new byte[] { 1, 2, 3, 4 }));
            }
            executor.runAll();

            assertFalse(factory.created.await(100L, TimeUnit.MILLISECONDS));

            for (long sequence = 3L; sequence < 8L; sequence++) {
                player.receiveRadioChunk(new RadioAudioChunkPacket(14L, sequence, new byte[] { 1, 2, 3, 4 }));
            }
            executor.runAll();

            assertTrue(factory.created.await(1L, TimeUnit.SECONDS));
        } finally {
            executor.runAll();
            player.shutdown();
        }
    }

    @Test
    public void radioVolumeChangeIsNotBlockedByLiveAudioWrite() throws Exception {
        FakeSourceDataLine line = new FakeSourceDataLine(0, true);
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(line));
        try {
            player.startRadio(new RadioAudioStartPacket(13L, 0L, 44100, 2, 16, false));
            for (long sequence = 0L; sequence < 8L; sequence++) {
                player.receiveRadioChunk(new RadioAudioChunkPacket(13L, sequence, new byte[] { 1, 2, 3, 4 }));
            }

            assertTrue(line.writeStarted.await(1L, TimeUnit.SECONDS));

            player.setVolume(0.25f);

            assertTrue(line.controlVolumeApplied.await(1L, TimeUnit.SECONDS));
            assertEquals((float) (20.0d * Math.log10(0.25d)), line.gain.getValue(), 0.01f);
        } finally {
            player.shutdown();
        }
    }

    @Test
    public void delayedLineCreationKeepsFourthSequentialPacketForPlayback() throws Exception {
        FakeSourceDataLine line = new FakeSourceDataLine(9);
        BlockingSourceLineFactory factory = new BlockingSourceLineFactory(line);
        AudioPlayer player = new AudioPlayer(factory);
        try {
            player.startRadio(new RadioAudioStartPacket(9L, 0L, 44100, 2, 16, false));
            player.receiveRadioChunk(new RadioAudioChunkPacket(9L, 0L, new byte[] { 1, 2, 3, 4 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(9L, 1L, new byte[] { 5, 6, 7, 8 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(9L, 2L, new byte[] { 9, 10, 11, 12 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(9L, 3L, new byte[] { 13, 14, 15, 16 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(9L, 4L, new byte[] { 17, 18, 19, 20 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(9L, 5L, new byte[] { 21, 22, 23, 24 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(9L, 6L, new byte[] { 25, 26, 27, 28 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(9L, 7L, new byte[] { 29, 30, 31, 32 }));
            assertTrue(factory.createStarted.await(1L, TimeUnit.SECONDS));

            player.receiveRadioChunk(new RadioAudioChunkPacket(9L, 8L, new byte[] { 33, 34, 35, 36 }));
            factory.releaseCreate.countDown();

            assertTrue(line.writesCompleted.await(1L, TimeUnit.SECONDS));
            assertEquals(36, line.audioBytes().length);
        } finally {
            factory.releaseCreate.countDown();
            player.shutdown();
        }
    }

    @Test
    public void postReadyPacketsCoalesceToOnePendingDrainTask() {
        ControlledExecutorService executor = new ControlledExecutorService();
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(new FakeSourceDataLine(0)), executor);
        try {
            player.startRadio(new RadioAudioStartPacket(10L, 0L, 44100, 2, 16, false));
            executor.runNext();

            for (long sequence = 0L; sequence < 10L; sequence++) {
                player.receiveRadioChunk(new RadioAudioChunkPacket(10L, sequence, new byte[] { 1, 2, 3, 4 }));
            }

            assertEquals(1, executor.pendingCount());
        } finally {
            executor.runAll();
            player.shutdown();
        }
    }

    @Test
    public void delayedLineKeepsCompleteFramesAcrossNonAlignedPackets() throws Exception {
        FakeSourceDataLine line = new FakeSourceDataLine(4);
        BlockingSourceLineFactory factory = new BlockingSourceLineFactory(line);
        AudioPlayer player = new AudioPlayer(factory);
        try {
            player.startRadio(new RadioAudioStartPacket(11L, 0L, 44100, 2, 16, false));
            player.receiveRadioChunk(new RadioAudioChunkPacket(11L, 0L, new byte[] { 0 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(11L, 1L, new byte[] { 1, 2 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(11L, 2L, new byte[] { 3, 4, 5 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(11L, 3L, new byte[] { 6 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(11L, 4L, new byte[] { 7, 8 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(11L, 5L, new byte[] { 9, 10, 11 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(11L, 6L, new byte[] { 12, 13, 14 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(11L, 7L, new byte[] { 15 }));
            assertTrue(factory.createStarted.await(1L, TimeUnit.SECONDS));

            factory.releaseCreate.countDown();

            assertTrue(line.writesCompleted.await(1L, TimeUnit.SECONDS));
            assertArrayEquals(new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 }, line.audioBytes());
        } finally {
            factory.releaseCreate.countDown();
            player.shutdown();
        }
    }

    @Test
    public void frameAlignmentContinuesAcrossStartupBufferDrain() {
        ControlledExecutorService executor = new ControlledExecutorService();
        FakeSourceDataLine line = new FakeSourceDataLine(6);
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(line), executor);
        try {
            player.startRadio(new RadioAudioStartPacket(12L, 0L, 44100, 2, 16, false));
            executor.runNext();
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 0L, new byte[] { 0 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 1L, new byte[] { 1 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 2L, new byte[] { 2 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 3L, new byte[] { 3, 4 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 4L, new byte[] { 5 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 5L, new byte[] { 6 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 6L, new byte[] { 7 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 7L, new byte[] { 8 }));
            executor.runNext();

            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 8L, new byte[] { 9 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 9L, new byte[] { 10, 11 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 10L, new byte[] { 12 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 11L, new byte[] { 13, 14 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 12L, new byte[] { 15, 16, 17 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 13L, new byte[] { 18, 19, 20, 21 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(12L, 14L, new byte[] { 22, 23 }));
            executor.runAll();

            assertArrayEquals(
                new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23 },
                line.audioBytes());
        } finally {
            executor.runAll();
            player.shutdown();
        }
    }

    @Test
    public void newRadioGenerationClosesPreviousLineBeforeStartingReplacement() throws Exception {
        FakeSourceDataLine first = new FakeSourceDataLine(3);
        FakeSourceDataLine second = new FakeSourceDataLine(8);
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(first, second));
        try {
            startReadyRadio(player, 1L, first);

            player.startRadio(new RadioAudioStartPacket(2L, 20L, 44100, 2, 16, false));

            assertTrue(first.closed.await(1L, TimeUnit.SECONDS));
            sendStartupRadioChunks(player, 2L, 20L);

            assertTrue(second.writesCompleted.await(1L, TimeUnit.SECONDS));
        } finally {
            player.shutdown();
        }
    }

    @Test
    public void liveRadioPreservesPartialFramesAcrossPacketBoundaries() throws Exception {
        FakeSourceDataLine line = new FakeSourceDataLine(2);
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(line));
        try {
            player.startRadio(new RadioAudioStartPacket(8L, 0L, 44100, 2, 16, false));
            player.receiveRadioChunk(new RadioAudioChunkPacket(8L, 0L, new byte[] { 1, 2, 3 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(8L, 1L, new byte[] { 4, 5, 6 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(8L, 2L, new byte[] { 7, 8 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(8L, 3L, new byte[] { 9 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(8L, 4L, new byte[] { 10 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(8L, 5L, new byte[] { 11 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(8L, 6L, new byte[] { 12 }));
            player.receiveRadioChunk(new RadioAudioChunkPacket(8L, 7L, new byte[] { 13 }));

            assertTrue(line.writesCompleted.await(1L, TimeUnit.SECONDS));
            assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 }, line.audioBytes());
        } finally {
            player.shutdown();
        }
    }

    @Test
    public void finiteMusicTakeoverClosesLiveRadioLine() throws Exception {
        FakeSourceDataLine line = new FakeSourceDataLine(3);
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(line));
        try {
            startReadyRadio(player, 3L, line);

            player.receiveChunk(new AudioChunkPacket("video", "Song", 0, 2, 0L, new byte[] { 1 }));

            assertTrue(line.closed.await(1L, TimeUnit.SECONDS));
        } finally {
            player.shutdown();
        }
    }

    @Test
    public void stopRadioClosesLiveLine() throws Exception {
        FakeSourceDataLine line = new FakeSourceDataLine(3);
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(line));
        try {
            startReadyRadio(player, 4L, line);

            player.stopRadio();

            assertTrue(line.closed.await(1L, TimeUnit.SECONDS));
        } finally {
            player.shutdown();
        }
    }

    @Test
    public void stopRadioUnblocksBlockingWriteAndClosesLiveLine() throws Exception {
        FakeSourceDataLine line = new FakeSourceDataLine(0, true);
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(line));
        try {
            player.startRadio(new RadioAudioStartPacket(40L, 0L, 44100, 2, 16, false));
            sendStartupRadioChunks(player, 40L, 0L);
            assertTrue(line.writeStarted.await(1L, TimeUnit.SECONDS));

            player.stopRadio();

            assertTrue(line.closed.await(1L, TimeUnit.SECONDS));
            assertTrue(line.writeExited.await(1L, TimeUnit.SECONDS));
        } finally {
            player.shutdown();
        }
    }

    @Test
    public void newerRadioGenerationUnblocksBlockingWriteBeforeReplacementStarts() throws Exception {
        FakeSourceDataLine first = new FakeSourceDataLine(0, true);
        FakeSourceDataLine second = new FakeSourceDataLine(8);
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(first, second));
        try {
            player.startRadio(new RadioAudioStartPacket(50L, 0L, 44100, 2, 16, false));
            sendStartupRadioChunks(player, 50L, 0L);
            assertTrue(first.writeStarted.await(1L, TimeUnit.SECONDS));

            player.startRadio(new RadioAudioStartPacket(51L, 20L, 44100, 2, 16, false));

            assertTrue(first.closed.await(1L, TimeUnit.SECONDS));
            assertTrue(first.writeExited.await(1L, TimeUnit.SECONDS));
            sendStartupRadioChunks(player, 51L, 20L);
            assertTrue(second.writesCompleted.await(1L, TimeUnit.SECONDS));
        } finally {
            player.shutdown();
        }
    }

    @Test
    public void shutdownClosesLiveLineThroughIndependentControlPath() throws Exception {
        FakeSourceDataLine line = new FakeSourceDataLine(3);
        AudioPlayer player = new AudioPlayer(new RecordingSourceLineFactory(line));
        startReadyRadio(player, 5L, line);

        player.shutdown();

        assertTrue(line.closed.await(1L, TimeUnit.SECONDS));
        assertTrue(
            line.operationThreads()
                .contains("HorizonRadio-Audio"));
        assertTrue(
            line.operationThreads()
                .contains("HorizonRadio-Audio-Control"));
    }

    private static void startReadyRadio(AudioPlayer player, long generation, FakeSourceDataLine line)
        throws InterruptedException {
        player.startRadio(new RadioAudioStartPacket(generation, 0L, 44100, 2, 16, false));
        sendStartupRadioChunks(player, generation, 0L);
        assertTrue(line.writeStarted.await(1L, TimeUnit.SECONDS));
    }

    private static void sendStartupRadioChunks(AudioPlayer player, long generation, long firstSequence) {
        for (long sequence = firstSequence; sequence < firstSequence + 8L; sequence++) {
            player.receiveRadioChunk(new RadioAudioChunkPacket(generation, sequence, new byte[] { 1, 2, 3, 4 }));
        }
    }

    private static final class RecordingTransport implements HorizonRadioClient.ClientTransport {

        private String radioSearchQuery;
        private String selectedRadioUuid;
        private boolean stopRadio;

        @Override
        public void sendAdd(String videoId, String title, String duration) {}

        @Override
        public void sendPlayNow(String videoId, String title, String duration) {}

        @Override
        public void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results) {}

        @Override
        public void sendRemove(String videoId) {}

        @Override
        public void sendClearPlaylist() {}

        @Override
        public void sendReorder(int fromIndex, int targetIndex) {}

        @Override
        public void sendSeek(float progress) {}

        @Override
        public void sendTogglePlayback() {}

        @Override
        public void sendSkipTrack() {}

        @Override
        public void sendPreviousTrack() {}

        @Override
        public void sendToggleLoop() {}

        @Override
        public void sendToggleShuffle() {}

        @Override
        public void sendSelectRadio(String stationUuid) {
            selectedRadioUuid = stationUuid;
        }

        @Override
        public void sendStopRadio() {
            stopRadio = true;
        }
    }

    private static final class QueueClientTaskScheduler implements ClientProxy.ClientTaskScheduler {

        private final Deque<Runnable> tasks = new ArrayDeque<Runnable>();
        private final List<String> threads = new ArrayList<String>();

        @Override
        public synchronized void schedule(Runnable task) {
            tasks.addLast(task);
        }

        private synchronized int pendingCount() {
            return tasks.size();
        }

        private void runAllOnClientThread() throws Exception {
            final Throwable[] failure = new Throwable[1];
            Thread clientThread = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        Runnable task;
                        while ((task = nextTask()) != null) {
                            recordExecutionThread();
                            task.run();
                        }
                    } catch (Throwable throwable) {
                        failure[0] = throwable;
                    }
                }
            }, "Test-Minecraft-Client");
            clientThread.start();
            clientThread.join(1000L);
            assertFalse("client scheduler thread did not finish", clientThread.isAlive());
            if (failure[0] != null) {
                throw new AssertionError("client scheduler task failed", failure[0]);
            }
        }

        private synchronized Runnable nextTask() {
            return tasks.pollFirst();
        }

        private synchronized void recordExecutionThread() {
            threads.add(
                Thread.currentThread()
                    .getName());
        }

        private synchronized List<String> executionThreads() {
            return new ArrayList<String>(threads);
        }
    }

    private static final class RecordingAudioDownloadService extends AudioDownloadService {

        private int cleanUpCalls;

        RecordingAudioDownloadService(Path directory) throws IOException {
            super(directory, com.horizonradio.media.concurrent.MediaExecutors.newDownloadExecutor());
        }

        @Override
        public synchronized void cleanUpCache() {
            cleanUpCalls++;
        }
    }

    private static final class RecordingSourceLineFactory implements AudioPlayer.SourceLineFactory {

        private final List<FakeSourceDataLine> lines;
        private final CountDownLatch created = new CountDownLatch(1);
        private int index;

        private RecordingSourceLineFactory(FakeSourceDataLine... lines) {
            this.lines = Arrays.asList(lines);
        }

        @Override
        public synchronized SourceDataLine create(AudioFormat format) {
            FakeSourceDataLine line = lines.get(index++);
            created.countDown();
            return line;
        }
    }

    private static final class ControlledExecutorService extends AbstractExecutorService {

        private final Deque<Runnable> tasks = new ArrayDeque<Runnable>();
        private boolean shutdown;

        @Override
        public synchronized void execute(Runnable task) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("executor is shut down");
            }
            tasks.addLast(task);
        }

        @Override
        public synchronized void shutdown() {
            shutdown = true;
        }

        @Override
        public synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<Runnable>(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public synchronized boolean isShutdown() {
            return shutdown;
        }

        @Override
        public synchronized boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            runAll();
            return true;
        }

        private void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.pollFirst();
            }
            if (task != null) {
                task.run();
            }
        }

        private void runAll() {
            while (pendingCount() > 0) {
                runNext();
            }
        }

        private synchronized int pendingCount() {
            return tasks.size();
        }
    }

    private static final class BlockingSourceLineFactory implements AudioPlayer.SourceLineFactory {

        private final FakeSourceDataLine line;
        private final CountDownLatch createStarted = new CountDownLatch(1);
        private final CountDownLatch releaseCreate = new CountDownLatch(1);

        private BlockingSourceLineFactory(FakeSourceDataLine line) {
            this.line = line;
        }

        @Override
        public SourceDataLine create(AudioFormat format) throws InterruptedException {
            createStarted.countDown();
            if (!releaseCreate.await(1L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release source line creation");
            }
            return line;
        }
    }

    private static final class FakeSourceDataLine implements SourceDataLine {

        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CountDownLatch writesCompleted;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch writeExited = new CountDownLatch(1);
        private final CountDownLatch controlVolumeApplied = new CountDownLatch(1);
        private final List<String> threads = new java.util.ArrayList<String>();
        private final FloatControl gain = new FloatControl(
            FloatControl.Type.MASTER_GAIN,
            -80.0f,
            6.0f,
            0.1f,
            0,
            0.0f,
            "dB") {

            @Override
            public void setValue(float value) {
                super.setValue(value);
                FakeSourceDataLine.this.recordThread();
                if ("HorizonRadio-Audio-Control".equals(
                    Thread.currentThread()
                        .getName())) {
                    controlVolumeApplied.countDown();
                }
            }
        };
        private AudioFormat format;
        private final boolean blockWrites;
        private boolean open;
        private boolean running;

        private FakeSourceDataLine(int expectedWrites) {
            this(expectedWrites, false);
        }

        private FakeSourceDataLine(int expectedWrites, boolean blockWrites) {
            writesCompleted = new CountDownLatch(expectedWrites);
            this.blockWrites = blockWrites;
        }

        @Override
        public synchronized void open(AudioFormat value, int bufferSize) {
            recordThread();
            format = value;
            open = true;
        }

        @Override
        public void open(AudioFormat value) {
            open(value, value.getFrameSize() * 1024);
        }

        @Override
        public int write(byte[] data, int offset, int length) {
            synchronized (this) {
                recordThread();
                if (format == null || length % format.getFrameSize() != 0) {
                    throw new IllegalArgumentException("write must contain complete sample frames");
                }
                writeStarted.countDown();
                while (blockWrites && open) {
                    try {
                        wait();
                    } catch (InterruptedException exception) {
                        Thread.currentThread()
                            .interrupt();
                        writeExited.countDown();
                        return -1;
                    }
                }
                if (!open) {
                    writeExited.countDown();
                    return -1;
                }
                output.write(data, offset, length);
                writesCompleted.countDown();
                writeExited.countDown();
                return length;
            }
        }

        @Override
        public synchronized void start() {
            recordThread();
            running = true;
        }

        @Override
        public synchronized void stop() {
            recordThread();
            running = false;
        }

        @Override
        public synchronized void close() {
            recordThread();
            open = false;
            running = false;
            closed.countDown();
            notifyAll();
        }

        @Override
        public synchronized boolean isOpen() {
            return open;
        }

        @Override
        public synchronized boolean isRunning() {
            return running;
        }

        @Override
        public synchronized boolean isActive() {
            return running;
        }

        @Override
        public synchronized AudioFormat getFormat() {
            return format;
        }

        @Override
        public int getBufferSize() {
            return 4096;
        }

        @Override
        public int available() {
            return 4096;
        }

        @Override
        public int getFramePosition() {
            return 0;
        }

        @Override
        public long getLongFramePosition() {
            return 0L;
        }

        @Override
        public long getMicrosecondPosition() {
            return 0L;
        }

        @Override
        public float getLevel() {
            return 0.0f;
        }

        @Override
        public void drain() {}

        @Override
        public void flush() {}

        @Override
        public Line.Info getLineInfo() {
            return new DataLine.Info(SourceDataLine.class, format);
        }

        @Override
        public void open() {
            open = true;
        }

        @Override
        public Control[] getControls() {
            return new Control[] { gain };
        }

        @Override
        public boolean isControlSupported(Control.Type control) {
            return FloatControl.Type.MASTER_GAIN.equals(control);
        }

        @Override
        public Control getControl(Control.Type control) {
            if (!isControlSupported(control)) {
                throw new IllegalArgumentException("unsupported control");
            }
            return gain;
        }

        @Override
        public void addLineListener(LineListener listener) {}

        @Override
        public void removeLineListener(LineListener listener) {}

        private synchronized byte[] audioBytes() {
            return output.toByteArray();
        }

        private synchronized List<String> operationThreads() {
            return new java.util.ArrayList<String>(threads);
        }

        private void recordThread() {
            String name = Thread.currentThread()
                .getName();
            if (!threads.contains(name)) {
                threads.add(name);
            }
        }
    }
}
