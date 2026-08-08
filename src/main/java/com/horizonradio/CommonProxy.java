package com.horizonradio;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.network.ServerMessageHandlers;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.NowPlayingPacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SearchResultsPacket;
import com.horizonradio.server.AudioDownloadService;
import com.horizonradio.server.PlaylistManager;
import com.horizonradio.server.YouTubeService;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    private static final Logger LOGGER = Logger.getLogger(CommonProxy.class.getName());

    private PlaylistManager playlistManager;
    private YouTubeService youTubeService;
    private AudioDownloadService audioDownloadService;
    private File configDirectory;

    public void preInit(FMLPreInitializationEvent event) {
        configDirectory = event.getSuggestedConfigurationFile()
            .getParentFile();
        HorizonRadio.setConfig(HorizonRadioConfig.load(configDirectory));
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void onServerStarting(MinecraftServer server) {
        if (server == null) {
            return;
        }
        onServerStopping(null);
        if (HorizonRadio.getConfig() == null) {
            HorizonRadio.setConfig(HorizonRadioConfig.load(null));
        }

        youTubeService = new YouTubeService();
        try {
            audioDownloadService = new AudioDownloadService(
                Paths.get(
                    HorizonRadio.getConfig()
                        .getDownloadDir()),
                HorizonRadio.getConfig()
                    .getYoutubeCookiesFromBrowser(),
                HorizonRadio.getConfig()
                    .getYoutubeCookiesFile());
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "HorizonRadio: Failed to initialise audio download service", exception);
            youTubeService = null;
            audioDownloadService = null;
            return;
        }

        playlistManager = new PlaylistManager(server, youTubeService, audioDownloadService, configDirectory);
        final PlaylistManager manager = playlistManager;
        ServerMessageHandlers.setHook(new ServerMessageHandlers.ServerPacketHook() {

            @Override
            public void handleSearch(EntityPlayerMP player, String query) {
                manager.handleSearch(player, query);
            }

            @Override
            public void handleImportPlaylist(EntityPlayerMP player, String playlistUrl) {
                manager.handleImportPlaylist(player, playlistUrl);
            }

            @Override
            public void handleImportVideo(EntityPlayerMP player, String videoUrl) {
                manager.handleImportVideo(player, videoUrl);
            }

            @Override
            public void handleRequestCharts(EntityPlayerMP player, boolean forceRefresh) {
                manager.handleRequestCharts(player, forceRefresh);
            }

            @Override
            public void handleAdd(EntityPlayerMP player, String videoId, String title, String duration) {
                manager.handleAddToPlaylist(player, videoId, title, duration);
            }

            @Override
            public void handlePlayNow(EntityPlayerMP player, String videoId, String title, String duration) {
                manager.handlePlayNow(player, videoId, title, duration);
            }

            @Override
            public void handleAddCharts(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries,
                boolean remove) {
                manager.handleAddChartsToPlaylist(player, entries, remove);
            }

            @Override
            public void handleRemove(EntityPlayerMP player, String videoId) {
                manager.handleRemoveFromPlaylist(player, videoId);
            }

            @Override
            public void handleClearPlaylist(EntityPlayerMP player) {
                manager.handleClearPlaylist(player);
            }

            @Override
            public void handleReady(EntityPlayerMP player, String videoId) {
                manager.onPlayerReady(player, videoId);
            }

            @Override
            public void handleReorder(EntityPlayerMP player, int fromIndex, int targetIndex) {
                manager.handleReorder(player, fromIndex, targetIndex);
            }

            @Override
            public void handleSeek(EntityPlayerMP player, float progress) {
                manager.handleSeek(player, progress);
            }

            @Override
            public void handleTogglePlayback(EntityPlayerMP player) {
                manager.handleTogglePlayback(player);
            }

            @Override
            public void handleSkipTrack(EntityPlayerMP player) {
                manager.handleSkipTrack(player);
            }

            @Override
            public void handlePreviousTrack(EntityPlayerMP player) {
                manager.handlePreviousTrack(player);
            }

            @Override
            public void handleToggleLoop(EntityPlayerMP player) {
                manager.handleToggleLoop(player);
            }

            @Override
            public void handleToggleShuffle(EntityPlayerMP player) {
                manager.handleToggleShuffle(player);
            }
        });
    }

    public void onServerStopping(MinecraftServer server) {
        PlaylistManager manager = playlistManager;
        playlistManager = null;
        if (manager != null) {
            manager.shutdown();
        }
        if (audioDownloadService != null) {
            audioDownloadService.shutdown();
        }
        audioDownloadService = null;
        youTubeService = null;
        ServerMessageHandlers.setHook(null);
    }

    public void onPlayerLoggedIn(EntityPlayerMP player) {
        if (playlistManager != null) {
            playlistManager.syncToPlayer(player);
        }
    }

    public void onPlayerLoggedOut(EntityPlayerMP player) {
        if (playlistManager != null) {
            playlistManager.handleDisconnect(player);
        }
    }

    public void handleSearchResults(SearchResultsPacket packet) {}

    public void handleChartResults(SearchResultsPacket packet) {}

    public void handlePlaylistSync(PlaylistSyncPacket packet) {}

    public void handleAudioChunk(AudioChunkPacket packet) {}

    public void handleNowPlaying(NowPlayingPacket packet) {}

    public void handlePause(PausePacket packet) {}

    public void handleResume(ResumePacket packet) {}

    public void handleLoopState(boolean looping) {}

    public void handleShuffleState(boolean shuffling) {}
}
