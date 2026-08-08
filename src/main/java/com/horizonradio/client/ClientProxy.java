package com.horizonradio.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import com.horizonradio.CommonProxy;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.NowPlayingPacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SearchResultsPacket;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        File configDirectory = event.getSuggestedConfigurationFile()
            .getParentFile();
        HorizonRadioClient.loadClientConfig(configDirectory);
        HorizonRadioClient.setTransport(new HorizonRadioClient.ForgeClientTransport());
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        HorizonRadioKeybinds.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientEvents());
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }

    @Override
    public void handleSearchResults(final SearchResultsPacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                List<HorizonRadioScreen.SearchResult> results = new ArrayList<HorizonRadioScreen.SearchResult>();
                for (SearchResultsPacket.Entry entry : packet.getResults()) {
                    results.add(
                        new HorizonRadioScreen.SearchResult(
                            entry.getVideoId(),
                            entry.getTitle(),
                            entry.getChannel(),
                            entry.getDuration(),
                            entry.getThumbnail()));
                }
                HorizonRadioClient.updateSearchResults(results);
            }
        });
    }

    @Override
    public void handleChartResults(final SearchResultsPacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                List<HorizonRadioScreen.SearchResult> results = new ArrayList<HorizonRadioScreen.SearchResult>();
                for (SearchResultsPacket.Entry entry : packet.getResults()) {
                    results.add(
                        new HorizonRadioScreen.SearchResult(
                            entry.getVideoId(),
                            entry.getTitle(),
                            entry.getChannel(),
                            entry.getDuration(),
                            entry.getThumbnail()));
                }
                HorizonRadioClient.updateChartResults(results);
            }
        });
    }

    @Override
    public void handlePlaylistSync(final PlaylistSyncPacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
                for (PlaylistSyncPacket.Entry entry : packet.getEntries()) {
                    entries.add(
                        new HorizonRadioScreen.PlaylistEntry(
                            entry.getVideoId(),
                            entry.getTitle(),
                            entry.getDuration(),
                            entry.getAddedBy()));
                }
                HorizonRadioClient.updatePlaylist(entries);
            }
        });
    }

    @Override
    public void handleAudioChunk(final AudioChunkPacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.handleAudioChunk(packet);
            }
        });
    }

    @Override
    public void handleNowPlaying(final NowPlayingPacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.updateNowPlaying(packet.getTitle(), packet.getProgress());
            }
        });
    }

    @Override
    public void handlePause(final PausePacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.handlePause(packet.getPositionMs());
            }
        });
    }

    @Override
    public void handleResume(final ResumePacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.handleResume(packet.getPositionMs());
            }
        });
    }

    @Override
    public void handleLoopState(final boolean looping) {
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.updateLooping(looping);
            }
        });
    }

    @Override
    public void handleShuffleState(final boolean shuffling) {
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.updateShuffling(shuffling);
            }
        });
    }

    private static void schedule(Runnable task) {
        Minecraft.getMinecraft()
            .func_152344_a(task);
    }

    public static final class ClientEvents {

        private boolean chartLoadStarted;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                HorizonRadioKeybinds.onClientTick();
                Minecraft minecraft = Minecraft.getMinecraft();
                if (minecraft.theWorld == null || minecraft.thePlayer == null) {
                    chartLoadStarted = false;
                } else if (!chartLoadStarted) {
                    chartLoadStarted = true;
                    HorizonRadioClient.beginChartLoading();
                    HorizonRadioClient.sendChartsRequest(false);
                }
            }
        }

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            HorizonRadioClient.clearCache();
        }
    }
}
