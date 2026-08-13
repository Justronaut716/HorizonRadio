package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.core.server.PlaylistState;
import com.mojang.authlib.GameProfile;

import sun.misc.Unsafe;

public class PlaylistManagerTest {

    private static final String VIDEO_ID = "abcdefghijk";
    private static final String SECOND_VIDEO_ID = "lmnopqrstuv";

    @Test
    public void selectingRadioCreatesQueueEntryWithoutDirectoryLookupOrRelay() throws Exception {
        PlaylistManager manager = manager();
        try {
            manager.handleSelectRadio(testPlayer(), "station-id");

            assertEquals(MediaSourceType.RADIO, playlist(manager).get(0).getSourceType());
            assertEquals("station-id", playlist(manager).get(0).getSourceId());
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
            assertEquals(MediaSourceType.YOUTUBE, playlist(manager).get(1).getSourceType());
            assertEquals(VIDEO_ID, playlist(manager).get(1).getSourceId());
            assertEquals(60_000L, playlist(manager).get(1).getDurationMs());
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
            assertEquals(VIDEO_ID, playlist(manager).get(1).getSourceId());
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
        assertFalse(proxySource.contains("new YouTubeService"));
        assertFalse(proxySource.contains("new AudioDownloadService"));
        assertFalse(proxySource.contains("new RadioBrowserService"));
        assertFalse(proxySource.contains("new RadioStreamService"));
        assertFalse(networkSource.contains("SearchRequestHandler.class"));
        assertFalse(networkSource.contains("RequestChartsHandler.class"));
        assertFalse(networkSource.contains("RadioSearchRequestHandler.class"));
        assertFalse(networkSource.contains("AudioChunkHandler.class"));
        assertFalse(networkSource.contains("NowPlayingHandler.class"));
        assertFalse(networkSource.contains("RadioAudioStartHandler.class"));
        assertFalse(networkSource.contains("RadioAudioChunkHandler.class"));
    }

    private static PlaylistManager manager() throws IOException {
        return new PlaylistManager(testServer(), testConfigDirectory());
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
