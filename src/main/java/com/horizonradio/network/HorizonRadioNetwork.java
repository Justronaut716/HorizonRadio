package com.horizonradio.network;

import com.horizonradio.core.protocol.HorizonRadioProtocol;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AddToPlaylistPacket;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.ClearPlaylistPacket;
import com.horizonradio.network.packets.ImportPlaylistPacket;
import com.horizonradio.network.packets.ImportVideoPacket;
import com.horizonradio.network.packets.LoopStatePacket;
import com.horizonradio.network.packets.NowPlayingPacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.PreviousTrackPacket;
import com.horizonradio.network.packets.ReadyPacket;
import com.horizonradio.network.packets.RemoveFromPlaylistPacket;
import com.horizonradio.network.packets.ReorderPlaylistPacket;
import com.horizonradio.network.packets.RequestChartsPacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SearchRequestPacket;
import com.horizonradio.network.packets.SearchResultsPacket;
import com.horizonradio.network.packets.SeekRequestPacket;
import com.horizonradio.network.packets.ShuffleStatePacket;
import com.horizonradio.network.packets.SkipTrackPacket;
import com.horizonradio.network.packets.ToggleLoopPacket;
import com.horizonradio.network.packets.TogglePlaybackPacket;
import com.horizonradio.network.packets.ToggleShufflePacket;

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
            ServerMessageHandlers.SearchRequestHandler.class,
            SearchRequestPacket.class,
            0,
            Side.SERVER);
        CHANNEL.registerMessage(
            ServerMessageHandlers.ImportPlaylistHandler.class,
            ImportPlaylistPacket.class,
            19,
            Side.SERVER);
        CHANNEL
            .registerMessage(ServerMessageHandlers.ImportVideoHandler.class, ImportVideoPacket.class, 20, Side.SERVER);
        CHANNEL.registerMessage(
            ServerMessageHandlers.RequestChartsHandler.class,
            RequestChartsPacket.class,
            21,
            Side.SERVER);
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
        CHANNEL.registerMessage(ServerMessageHandlers.ReadyHandler.class, ReadyPacket.class, 3, Side.SERVER);
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
            ClientboundMessageHandlers.SearchResultsHandler.class,
            SearchResultsPacket.class,
            4,
            Side.CLIENT);
        CHANNEL.registerMessage(
            ClientboundMessageHandlers.PlaylistSyncHandler.class,
            PlaylistSyncPacket.class,
            5,
            Side.CLIENT);
        CHANNEL.registerMessage(
            ClientboundMessageHandlers.AudioChunkHandler.class,
            AudioChunkPacket.class,
            6,
            Side.CLIENT);
        CHANNEL.registerMessage(
            ClientboundMessageHandlers.NowPlayingHandler.class,
            NowPlayingPacket.class,
            7,
            Side.CLIENT);
        CHANNEL.registerMessage(ClientboundMessageHandlers.PauseHandler.class, PausePacket.class, 8, Side.CLIENT);
        CHANNEL.registerMessage(ClientboundMessageHandlers.ResumeHandler.class, ResumePacket.class, 9, Side.CLIENT);
        registered = true;
    }
}
