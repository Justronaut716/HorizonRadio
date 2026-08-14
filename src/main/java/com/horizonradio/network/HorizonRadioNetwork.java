package com.horizonradio.network;

import com.horizonradio.core.protocol.HorizonRadioProtocol;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AddToPlaylistPacket;
import com.horizonradio.network.packets.ClearPlaylistPacket;
import com.horizonradio.network.packets.ClockSyncRequestPacket;
import com.horizonradio.network.packets.ClockSyncResponsePacket;
import com.horizonradio.network.packets.LoopStatePacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlayNowPacket;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistResyncRequestPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.PreviousTrackPacket;
import com.horizonradio.network.packets.RemoveFromPlaylistPacket;
import com.horizonradio.network.packets.ReorderPlaylistPacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SeekRequestPacket;
import com.horizonradio.network.packets.SelectRadioStationPacket;
import com.horizonradio.network.packets.ShuffleStatePacket;
import com.horizonradio.network.packets.SkipTrackPacket;
import com.horizonradio.network.packets.StopRadioPacket;
import com.horizonradio.network.packets.ToggleLoopPacket;
import com.horizonradio.network.packets.TogglePlaybackPacket;
import com.horizonradio.network.packets.ToggleShufflePacket;
import com.horizonradio.network.packets.TrackSyncPacket;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class HorizonRadioNetwork {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE
        .newSimpleChannel(HorizonRadioProtocol.CHANNEL_NAME);

    private static boolean registered;

    private HorizonRadioNetwork() {}

    public static synchronized void registerMessages() {
        if (registered) {
            return;
        }
        CHANNEL.registerMessage(
            ServerMessageHandlers.AddToPlaylistHandler.class,
            AddToPlaylistPacket.class,
            1,
            Side.SERVER);
        CHANNEL.registerMessage(
            ServerMessageHandlers.AddChartsToPlaylistHandler.class,
            AddChartsToPlaylistPacket.class,
            22,
            Side.SERVER);
        CHANNEL.registerMessage(
            ServerMessageHandlers.RemoveFromPlaylistHandler.class,
            RemoveFromPlaylistPacket.class,
            2,
            Side.SERVER);
        CHANNEL.registerMessage(
            ServerMessageHandlers.ClearPlaylistHandler.class,
            ClearPlaylistPacket.class,
            23,
            Side.SERVER);
        CHANNEL.registerMessage(ServerMessageHandlers.PlayNowHandler.class, PlayNowPacket.class, 24, Side.SERVER);
        CHANNEL.registerMessage(
            ServerMessageHandlers.SelectRadioStationHandler.class,
            SelectRadioStationPacket.class,
            26,
            Side.SERVER);
        CHANNEL.registerMessage(ServerMessageHandlers.StopRadioHandler.class, StopRadioPacket.class, 27, Side.SERVER);
        CHANNEL.registerMessage(
            ServerMessageHandlers.ReorderPlaylistHandler.class,
            ReorderPlaylistPacket.class,
            10,
            Side.SERVER);
        CHANNEL
            .registerMessage(ServerMessageHandlers.SeekRequestHandler.class, SeekRequestPacket.class, 11, Side.SERVER);
        CHANNEL.registerMessage(
            ServerMessageHandlers.TogglePlaybackHandler.class,
            TogglePlaybackPacket.class,
            12,
            Side.SERVER);
        CHANNEL.registerMessage(ServerMessageHandlers.SkipTrackHandler.class, SkipTrackPacket.class, 13, Side.SERVER);
        CHANNEL.registerMessage(
            ServerMessageHandlers.PreviousTrackHandler.class,
            PreviousTrackPacket.class,
            14,
            Side.SERVER);
        CHANNEL.registerMessage(ServerMessageHandlers.ToggleLoopHandler.class, ToggleLoopPacket.class, 15, Side.SERVER);
        CHANNEL
            .registerMessage(ClientboundMessageHandlers.LoopStateHandler.class, LoopStatePacket.class, 16, Side.CLIENT);
        CHANNEL.registerMessage(
            ServerMessageHandlers.ToggleShuffleHandler.class,
            ToggleShufflePacket.class,
            17,
            Side.SERVER);
        CHANNEL.registerMessage(
            ClientboundMessageHandlers.ShuffleStateHandler.class,
            ShuffleStatePacket.class,
            18,
            Side.CLIENT);
        CHANNEL.registerMessage(
            ClientboundMessageHandlers.PlaylistSyncHandler.class,
            PlaylistSyncPacket.class,
            5,
            Side.CLIENT);
        CHANNEL.registerMessage(
            ClientboundMessageHandlers.PlaylistDeltaHandler.class,
            PlaylistDeltaPacket.class,
            36,
            Side.CLIENT);
        CHANNEL.registerMessage(
            ServerMessageHandlers.PlaylistResyncRequestHandler.class,
            PlaylistResyncRequestPacket.class,
            37,
            Side.SERVER);
        CHANNEL
            .registerMessage(ClientboundMessageHandlers.TrackSyncHandler.class, TrackSyncPacket.class, 35, Side.CLIENT);
        CHANNEL.registerMessage(ClientboundMessageHandlers.PauseHandler.class, PausePacket.class, 8, Side.CLIENT);
        CHANNEL.registerMessage(ClientboundMessageHandlers.ResumeHandler.class, ResumePacket.class, 9, Side.CLIENT);
        CHANNEL.registerMessage(
            ServerMessageHandlers.ClockSyncRequestHandler.class,
            ClockSyncRequestPacket.class,
            33,
            Side.SERVER);
        CHANNEL.registerMessage(
            ClientboundMessageHandlers.ClockSyncResponseHandler.class,
            ClockSyncResponsePacket.class,
            34,
            Side.CLIENT);
        registered = true;
    }
}
