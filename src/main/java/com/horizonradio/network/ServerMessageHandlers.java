package com.horizonradio.network;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AddToPlaylistPacket;
import com.horizonradio.network.packets.ClearPlaylistPacket;
import com.horizonradio.network.packets.ImportPlaylistPacket;
import com.horizonradio.network.packets.ImportVideoPacket;
import com.horizonradio.network.packets.PreviousTrackPacket;
import com.horizonradio.network.packets.ReadyPacket;
import com.horizonradio.network.packets.RemoveFromPlaylistPacket;
import com.horizonradio.network.packets.ReorderPlaylistPacket;
import com.horizonradio.network.packets.RequestChartsPacket;
import com.horizonradio.network.packets.SearchRequestPacket;
import com.horizonradio.network.packets.SeekRequestPacket;
import com.horizonradio.network.packets.SkipTrackPacket;
import com.horizonradio.network.packets.ToggleLoopPacket;
import com.horizonradio.network.packets.TogglePlaybackPacket;
import com.horizonradio.network.packets.ToggleShufflePacket;
import com.horizonradio.server.ServerThreadExecutor;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

public final class ServerMessageHandlers {

    private static volatile ServerPacketHook hook = new NoOpServerPacketHook();

    private ServerMessageHandlers() {}

    public static void setHook(ServerPacketHook newHook) {
        hook = newHook == null ? new NoOpServerPacketHook() : newHook;
    }

    private static EntityPlayerMP player(MessageContext context) {
        if (context == null || context.getServerHandler() == null) {
            return null;
        }
        return context.getServerHandler().playerEntity;
    }

    private static void schedule(final Runnable task) {
        MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        if (server != null) {
            ServerThreadExecutor.execute(server, task);
        }
    }

    public interface ServerPacketHook {

        void handleSearch(EntityPlayerMP player, String query);

        void handleImportPlaylist(EntityPlayerMP player, String playlistUrl);

        void handleImportVideo(EntityPlayerMP player, String videoUrl);

        void handleRequestCharts(EntityPlayerMP player, boolean forceRefresh);

        void handleAdd(EntityPlayerMP player, String videoId, String title, String duration);

        void handleAddCharts(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries, boolean remove);

        void handleRemove(EntityPlayerMP player, String videoId);

        void handleClearPlaylist(EntityPlayerMP player);

        void handleReady(EntityPlayerMP player, String videoId);

        void handleReorder(EntityPlayerMP player, int fromIndex, int targetIndex);

        void handleSeek(EntityPlayerMP player, float progress);

        void handleTogglePlayback(EntityPlayerMP player);

        void handleSkipTrack(EntityPlayerMP player);

        void handlePreviousTrack(EntityPlayerMP player);

        void handleToggleLoop(EntityPlayerMP player);

        void handleToggleShuffle(EntityPlayerMP player);
    }

    private static final class NoOpServerPacketHook implements ServerPacketHook {

        @Override
        public void handleSearch(EntityPlayerMP player, String query) {}

        @Override
        public void handleImportPlaylist(EntityPlayerMP player, String playlistUrl) {}

        @Override
        public void handleImportVideo(EntityPlayerMP player, String videoUrl) {}

        @Override
        public void handleRequestCharts(EntityPlayerMP player, boolean forceRefresh) {}

        @Override
        public void handleAdd(EntityPlayerMP player, String videoId, String title, String duration) {}

        @Override
        public void handleAddCharts(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries,
            boolean remove) {}

        @Override
        public void handleRemove(EntityPlayerMP player, String videoId) {}

        @Override
        public void handleClearPlaylist(EntityPlayerMP player) {}

        @Override
        public void handleReady(EntityPlayerMP player, String videoId) {}

        @Override
        public void handleReorder(EntityPlayerMP player, int fromIndex, int targetIndex) {}

        @Override
        public void handleSeek(EntityPlayerMP player, float progress) {}

        @Override
        public void handleTogglePlayback(EntityPlayerMP player) {}

        @Override
        public void handleSkipTrack(EntityPlayerMP player) {}

        @Override
        public void handlePreviousTrack(EntityPlayerMP player) {}

        @Override
        public void handleToggleLoop(EntityPlayerMP player) {}

        @Override
        public void handleToggleShuffle(EntityPlayerMP player) {}
    }

    public static final class SearchRequestHandler implements IMessageHandler<SearchRequestPacket, IMessage> {

        @Override
        public IMessage onMessage(final SearchRequestPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleSearch(player, message.getQuery());
                    }
                });
            }
            return null;
        }
    }

    public static final class ImportPlaylistHandler implements IMessageHandler<ImportPlaylistPacket, IMessage> {

        @Override
        public IMessage onMessage(final ImportPlaylistPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleImportPlaylist(player, message.getPlaylistUrl());
                    }
                });
            }
            return null;
        }
    }

    public static final class ImportVideoHandler implements IMessageHandler<ImportVideoPacket, IMessage> {

        @Override
        public IMessage onMessage(final ImportVideoPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleImportVideo(player, message.getVideoUrl());
                    }
                });
            }
            return null;
        }
    }

    public static final class RequestChartsHandler implements IMessageHandler<RequestChartsPacket, IMessage> {

        @Override
        public IMessage onMessage(final RequestChartsPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleRequestCharts(player, message.isForceRefresh());
                    }
                });
            }
            return null;
        }
    }

    public static final class AddToPlaylistHandler implements IMessageHandler<AddToPlaylistPacket, IMessage> {

        @Override
        public IMessage onMessage(final AddToPlaylistPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleAdd(player, message.getVideoId(), message.getTitle(), message.getDuration());
                    }
                });
            }
            return null;
        }
    }

    public static final class AddChartsToPlaylistHandler
        implements IMessageHandler<AddChartsToPlaylistPacket, IMessage> {

        @Override
        public IMessage onMessage(final AddChartsToPlaylistPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleAddCharts(player, message.getEntries(), message.isRemove());
                    }
                });
            }
            return null;
        }
    }

    public static final class RemoveFromPlaylistHandler implements IMessageHandler<RemoveFromPlaylistPacket, IMessage> {

        @Override
        public IMessage onMessage(final RemoveFromPlaylistPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleRemove(player, message.getVideoId());
                    }
                });
            }
            return null;
        }
    }

    public static final class ClearPlaylistHandler implements IMessageHandler<ClearPlaylistPacket, IMessage> {

        @Override
        public IMessage onMessage(final ClearPlaylistPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleClearPlaylist(player);
                    }
                });
            }
            return null;
        }
    }

    public static final class ReadyHandler implements IMessageHandler<ReadyPacket, IMessage> {

        @Override
        public IMessage onMessage(final ReadyPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleReady(player, message.getVideoId());
                    }
                });
            }
            return null;
        }
    }

    public static final class ReorderPlaylistHandler implements IMessageHandler<ReorderPlaylistPacket, IMessage> {

        @Override
        public IMessage onMessage(final ReorderPlaylistPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleReorder(player, message.getFromIndex(), message.getTargetIndex());
                    }
                });
            }
            return null;
        }
    }

    public static final class SeekRequestHandler implements IMessageHandler<SeekRequestPacket, IMessage> {

        @Override
        public IMessage onMessage(final SeekRequestPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleSeek(player, message.getProgress());
                    }
                });
            }
            return null;
        }
    }

    public static final class TogglePlaybackHandler implements IMessageHandler<TogglePlaybackPacket, IMessage> {

        @Override
        public IMessage onMessage(final TogglePlaybackPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleTogglePlayback(player);
                    }
                });
            }
            return null;
        }
    }

    public static final class SkipTrackHandler implements IMessageHandler<SkipTrackPacket, IMessage> {

        @Override
        public IMessage onMessage(final SkipTrackPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleSkipTrack(player);
                    }
                });
            }
            return null;
        }
    }

    public static final class PreviousTrackHandler implements IMessageHandler<PreviousTrackPacket, IMessage> {

        @Override
        public IMessage onMessage(final PreviousTrackPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handlePreviousTrack(player);
                    }
                });
            }
            return null;
        }
    }

    public static final class ToggleLoopHandler implements IMessageHandler<ToggleLoopPacket, IMessage> {

        @Override
        public IMessage onMessage(final ToggleLoopPacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleToggleLoop(player);
                    }
                });
            }
            return null;
        }
    }

    public static final class ToggleShuffleHandler implements IMessageHandler<ToggleShufflePacket, IMessage> {

        @Override
        public IMessage onMessage(final ToggleShufflePacket message, final MessageContext context) {
            final EntityPlayerMP player = player(context);
            if (player != null) {
                schedule(new Runnable() {

                    @Override
                    public void run() {
                        hook.handleToggleShuffle(player);
                    }
                });
            }
            return null;
        }
    }
}
