package com.horizonradio.client;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.horizonradio.CommonProxy;
import com.horizonradio.client.audio.AudioPlayer;
import com.horizonradio.client.audio.ClientRadioPlayback;
import com.horizonradio.client.media.ClientMediaService;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.network.packets.ClockSyncResponsePacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.TrackSyncPacket;
import com.horizonradio.server.AudioDownloadService;
import com.horizonradio.server.RadioBrowserService;
import com.horizonradio.server.YouTubeService;
import com.horizonradio.server.media.RadioInputSession;
import com.horizonradio.server.media.YouTubeMediaModels;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    private static final Logger LOGGER = Logger.getLogger(ClientProxy.class.getName());

    interface ClientTaskScheduler {

        void schedule(Runnable task);
    }

    private static final class MinecraftClientTaskScheduler implements ClientTaskScheduler {

        @Override
        public void schedule(Runnable task) {
            Minecraft.getMinecraft()
                .func_152344_a(task);
        }
    }

    private final ClientTaskScheduler clientTaskScheduler;
    private static volatile ClientTaskScheduler activeClientTaskScheduler;

    public ClientProxy() {
        this(new MinecraftClientTaskScheduler());
    }

    ClientProxy(ClientTaskScheduler clientTaskScheduler) {
        if (clientTaskScheduler == null) {
            throw new IllegalArgumentException("client task scheduler is required");
        }
        this.clientTaskScheduler = clientTaskScheduler;
        activeClientTaskScheduler = clientTaskScheduler;
    }

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        YouTubeMediaModels.preferIpv6ForClientMedia();
        super.preInit(event);
        File configDirectory = event.getSuggestedConfigurationFile()
            .getParentFile();
        HorizonRadioClient.loadClientConfig(configDirectory);
        try {
            File audioDirectory = new File(
                configDirectory == null ? new File(".") : configDirectory,
                "horizonradio-audio");
            AudioDownloadService audioDownloadService = new AudioDownloadService(audioDirectory.toPath());
            HorizonRadioClient.setClientAudioDownloadService(audioDownloadService);
            final ClientMediaService mediaService = new ClientMediaService(
                new YouTubeService(),
                audioDownloadService,
                new RadioBrowserService());
            HorizonRadioClient.setClientMediaService(mediaService);
            HorizonRadioClient
                .setClientRadioPlayback(new ClientRadioPlayback(new ClientRadioPlayback.StationResolver() {

                    @Override
                    public CompletableFuture<RadioStation> lookup(String stationUuid) {
                        return mediaService.lookupRadio(stationUuid);
                    }
                }, new ClientRadioPlayback.SessionFactory() {

                    @Override
                    public RadioInputSession create(String streamUrl, RadioInputSession.RadioPcmListener listener) {
                        return new RadioInputSession(streamUrl, listener);
                    }
                }, new ClientRadioPlayback.AudioSink() {

                    @Override
                    public boolean beginLocalRadioPcm(long generation) {
                        return AudioPlayer.getInstance()
                            .beginLocalRadioPcm(generation);
                    }

                    @Override
                    public void bufferLocalRadioPcm(long generation, byte[] pcm) {
                        AudioPlayer.getInstance()
                            .bufferLocalRadioPcm(generation, pcm);
                    }

                    @Override
                    public void stopLocalRadioPcm() {
                        AudioPlayer.getInstance()
                            .stopRadio();
                    }
                }, new ClientRadioPlayback.StatusListener() {

                    @Override
                    public void onStarted(final long generation, final String stationUuid, final String stationName) {
                        scheduleOnClientThread(new Runnable() {

                            @Override
                            public void run() {
                                HorizonRadioClient.handleLocalRadioStarted(generation, stationUuid, stationName);
                            }
                        });
                    }

                    @Override
                    public void onFailure(final long generation, final String stationUuid, final String message) {
                        scheduleOnClientThread(new Runnable() {

                            @Override
                            public void run() {
                                HorizonRadioClient.handleLocalRadioFailure(generation, stationUuid, message);
                            }
                        });
                    }
                }));
        } catch (IOException exception) {
            HorizonRadioClient.setClientMediaService(null);
            HorizonRadioClient.setClientRadioPlayback(null);
            LOGGER.log(Level.WARNING, "HorizonRadio: Failed to initialise client audio cache", exception);
        }
        HorizonRadioClient.setTransport(new HorizonRadioClient.ForgeClientTransport());
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        HorizonRadioKeybinds.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientEvents(clientTaskScheduler));
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }

    @Override
    public void handlePlaylistSync(final PlaylistSyncPacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.handlePlaylistSnapshot(packet);
            }
        });
    }

    @Override
    public void handlePlaylistDelta(final PlaylistDeltaPacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.handlePlaylistDelta(packet);
            }
        });
    }

    @Override
    public void handleClockSync(final ClockSyncResponsePacket packet) {
        final long clientReceivedAtMs = System.currentTimeMillis();
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.handleClockSync(packet, clientReceivedAtMs);
            }
        });
    }

    @Override
    public void handleTrackSync(final TrackSyncPacket packet) {
        schedule(new Runnable() {

            @Override
            public void run() {
                HorizonRadioClient.handleTrackSync(packet);
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
                HorizonRadioClient.handleResume(packet.getPositionMs(), packet.getStartAtMs());
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

    private void schedule(Runnable task) {
        clientTaskScheduler.schedule(task);
    }

    static void sendDebugChat(String message) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.thePlayer == null) {
            return;
        }
        minecraft.thePlayer.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.GRAY + "[HorizonRadio][Client] " + (message == null ? "" : message)));
    }

    static void scheduleOnClientThread(Runnable task) {
        ClientTaskScheduler scheduler = activeClientTaskScheduler;
        if (scheduler == null) {
            task.run();
        } else {
            scheduler.schedule(task);
        }
    }

    public static final class ClientEvents {

        private final ClientTaskScheduler clientTaskScheduler;

        public ClientEvents() {
            this(new MinecraftClientTaskScheduler());
        }

        ClientEvents(ClientTaskScheduler clientTaskScheduler) {
            if (clientTaskScheduler == null) {
                throw new IllegalArgumentException("client task scheduler is required");
            }
            this.clientTaskScheduler = clientTaskScheduler;
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                HorizonRadioKeybinds.onClientTick();
                HorizonRadioClient.onClientTick();
            }
        }

        @SubscribeEvent
        public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
            clientTaskScheduler.schedule(new Runnable() {

                @Override
                public void run() {
                    HorizonRadioClient.sendClockSync();
                }
            });
        }

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            clientTaskScheduler.schedule(new Runnable() {

                @Override
                public void run() {
                    HorizonRadioClient.clearCache();
                }
            });
        }
    }
}
