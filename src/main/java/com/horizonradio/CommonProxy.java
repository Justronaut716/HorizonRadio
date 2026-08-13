package com.horizonradio;

import java.io.File;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.network.ServerMessageHandlers;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.ChartAddCompletionPacket;
import com.horizonradio.network.packets.ClockSyncResponsePacket;
import com.horizonradio.network.packets.NowPlayingPacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.RadioSearchResultsPacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SearchResultsPacket;
import com.horizonradio.network.packets.TrackSyncPacket;
import com.horizonradio.server.PlaylistManager;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    private PlaylistManager playlistManager;
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

        playlistManager = new PlaylistManager(server, configDirectory);
        final PlaylistManager manager = playlistManager;
        ServerMessageHandlers.setHook(new ServerMessageHandlers.ServerPacketHook() {

            @Override
            public void handleAdd(EntityPlayerMP player, String videoId, long durationMs) {
                manager.handleAddToPlaylist(player, videoId, durationMs);
            }

            @Override
            public void handlePlayNow(EntityPlayerMP player, String videoId, long durationMs) {
                manager.handlePlayNow(player, videoId, durationMs);
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

            @Override
            public void handleSelectRadio(EntityPlayerMP player, String stationUuid) {
                manager.handleSelectRadio(player, stationUuid);
            }

            @Override
            public void handleStopRadio(EntityPlayerMP player) {
                manager.handleStopRadio(player);
            }

            @Override
            public void handlePlaylistResyncRequest(EntityPlayerMP player, long knownRevision) {
                manager.syncToPlayer(player);
            }
        });
    }

    public void onServerStopping(MinecraftServer server) {
        PlaylistManager manager = playlistManager;
        playlistManager = null;
        if (manager != null) {
            manager.shutdown();
        }
        ServerMessageHandlers.setHook(null);
    }

    public void onPlayerLoggedIn(EntityPlayerMP player) {
        if (playlistManager != null) {
            playlistManager.syncToPlayer(player);
        }
    }

    public void onPlayerLoggedOut(EntityPlayerMP player) {
    }

    public void handleSearchResults(SearchResultsPacket packet) {}

    public void handleChartResults(SearchResultsPacket packet) {}

    public void handlePlaylistSync(PlaylistSyncPacket packet) {}

    public void handlePlaylistDelta(PlaylistDeltaPacket packet) {}

    public void handleChartAddCompletion(ChartAddCompletionPacket packet) {}

    public void handleClockSync(ClockSyncResponsePacket packet) {}

    public void handleAudioChunk(AudioChunkPacket packet) {}

    public void handleTrackSync(TrackSyncPacket packet) {}

    public void handleNowPlaying(NowPlayingPacket packet) {}

    public void handlePause(PausePacket packet) {}

    public void handleResume(ResumePacket packet) {}

    public void handleLoopState(boolean looping) {}

    public void handleShuffleState(boolean shuffling) {}

    public void handleRadioSearchResults(RadioSearchResultsPacket packet) {}

}
