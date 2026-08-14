package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.horizonradio.client.audio.AudioPlayer;
import com.horizonradio.core.client.ClientLocalPlaylistState;
import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.packets.ClockSyncResponsePacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.TrackSyncPacket;
import com.horizonradio.server.AudioDownloadService;

public class HorizonRadioClientModeTest {

    private RecordingTransport transport;
    private ControlledAudioDownloadService audioDownloads;
    private Path audioDirectory;

    @Before
    public void setUp() throws Exception {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);
        new ClientProxy(new DirectClientTaskScheduler());
        transport = new RecordingTransport();
        HorizonRadioClient.setTransport(transport);
        audioDirectory = Files.createTempDirectory("horizonradio-private-playback");
        audioDownloads = new ControlledAudioDownloadService(audioDirectory);
        HorizonRadioClient.setClientAudioDownloadService(audioDownloads);
    }

    @After
    public void tearDown() throws Exception {
        HorizonRadioClient.setTransport(new HorizonRadioClient.NoopClientTransport());
        HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setClientAudioDownloadService(null);
        Files.deleteIfExists(audioDirectory.resolve("completed.wav"));
        Files.deleteIfExists(audioDirectory);
    }

    @Test
    public void privateAddUsesLocalQueueWithoutTransport() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);

        HorizonRadioClient.sendAdd("private-song", 120_000L);

        assertEquals(0, transport.addCount);
        assertEquals("private-song", HorizonRadioClient.getCachedPlaylist().get(0).sourceId);
    }

    @Test
    public void privateModeIgnoresServerPlaylistAndTrackPackets() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);

        HorizonRadioClient.handlePlaylistSnapshot(snapshotPacket(4L, "server-song"));
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(4L, "server-song", 0L, 0L, false));

        assertTrue(HorizonRadioClient.getCachedPlaylist().isEmpty());
        assertNull(HorizonRadioClient.getCachedNowPlaying());
    }

    @Test
    public void switchingBackToServerClearsPrivateViewAndRequestsSnapshotAndClock() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendAdd("private-song", 120_000L);

        HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);

        assertTrue(HorizonRadioClient.getCachedPlaylist().isEmpty());
        assertEquals(1, transport.playlistResyncCount);
        assertEquals(1, transport.clockSyncCount);
    }

    @Test
    public void serverActionsContinueToDelegateToTheTransport() {
        HorizonRadioClient.sendAdd("server-song", 120_000L);
        HorizonRadioClient.sendPlayNow("server-now", 120_000L);
        HorizonRadioClient.sendRemove("server-song");
        HorizonRadioClient.sendClearPlaylist();
        HorizonRadioClient.sendReorder(0, 1);
        HorizonRadioClient.sendSeek(0.5f);
        HorizonRadioClient.sendTogglePlayback();
        HorizonRadioClient.sendSkipTrack();
        HorizonRadioClient.sendPreviousTrack();
        HorizonRadioClient.sendToggleLoop();
        HorizonRadioClient.sendToggleShuffle();
        HorizonRadioClient.sendSelectRadio("station-id");
        HorizonRadioClient.sendStopRadio();
        HorizonRadioClient.sendClockSync();

        assertEquals(1, transport.addCount);
        assertEquals(1, transport.playNowCount);
        assertEquals(1, transport.removeCount);
        assertEquals(1, transport.clearCount);
        assertEquals(1, transport.reorderCount);
        assertEquals(1, transport.seekCount);
        assertEquals(1, transport.playbackCount);
        assertEquals(1, transport.skipCount);
        assertEquals(1, transport.previousCount);
        assertEquals(1, transport.loopCount);
        assertEquals(1, transport.shuffleCount);
        assertEquals(1, transport.radioCount);
        assertEquals(1, transport.stopRadioCount);
        assertEquals(1, transport.clockSyncCount);
        assertEquals(14, transport.totalPacketCount());
    }

    @Test
    public void groupModeRequestLeavesTheSelectedModeUnchanged() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);

        HorizonRadioClient.setPlaybackMode(PlaybackMode.GROUP);

        assertSame(PlaybackMode.PRIVATE, HorizonRadioClient.getPlaybackMode());
    }

    @Test
    public void privateModeDoesNotUseTransportForControlActionsOrClockSync() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendAdd("private-song", 120_000L);

        HorizonRadioClient.sendRemove("private-song");
        HorizonRadioClient.sendClearPlaylist();
        HorizonRadioClient.sendReorder(0, 0);
        HorizonRadioClient.sendSeek(0.5f);
        HorizonRadioClient.sendTogglePlayback();
        HorizonRadioClient.sendSkipTrack();
        HorizonRadioClient.sendPreviousTrack();
        HorizonRadioClient.sendToggleLoop();
        HorizonRadioClient.sendToggleShuffle();
        HorizonRadioClient.sendSelectRadio("station-id");
        HorizonRadioClient.sendStopRadio();
        HorizonRadioClient.sendClockSync();

        assertEquals(0, transport.totalPacketCount());
        assertTrue(HorizonRadioClient.getCachedPlaylist().isEmpty());
    }

    @Test
    public void privateSkipRemovesTheCurrentEntryAndPreparesTheFollowingEntryWithoutTransport() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendAdd("one", 120_000L);
        HorizonRadioClient.sendAdd("two", 120_000L);
        HorizonRadioClient.sendPlayNow("one", 120_000L);

        HorizonRadioClient.sendSkipTrack();

        assertEquals(Arrays.asList("two"), playlistSourceIds());
        assertEquals("two", currentPrivateSourceId());
        assertEquals(0, transport.totalPacketCount());
    }

    @Test
    public void privatePreviousSelectsTheLastSkippedEntryWithoutTransport() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendAdd("one", 120_000L);
        HorizonRadioClient.sendAdd("two", 120_000L);
        HorizonRadioClient.sendPlayNow("one", 120_000L);
        HorizonRadioClient.sendSkipTrack();
        HorizonRadioClient.sendPreviousTrack();

        HorizonRadioClient.sendPreviousTrack();

        assertEquals(Arrays.asList("one", "two"), playlistSourceIds());
        assertEquals("one", currentPrivateSourceId());
        assertEquals(0, transport.totalPacketCount());
    }

    @Test
    public void privatePlayNowStartsFiniteTrackAndDownloadWithoutTransport() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);

        HorizonRadioClient.sendPlayNow("song", 1_000L);

        assertEquals("song", currentPrivateSourceId());
        assertEquals("song", activePrivateSourceId());
        assertTrue(privateTrackStartAt() > 0L);
        assertTrue(activePrivateGeneration() > 0L);
        assertEquals("song", HorizonRadioClient.getCachedNowPlaying());
        assertTrue(audioDownloads.hasDownload("song"));
        assertEquals(0, transport.totalPacketCount());
    }

    @Test
    public void enteringPrivateModeClearsServerClockBeforeLocalTrackStart() {
        HorizonRadioClient.handleClockSync(new ClockSyncResponsePacket(1_000L, 2_000L, 2_100L), 1_100L);
        assertEquals(1_000L, longField("serverClockOffsetMs"));
        assertTrue(audioPlayerBooleanField("serverClockSynchronized"));

        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendPlayNow("song", 1_000L);

        assertEquals(0L, longField("serverClockOffsetMs"));
        assertEquals(0L, audioPlayerLongField("serverClockOffsetMs"));
        assertFalse(audioPlayerBooleanField("serverClockSynchronized"));
        assertEquals(privateTrackStartAt(), audioPlayerLongField("resumeStartAtMs"));
    }

    @Test
    public void privateTickUsesTheLocalClockForPresentation() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendPlayNow("song", 1_000L);
        long startedAt = privateTrackStartAt();

        HorizonRadioClient.onClientTick(startedAt + 500L);

        assertEquals("song", HorizonRadioClient.getCachedNowPlaying());
        assertEquals(0.5f, HorizonRadioClient.getCachedProgress(), 0.0001f);
        assertEquals(0, transport.totalPacketCount());
    }

    @Test
    public void privateTickAdvancesToNextEntryWithoutTransport() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendAdd("one", 1_000L);
        HorizonRadioClient.sendAdd("two", 1_000L);
        HorizonRadioClient.sendPlayNow("one", 1_000L);
        long startedAt = privateTrackStartAt();

        HorizonRadioClient.onClientTick(startedAt + 1_001L);

        assertEquals("two", currentPrivateSourceId());
        assertEquals(Arrays.asList("two"), playlistSourceIds());
        assertEquals(0, transport.totalPacketCount());
    }

    @Test
    public void privateTickRestartsCurrentEntryWhenLooping() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendPlayNow("song", 1_000L);
        HorizonRadioClient.sendToggleLoop();
        long firstGeneration = activePrivateGeneration();
        long startedAt = privateTrackStartAt();

        HorizonRadioClient.onClientTick(startedAt + 1_001L);

        assertEquals("song", currentPrivateSourceId());
        assertEquals(Arrays.asList("song"), playlistSourceIds());
        assertTrue(activePrivateGeneration() > firstGeneration);
        assertEquals(0, transport.totalPacketCount());
    }

    @Test
    public void privateTickStartsOneOfTheShuffledQueuedEntries() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendAdd("one", 1_000L);
        HorizonRadioClient.sendAdd("two", 1_000L);
        HorizonRadioClient.sendAdd("three", 1_000L);
        HorizonRadioClient.sendPlayNow("one", 1_000L);
        HorizonRadioClient.sendToggleShuffle();
        long startedAt = privateTrackStartAt();

        HorizonRadioClient.onClientTick(startedAt + 1_001L);

        assertTrue(Arrays.asList("two", "three").contains(currentPrivateSourceId()));
        assertEquals(2, HorizonRadioClient.getCachedPlaylist().size());
        assertFalse(playlistSourceIds().contains("one"));
        assertEquals(0, transport.totalPacketCount());
    }

    @Test
    public void privatePauseSeekAndResumeStayAlignedWithoutTransport() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendPlayNow("song", 1_000L);

        HorizonRadioClient.sendTogglePlayback();
        HorizonRadioClient.sendSeek(0.5f);

        assertTrue(HorizonRadioClient.isPaused());
        assertEquals(500L, localQueue().currentPositionMs(System.currentTimeMillis()));
        assertEquals(500L, audioPlayerLongField("resumePositionMs"));

        HorizonRadioClient.sendTogglePlayback();

        assertFalse(HorizonRadioClient.isPaused());
        assertEquals(500L, audioPlayerLongField("resumePositionMs"));
        assertEquals(0L, audioPlayerLongField("resumeStartAtMs"));
        assertEquals(0, transport.totalPacketCount());
    }

    @Test
    public void failedPrivateDownloadClearsPresentationButKeepsQueueWithoutTransport() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendPlayNow("song", 1_000L);

        audioDownloads.fail("song");

        assertNull(activePrivateSourceId());
        assertNull(HorizonRadioClient.getCachedNowPlaying());
        assertEquals(Arrays.asList("song"), playlistSourceIds());
        assertNull(localQueue().getCurrentEntry());
        assertEquals(0, transport.totalPacketCount());
    }

    @Test
    public void latePrivateDownloadCompletionIsIgnoredAfterModeSwitch() throws Exception {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendPlayNow("song", 1_000L);
        long generation = activePrivateGeneration();
        assertNotNull(audioDownloads.future("song"));

        HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);
        Path completed = Files.createFile(audioDirectory.resolve("completed.wav"));
        audioDownloads.complete("song", completed);

        assertFalse(
            HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
                PlaybackMode.SERVER,
                generation,
                generation,
                "song",
                "song"));
        assertNull(activePrivateSourceId());
        assertNull(HorizonRadioClient.getCachedNowPlaying());
    }

    @Test
    public void lateServerDownloadCompletionIsIgnoredAfterPrivateGenerationAndVideoCollision() throws Exception {
        AudioPlayer originalPlayer = AudioPlayer.getInstance();
        CountingExecutorService audioExecutor = new CountingExecutorService();
        AudioPlayer controlledPlayer = new AudioPlayer(new AudioPlayer.SourceLineFactory() {

            @Override
            public javax.sound.sampled.SourceDataLine create(javax.sound.sampled.AudioFormat format) {
                throw new AssertionError("finite callback must not open a radio line");
            }
        }, audioExecutor);
        setAudioPlayerInstance(controlledPlayer);
        try {
            long serverGeneration = longField("localPlaybackGeneration") + 2L;
            HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(serverGeneration, "song", 0L, 0L, false));
            CompletableFuture<Path> oldServerDownload = audioDownloads.future("song");
            assertNotNull(oldServerDownload);
            audioDownloads.detachDownloadOnCancel();

            HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
            HorizonRadioClient.sendPlayNow("song", 1_000L);
            assertEquals(serverGeneration, activePrivateGeneration());
            int tasksBeforeLateCompletion = audioExecutor.executeCount();
            Path completed = Files.createFile(audioDirectory.resolve("completed.wav"));
            oldServerDownload.complete(completed);

            assertFalse(
                HorizonRadioClient.shouldAcceptServerAudioCompletion(
                    HorizonRadioClient.getPlaybackMode(),
                    activePrivateGeneration(),
                    serverGeneration,
                    activePrivateSourceId(),
                    "song"));
            assertEquals(tasksBeforeLateCompletion, audioExecutor.executeCount());
            assertEquals("song", activePrivateSourceId());
            assertEquals("song", HorizonRadioClient.getCachedNowPlaying());
        } finally {
            setAudioPlayerInstance(originalPlayer);
            controlledPlayer.shutdown();
        }
    }

    @Test
    public void clearCacheInvalidatesPrivateGenerationBeforeSynchronousCancellationCallback() {
        HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
        HorizonRadioClient.sendPlayNow("song", 1_000L);
        long generation = longField("localPlaybackGeneration");
        audioDownloads.failDownloadOnCancel();

        HorizonRadioClient.clearCache();

        assertEquals(generation + 1L, longField("localPlaybackGeneration"));
        assertNull(activePrivateSourceId());
        assertNull(HorizonRadioClient.getCachedNowPlaying());
        assertTrue(HorizonRadioClient.getCachedPlaylist().isEmpty());
        assertNull(localQueue().getCurrentEntry());
    }

    @Test
    public void privateAudioCompletionGuardRequiresMatchingPrivateGenerationAndVideo() {
        assertTrue(
            HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
                PlaybackMode.PRIVATE,
                7L,
                7L,
                "private-song",
                "private-song"));
        assertTrue(
            !HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
                PlaybackMode.SERVER,
                7L,
                7L,
                "private-song",
                "private-song"));
        assertTrue(
            !HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
                PlaybackMode.PRIVATE,
                8L,
                7L,
                "private-song",
                "private-song"));
        assertTrue(
            !HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
                PlaybackMode.PRIVATE,
                7L,
                7L,
                "other-song",
                "private-song"));
    }

    private static PlaylistSyncPacket snapshotPacket(long revision, String videoId) {
        return new PlaylistSyncPacket(
            revision,
            false,
            false,
            Arrays.asList(new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, videoId, "Server")));
    }

    private static ClientLocalPlaylistState localQueue() {
        try {
            Field field = HorizonRadioClient.class.getDeclaredField("LOCAL_QUEUE");
            field.setAccessible(true);
            return (ClientLocalPlaylistState) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static long privateTrackStartAt() {
        return localQueue().getPlaybackStartTime();
    }

    private static String currentPrivateSourceId() {
        return localQueue().getCurrentSourceId();
    }

    private static long activePrivateGeneration() {
        return longField("activeTrackGeneration");
    }

    private static String activePrivateSourceId() {
        return (String) fieldValue(HorizonRadioClient.class, "activeTrackSourceId", null);
    }

    private static long audioPlayerLongField(String name) {
        return ((Long) fieldValue(com.horizonradio.client.audio.AudioPlayer.class, name,
            com.horizonradio.client.audio.AudioPlayer.getInstance())).longValue();
    }

    private static boolean audioPlayerBooleanField(String name) {
        return ((Boolean) fieldValue(com.horizonradio.client.audio.AudioPlayer.class, name,
            com.horizonradio.client.audio.AudioPlayer.getInstance())).booleanValue();
    }

    private static long longField(String name) {
        return ((Long) fieldValue(HorizonRadioClient.class, name, null)).longValue();
    }

    private static Object fieldValue(Class<?> owner, String name, Object instance) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setAudioPlayerInstance(AudioPlayer player) {
        try {
            Field field = AudioPlayer.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, player);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<String> playlistSourceIds() {
        List<String> sourceIds = new java.util.ArrayList<String>();
        for (HorizonRadioScreen.PlaylistEntry entry : HorizonRadioClient.getCachedPlaylist()) {
            sourceIds.add(entry.sourceId);
        }
        return sourceIds;
    }

    private static final class RecordingTransport implements HorizonRadioClient.ClientTransport {

        private int addCount;
        private int playNowCount;
        private int removeCount;
        private int clearCount;
        private int reorderCount;
        private int seekCount;
        private int playbackCount;
        private int skipCount;
        private int previousCount;
        private int loopCount;
        private int shuffleCount;
        private int radioCount;
        private int stopRadioCount;
        private int playlistResyncCount;
        private int clockSyncCount;

        @Override
        public void sendAdd(String videoId, String title, String duration) {
            addCount++;
        }

        @Override
        public void sendPlayNow(String videoId, String title, String duration) {
            playNowCount++;
        }

        @Override
        public void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results) {}

        @Override
        public void sendRemove(String videoId) {
            removeCount++;
        }

        @Override
        public void sendClearPlaylist() {
            clearCount++;
        }

        @Override
        public void sendReorder(int fromIndex, int targetIndex) {
            reorderCount++;
        }

        @Override
        public void sendSeek(float progress) {
            seekCount++;
        }

        @Override
        public void sendTogglePlayback() {
            playbackCount++;
        }

        @Override
        public void sendSkipTrack() {
            skipCount++;
        }

        @Override
        public void sendPreviousTrack() {
            previousCount++;
        }

        @Override
        public void sendToggleLoop() {
            loopCount++;
        }

        @Override
        public void sendToggleShuffle() {
            shuffleCount++;
        }

        @Override
        public void sendSelectRadio(String stationUuid) {
            radioCount++;
        }

        @Override
        public void sendStopRadio() {
            stopRadioCount++;
        }

        @Override
        public void sendPlaylistResync(long knownRevision) {
            playlistResyncCount++;
        }

        @Override
        public void sendClockSync(long clientSentAtMs) {
            clockSyncCount++;
        }

        private int totalPacketCount() {
            return addCount + playNowCount + removeCount + clearCount + reorderCount + seekCount + playbackCount
                + skipCount + previousCount + loopCount + shuffleCount + radioCount + stopRadioCount
                + playlistResyncCount + clockSyncCount;
        }
    }

    private static final class DirectClientTaskScheduler implements ClientProxy.ClientTaskScheduler {

        @Override
        public void schedule(Runnable task) {
            task.run();
        }
    }

    private static final class CountingExecutorService extends AbstractExecutorService {

        private int executeCount;
        private boolean shutdown;

        @Override
        public synchronized void shutdown() {
            shutdown = true;
        }

        @Override
        public synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public synchronized boolean isShutdown() {
            return shutdown;
        }

        @Override
        public synchronized boolean isTerminated() {
            return shutdown;
        }

        @Override
        public synchronized boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public synchronized void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("executor is shut down");
            }
            executeCount++;
        }

        private synchronized int executeCount() {
            return executeCount;
        }
    }

    private static final class ControlledAudioDownloadService extends AudioDownloadService {

        private final java.util.Map<String, CompletableFuture<Path>> futures =
            new java.util.HashMap<String, CompletableFuture<Path>>();
        private boolean detachDownloadOnCancel;
        private boolean failDownloadOnCancel;

        private ControlledAudioDownloadService(Path directory) throws java.io.IOException {
            super(directory);
        }

        @Override
        public synchronized CompletableFuture<Path> download(String videoId) {
            CompletableFuture<Path> future = futures.get(videoId);
            if (future == null) {
                future = new CompletableFuture<Path>();
                futures.put(videoId, future);
            }
            return future;
        }

        @Override
        public synchronized void cancelDownload(String videoId) {
            CompletableFuture<Path> future = futures.get(videoId);
            if (detachDownloadOnCancel) {
                futures.remove(videoId);
            }
            if (failDownloadOnCancel && future != null) {
                future.completeExceptionally(new IllegalStateException("cancelled during clear"));
            }
            // Futures remain completable to simulate callbacks racing cancellation.
        }

        private synchronized void detachDownloadOnCancel() {
            detachDownloadOnCancel = true;
        }

        private synchronized void failDownloadOnCancel() {
            failDownloadOnCancel = true;
        }

        private synchronized boolean hasDownload(String videoId) {
            return futures.containsKey(videoId);
        }

        private synchronized CompletableFuture<Path> future(String videoId) {
            return futures.get(videoId);
        }

        private void complete(String videoId, Path path) {
            CompletableFuture<Path> future = future(videoId);
            assertNotNull(future);
            future.complete(path);
        }

        private void fail(String videoId) {
            CompletableFuture<Path> future = future(videoId);
            assertNotNull(future);
            future.completeExceptionally(new IllegalStateException("download failed"));
        }
    }
}
