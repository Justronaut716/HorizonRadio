package com.horizonradio.network;

import com.horizonradio.HorizonRadio;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.ChartAddCompletionPacket;
import com.horizonradio.network.packets.ClockSyncResponsePacket;
import com.horizonradio.network.packets.LoopStatePacket;
import com.horizonradio.network.packets.NowPlayingPacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.RadioAudioChunkPacket;
import com.horizonradio.network.packets.RadioAudioStartPacket;
import com.horizonradio.network.packets.RadioSearchResultsPacket;
import com.horizonradio.network.packets.RadioStatePacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SearchResultsPacket;
import com.horizonradio.network.packets.ShuffleStatePacket;
import com.horizonradio.network.packets.TrackSyncPacket;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

public final class ClientboundMessageHandlers {

    private ClientboundMessageHandlers() {}

    public static final class SearchResultsHandler implements IMessageHandler<SearchResultsPacket, IMessage> {

        @Override
        public IMessage onMessage(SearchResultsPacket message, MessageContext context) {
            if (message.isCharts()) {
                HorizonRadio.proxy.handleChartResults(message);
            } else {
                HorizonRadio.proxy.handleSearchResults(message);
            }
            return null;
        }
    }

    public static final class PlaylistSyncHandler implements IMessageHandler<PlaylistSyncPacket, IMessage> {

        @Override
        public IMessage onMessage(PlaylistSyncPacket message, MessageContext context) {
            HorizonRadio.proxy.handlePlaylistSync(message);
            return null;
        }
    }

    public static final class PlaylistDeltaHandler implements IMessageHandler<PlaylistDeltaPacket, IMessage> {

        @Override
        public IMessage onMessage(PlaylistDeltaPacket message, MessageContext context) {
            HorizonRadio.proxy.handlePlaylistDelta(message);
            return null;
        }
    }

    public static final class ChartAddCompletionHandler implements IMessageHandler<ChartAddCompletionPacket, IMessage> {

        @Override
        public IMessage onMessage(ChartAddCompletionPacket message, MessageContext context) {
            HorizonRadio.proxy.handleChartAddCompletion(message);
            return null;
        }
    }

    public static final class ClockSyncResponseHandler implements IMessageHandler<ClockSyncResponsePacket, IMessage> {

        @Override
        public IMessage onMessage(ClockSyncResponsePacket message, MessageContext context) {
            HorizonRadio.proxy.handleClockSync(message);
            return null;
        }
    }

    public static final class AudioChunkHandler implements IMessageHandler<AudioChunkPacket, IMessage> {

        @Override
        public IMessage onMessage(AudioChunkPacket message, MessageContext context) {
            HorizonRadio.proxy.handleAudioChunk(message);
            return null;
        }
    }

    public static final class TrackSyncHandler implements IMessageHandler<TrackSyncPacket, IMessage> {

        @Override
        public IMessage onMessage(TrackSyncPacket message, MessageContext context) {
            HorizonRadio.proxy.handleTrackSync(message);
            return null;
        }
    }

    public static final class NowPlayingHandler implements IMessageHandler<NowPlayingPacket, IMessage> {

        @Override
        public IMessage onMessage(NowPlayingPacket message, MessageContext context) {
            HorizonRadio.proxy.handleNowPlaying(message);
            return null;
        }
    }

    public static final class PauseHandler implements IMessageHandler<PausePacket, IMessage> {

        @Override
        public IMessage onMessage(PausePacket message, MessageContext context) {
            HorizonRadio.proxy.handlePause(message);
            return null;
        }
    }

    public static final class ResumeHandler implements IMessageHandler<ResumePacket, IMessage> {

        @Override
        public IMessage onMessage(ResumePacket message, MessageContext context) {
            HorizonRadio.proxy.handleResume(message);
            return null;
        }
    }

    public static final class LoopStateHandler implements IMessageHandler<LoopStatePacket, IMessage> {

        @Override
        public IMessage onMessage(LoopStatePacket message, MessageContext context) {
            HorizonRadio.proxy.handleLoopState(message.isLooping());
            return null;
        }
    }

    public static final class ShuffleStateHandler implements IMessageHandler<ShuffleStatePacket, IMessage> {

        @Override
        public IMessage onMessage(ShuffleStatePacket message, MessageContext context) {
            HorizonRadio.proxy.handleShuffleState(message.isShuffling());
            return null;
        }
    }

    public static final class RadioSearchResultsHandler implements IMessageHandler<RadioSearchResultsPacket, IMessage> {

        @Override
        public IMessage onMessage(RadioSearchResultsPacket message, MessageContext context) {
            HorizonRadio.proxy.handleRadioSearchResults(message);
            return null;
        }
    }

    public static final class RadioStateHandler implements IMessageHandler<RadioStatePacket, IMessage> {

        @Override
        public IMessage onMessage(RadioStatePacket message, MessageContext context) {
            HorizonRadio.proxy.handleRadioState(message);
            return null;
        }
    }

    public static final class RadioAudioStartHandler implements IMessageHandler<RadioAudioStartPacket, IMessage> {

        @Override
        public IMessage onMessage(RadioAudioStartPacket message, MessageContext context) {
            HorizonRadio.proxy.handleRadioAudioStart(message);
            return null;
        }
    }

    public static final class RadioAudioChunkHandler implements IMessageHandler<RadioAudioChunkPacket, IMessage> {

        @Override
        public IMessage onMessage(RadioAudioChunkPacket message, MessageContext context) {
            HorizonRadio.proxy.handleRadioAudioChunk(message);
            return null;
        }
    }
}
