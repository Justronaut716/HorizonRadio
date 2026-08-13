package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.core.server.PlaylistState;
import com.horizonradio.network.packets.TrackSyncPacket;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import sun.misc.Unsafe;

public class PlaylistManagerTest {

    private static final String VIDEO_ID = "abcdefghijk";
    private static final String SECOND_VIDEO_ID = "lmnopqrstuv";

    @Test
    public void selectingRadioCreatesQueueEntryWithoutDirectoryLookupOrRelay() throws Exception {
        PlaylistManager manager = manager();
        try {
            manager.handleSelectRadio(testPlayer(), "station-id");

            assertEquals(
                MediaSourceType.RADIO,
                playlist(manager).get(0)
                    .getSourceType());
            assertEquals(
                "station-id",
                playlist(manager).get(0)
                    .getSourceId());
            assertEquals(1, playlist(manager).size());
            assertEquals(MediaSourceType.RADIO, current(manager).getSourceType());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void addingFiniteTrackReportsOnlyIdAndDurationAndLeavesRadioCurrent() throws Exception {
        PlaylistManager manager = manager();
        try {
            EntityPlayerMP player = testPlayer();
            manager.handleSelectRadio(player, "station-id");

            manager.handleAddToPlaylist(player, VIDEO_ID, 60_000L);

            assertEquals(MediaSourceType.RADIO, current(manager).getSourceType());
            assertEquals(
                MediaSourceType.YOUTUBE,
                playlist(manager).get(1)
                    .getSourceType());
            assertEquals(
                VIDEO_ID,
                playlist(manager).get(1)
                    .getSourceId());
            assertEquals(
                60_000L,
                playlist(manager).get(1)
                    .getDurationMs());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void stoppingRadioRemovesIndexZeroAndStartsNextFiniteTrack() throws Exception {
        PlaylistManager manager = manager();
        try {
            EntityPlayerMP player = testPlayer();
            manager.handleSelectRadio(player, "station-id");
            manager.handleAddToPlaylist(player, VIDEO_ID, 60_000L);

            manager.handleStopRadio(player);

            assertEquals(1, playlist(manager).size());
            assertEquals(MediaSourceType.YOUTUBE, current(manager).getSourceType());
            assertEquals(VIDEO_ID, current(manager).getSourceId());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void selectingAnotherRadioIsOneAtomicQueueMutation() throws Exception {
        PlaylistManager manager = manager();
        try {
            EntityPlayerMP player = testPlayer();
            manager.handleSelectRadio(player, "station-one");
            long before = state(manager).getQueueRevision();

            manager.handleSelectRadio(player, "station-two");

            assertEquals(before + 1L, state(manager).getQueueRevision());
            assertEquals(1, playlist(manager).size());
            assertEquals("station-two", current(manager).getSourceId());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void playNowAtomicallyReplacesRadioAndStartsRequestedFiniteTrack() throws Exception {
        PlaylistManager manager = manager();
        try {
            EntityPlayerMP player = testPlayer();
            manager.handleSelectRadio(player, "station-id");
            manager.handleAddToPlaylist(player, VIDEO_ID, 60_000L);
            long before = state(manager).getQueueRevision();

            manager.handlePlayNow(player, SECOND_VIDEO_ID, 90_000L);

            assertEquals(before + 1L, state(manager).getQueueRevision());
            assertEquals(MediaSourceType.YOUTUBE, current(manager).getSourceType());
            assertEquals(SECOND_VIDEO_ID, current(manager).getSourceId());
            assertEquals(90_000L, current(manager).getDurationMs());
            assertEquals(2, playlist(manager).size());
            assertEquals(
                VIDEO_ID,
                playlist(manager).get(1)
                    .getSourceId());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void rejectsUnknownDurationAndMalformedFiniteIds() throws Exception {
        PlaylistManager manager = manager();
        try {
            EntityPlayerMP player = testPlayer();

            manager.handleAddToPlaylist(player, VIDEO_ID, 0L);
            manager.handleAddToPlaylist(player, "short", 60_000L);

            assertTrue(playlist(manager).isEmpty());
            assertEquals(0L, state(manager).getQueueRevision());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void radioRejectsPauseAndSeekWithoutChangingPlaybackState() throws Exception {
        PlaylistManager manager = manager();
        try {
            EntityPlayerMP player = testPlayer();
            manager.handleSelectRadio(player, "station-id");

            manager.handleTogglePlayback(player);
            manager.handleSeek(player, 0.5F);

            assertTrue(state(manager).isPlaying());
            assertFalse(state(manager).isPaused());
            assertEquals(MediaSourceType.RADIO, state(manager).getCurrentSourceType());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void removingTheFinalFiniteTrackBroadcastsAStopTransition() throws Exception {
        RecordingPacketBroadcaster broadcaster = new RecordingPacketBroadcaster();
        PlaylistManager manager = manager(broadcaster);
        try {
            manager.handleAddToPlaylist(testPlayer(), VIDEO_ID, 60_000L);
            long playingGeneration = playbackGeneration(manager);

            manager.handleRemoveFromPlaylist(testPlayer(), VIDEO_ID);

            assertStoppedAtNewGeneration(manager, broadcaster, playingGeneration + 1L);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void clearingTheFinalFiniteTrackBroadcastsAStopTransition() throws Exception {
        RecordingPacketBroadcaster broadcaster = new RecordingPacketBroadcaster();
        PlaylistManager manager = manager(broadcaster);
        try {
            manager.handleAddToPlaylist(testPlayer(), VIDEO_ID, 60_000L);
            long playingGeneration = playbackGeneration(manager);

            manager.handleClearPlaylist(testPlayer());

            assertStoppedAtNewGeneration(manager, broadcaster, playingGeneration + 1L);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void skippingTheFinalFiniteTrackBroadcastsAStopTransition() throws Exception {
        RecordingPacketBroadcaster broadcaster = new RecordingPacketBroadcaster();
        PlaylistManager manager = manager(broadcaster);
        try {
            manager.handleAddToPlaylist(testPlayer(), VIDEO_ID, 60_000L);
            long playingGeneration = playbackGeneration(manager);

            manager.handleSkipTrack(testPlayer());

            assertStoppedAtNewGeneration(manager, broadcaster, playingGeneration + 1L);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void rejectingRadioAtFullQueuePreservesFiniteTrackAdvancement() throws Exception {
        PlaylistManager manager = manager();
        try {
            EntityPlayerMP player = testPlayer();
            manager.handleAddToPlaylist(player, VIDEO_ID, 60_000L);
            ScheduledFuture<?> scheduledAdvance = advanceFuture(manager);
            assertNotNull(scheduledAdvance);
            for (int index = 1; index < 50; index++) {
                manager.handleAddToPlaylist(player, String.format("%011d", index), 60_000L);
            }

            manager.handleSelectRadio(player, "station-id");

            assertEquals(MediaSourceType.YOUTUBE, state(manager).getCurrentSourceType());
            assertEquals(VIDEO_ID, state(manager).getCurrentSourceId());
            assertSame(scheduledAdvance, advanceFuture(manager));
            assertFalse(scheduledAdvance.isCancelled());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void productionServerManagerHasNoMediaServicesOrLegacyRelayPaths() throws IOException {
        String managerSource = source("src/main/java/com/horizonradio/server/PlaylistManager.java");
        String proxySource = source("src/main/java/com/horizonradio/CommonProxy.java");
        String networkSource = source("src/main/java/com/horizonradio/network/HorizonRadioNetwork.java");

        assertFalse(managerSource.contains("YouTubeService"));
        assertFalse(managerSource.contains("AudioDownloadService"));
        assertFalse(managerSource.contains("RadioBrowserService"));
        assertFalse(managerSource.contains("RadioStreamService"));
        assertFalse(managerSource.contains("ChartCache"));
        assertFalse(managerSource.contains("AudioChunkPacket"));
        assertFalse(managerSource.contains("NowPlayingPacket"));
        assertFalse(managerSource.contains("RadioAudioStartPacket"));
        assertFalse(managerSource.contains("RadioAudioChunkPacket"));
        assertFalse(managerSource.contains("RadioStatePacket"));
        assertFalse(managerSource.contains("ChartAddCompletionPacket"));
        assertTrue(managerSource.contains("Logger.getLogger(PlaylistManager.class.getName())"));
        assertTrue(managerSource.contains("config.isServerDebugChat()"));
        assertFalse(proxySource.contains("new YouTubeService"));
        assertFalse(proxySource.contains("new AudioDownloadService"));
        assertFalse(proxySource.contains("new RadioBrowserService"));
        assertFalse(proxySource.contains("new RadioStreamService"));
        assertFalse(proxySource.contains("SearchResultsPacket"));
        assertFalse(proxySource.contains("AudioChunkPacket"));
        assertFalse(proxySource.contains("NowPlayingPacket"));
        assertFalse(proxySource.contains("RadioSearchResultsPacket"));
        assertFalse(proxySource.contains("ChartAddCompletionPacket"));
        assertFalse(networkSource.contains("SearchRequestHandler.class"));
        assertFalse(networkSource.contains("SearchRequestPacket.class"));
        assertFalse(networkSource.contains("SearchResultsPacket.class"));
        assertFalse(networkSource.contains("ImportPlaylistPacket.class"));
        assertFalse(networkSource.contains("ImportVideoPacket.class"));
        assertFalse(networkSource.contains("RequestChartsHandler.class"));
        assertFalse(networkSource.contains("RequestChartsPacket.class"));
        assertFalse(networkSource.contains("RadioSearchRequestHandler.class"));
        assertFalse(networkSource.contains("RadioSearchRequestPacket.class"));
        assertFalse(networkSource.contains("RadioSearchResultsPacket.class"));
        assertFalse(networkSource.contains("AudioChunkHandler.class"));
        assertFalse(networkSource.contains("AudioChunkPacket.class"));
        assertFalse(networkSource.contains("NowPlayingHandler.class"));
        assertFalse(networkSource.contains("NowPlayingPacket.class"));
        assertFalse(networkSource.contains("RadioAudioStartHandler.class"));
        assertFalse(networkSource.contains("RadioAudioStartPacket.class"));
        assertFalse(networkSource.contains("RadioAudioChunkHandler.class"));
        assertFalse(networkSource.contains("RadioAudioChunkPacket.class"));
        assertFalse(networkSource.contains("RadioStatePacket.class"));
        assertFalse(networkSource.contains("ReadyPacket.class"));
        assertFalse(networkSource.contains("ChartAddCompletionPacket.class"));
    }

    private static PlaylistManager manager() throws IOException {
        return new PlaylistManager(testServer(), testConfigDirectory());
    }

    private static PlaylistManager manager(RecordingPacketBroadcaster broadcaster) throws IOException {
        return new PlaylistManager(testServer(), testConfigDirectory(), broadcaster);
    }

    private static void assertStoppedAtNewGeneration(PlaylistManager manager, RecordingPacketBroadcaster broadcaster,
        long expectedGeneration) throws Exception {
        assertFalse(state(manager).isPlaying());
        assertEquals(expectedGeneration, playbackGeneration(manager));
        assertNull(advanceFuture(manager));
        assertTrue(
            broadcaster.lastTrackSync()
                .isStop());
        assertEquals(
            expectedGeneration,
            broadcaster.lastTrackSync()
                .getGeneration());
    }

    private static long playbackGeneration(PlaylistManager manager) throws Exception {
        Field field = PlaylistManager.class.getDeclaredField("playbackGeneration");
        field.setAccessible(true);
        return field.getLong(manager);
    }

    private static ScheduledFuture<?> advanceFuture(PlaylistManager manager) throws Exception {
        Field field = PlaylistManager.class.getDeclaredField("advanceFuture");
        field.setAccessible(true);
        return (ScheduledFuture<?>) field.get(manager);
    }

    private static final class RecordingPacketBroadcaster implements PlaylistManager.PacketBroadcaster {

        private final List<IMessage> packets = new ArrayList<IMessage>();

        @Override
        public void broadcast(IMessage packet, List<EntityPlayerMP> recipients) {
            packets.add(packet);
        }

        private TrackSyncPacket lastTrackSync() {
            for (int index = packets.size() - 1; index >= 0; index--) {
                if (packets.get(index) instanceof TrackSyncPacket) {
                    return (TrackSyncPacket) packets.get(index);
                }
            }
            throw new AssertionError("expected a TrackSyncPacket broadcast");
        }
    }

    private static MinecraftServer testServer() {
        return null;
    }

    private static EntityPlayerMP testPlayer() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) ((Unsafe) unsafeField.get(null))
            .allocateInstance(EntityPlayerMP.class);
        Field profileField = EntityPlayer.class.getDeclaredField("field_146106_i");
        profileField.setAccessible(true);
        profileField.set(player, new GameProfile(java.util.UUID.randomUUID(), "test"));
        return player;
    }

    private static File testConfigDirectory() throws IOException {
        return Files.createTempDirectory("horizonradio-manager-config")
            .toFile();
    }

    private static java.util.List<PlaylistEntry> playlist(PlaylistManager manager) throws Exception {
        return state(manager).snapshot();
    }

    private static PlaylistEntry current(PlaylistManager manager) throws Exception {
        PlaylistState playlist = state(manager);
        return playlist.get(playlist.getCurrentIndex());
    }

    private static PlaylistState state(PlaylistManager manager) throws Exception {
        Field field = PlaylistManager.class.getDeclaredField("state");
        field.setAccessible(true);
        return (PlaylistState) field.get(manager);
    }

    private static String source(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), Charset.forName("UTF-8"));
    }
}
