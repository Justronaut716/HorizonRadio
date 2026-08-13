package com.horizonradio.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AddToPlaylistPacket;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.ChartAddCompletionPacket;
import com.horizonradio.network.packets.ClearPlaylistPacket;
import com.horizonradio.network.packets.ClockSyncRequestPacket;
import com.horizonradio.network.packets.ClockSyncResponsePacket;
import com.horizonradio.network.packets.ImportPlaylistPacket;
import com.horizonradio.network.packets.ImportVideoPacket;
import com.horizonradio.network.packets.LoopStatePacket;
import com.horizonradio.network.packets.NowPlayingPacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlayNowPacket;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistResyncRequestPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.PreviousTrackPacket;
import com.horizonradio.network.packets.RadioAudioChunkPacket;
import com.horizonradio.network.packets.RadioAudioStartPacket;
import com.horizonradio.network.packets.RadioSearchRequestPacket;
import com.horizonradio.network.packets.RadioSearchResultsPacket;
import com.horizonradio.network.packets.RadioStatePacket;
import com.horizonradio.network.packets.ReadyPacket;
import com.horizonradio.network.packets.RemoveFromPlaylistPacket;
import com.horizonradio.network.packets.ReorderPlaylistPacket;
import com.horizonradio.network.packets.RequestChartsPacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SearchRequestPacket;
import com.horizonradio.network.packets.SearchResultsPacket;
import com.horizonradio.network.packets.SeekRequestPacket;
import com.horizonradio.network.packets.SelectRadioStationPacket;
import com.horizonradio.network.packets.ShuffleStatePacket;
import com.horizonradio.network.packets.SkipTrackPacket;
import com.horizonradio.network.packets.StopRadioPacket;
import com.horizonradio.network.packets.ToggleLoopPacket;
import com.horizonradio.network.packets.TogglePlaybackPacket;
import com.horizonradio.network.packets.ToggleShufflePacket;
import com.horizonradio.network.packets.TrackSyncPacket;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketRoundTripTest {

    @Test
    public void roundTripsProductionRadioSelectionPackets() {
        RadioSearchRequestPacket search = roundTrip(
            new RadioSearchRequestPacket("ambient caf\u00e9"),
            new RadioSearchRequestPacket());
        assertEquals("ambient caf\u00e9", search.getQuery());

        SelectRadioStationPacket select = roundTrip(
            new SelectRadioStationPacket("station-uuid"),
            new SelectRadioStationPacket());
        assertEquals("station-uuid", select.getStationUuid());
        roundTrip(new StopRadioPacket(), new StopRadioPacket());
    }

    @Test
    public void roundTripsCompatibilityOnlyRadioResultAndRelaySerializers() {

        List<RadioSearchResultsPacket.Entry> radioEntries = Arrays.asList(
            new RadioSearchResultsPacket.Entry("station-1", "Station One"),
            new RadioSearchResultsPacket.Entry("station-2", "Station Two"));
        RadioSearchResultsPacket radioResults = roundTrip(
            new RadioSearchResultsPacket(radioEntries),
            new RadioSearchResultsPacket());
        assertEquals(radioEntries, radioResults.getEntries());

        RadioStatePacket state = roundTrip(
            new RadioStatePacket(true, 7L, "station-uuid", "Station Name", "LIVE"),
            new RadioStatePacket());
        assertTrue(state.isActive());
        assertEquals(7L, state.getGeneration());
        assertEquals("station-uuid", state.getStationUuid());
        assertEquals("Station Name", state.getStationName());
        assertEquals("LIVE", state.getStatus());
        assertFalse(state.isMusicMode());

        RadioStatePacket musicState = roundTrip(
            new RadioStatePacket(false, 8L, "station-uuid", "Station Name", "", true),
            new RadioStatePacket());
        assertTrue(musicState.isMusicMode());

        RadioAudioStartPacket start = roundTrip(
            new RadioAudioStartPacket(7L, 42L, 44100, 2, 16, false),
            new RadioAudioStartPacket());
        assertEquals(7L, start.getGeneration());
        assertEquals(42L, start.getFirstSequence());
        assertEquals(44100, start.getSampleRate());
        assertEquals(2, start.getChannels());
        assertEquals(16, start.getSampleSizeInBits());
        assertFalse(start.isBigEndian());

        byte[] audio = new byte[PacketBufferUtil.MAX_BYTE_ARRAY_BYTES];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i * 17);
        }
        RadioAudioChunkPacket chunk = roundTrip(new RadioAudioChunkPacket(7L, 43L, audio), new RadioAudioChunkPacket());
        assertEquals(7L, chunk.getGeneration());
        assertEquals(43L, chunk.getSequence());
        assertArrayEquals(audio, chunk.getData());
    }

    @Test
    public void roundTripsIdOnlySnapshotsDeltasAndSourceAwareSync() {
        List<PlaylistSyncPacket.Entry> entries = Arrays.asList(
            new PlaylistSyncPacket.Entry(MediaSourceType.YOUTUBE, "video-id", "Alice"),
            new PlaylistSyncPacket.Entry(MediaSourceType.RADIO, "station-id", "Bob"));
        PlaylistSyncPacket snapshot = roundTrip(
            new PlaylistSyncPacket(12L, true, false, entries),
            new PlaylistSyncPacket());
        assertEquals(12L, snapshot.getQueueRevision());
        assertEquals(entries, snapshot.getEntries());
        assertEquals("station-id", snapshot.getEntries().get(1).getSourceId());
        ByteBuf snapshotBytes = Unpooled.buffer();
        new PlaylistSyncPacket(12L, true, false, entries).toBytes(snapshotBytes);
        assertEquals(43, snapshotBytes.readableBytes());

        PlaylistDeltaPacket.Entry entry = new PlaylistDeltaPacket.Entry(
            MediaSourceType.YOUTUBE,
            "next-video",
            "Carol");
        PlaylistDeltaPacket add = roundTrip(PlaylistDeltaPacket.add(13L, entry, 2), new PlaylistDeltaPacket());
        assertEquals(PlaylistDeltaPacket.Operation.ADD, add.getOperation());
        assertEquals(entry, add.getEntry());
        assertEquals(2, add.getIndex());
        assertEquals(
            3,
            roundTrip(PlaylistDeltaPacket.remove(14L, 3), new PlaylistDeltaPacket()).getIndex());
        PlaylistDeltaPacket move = roundTrip(PlaylistDeltaPacket.move(15L, 1, 4), new PlaylistDeltaPacket());
        assertEquals(1, move.getIndex());
        assertEquals(4, move.getTargetIndex());
        assertEquals(
            PlaylistDeltaPacket.Operation.CLEAR,
            roundTrip(PlaylistDeltaPacket.clear(16L), new PlaylistDeltaPacket()).getOperation());
        assertEquals(
            entries.size(),
            roundTrip(PlaylistDeltaPacket.replace(17L, Arrays.asList(
                new PlaylistDeltaPacket.Entry(MediaSourceType.YOUTUBE, "video-id", "Alice"),
                new PlaylistDeltaPacket.Entry(MediaSourceType.RADIO, "station-id", "Bob"))), new PlaylistDeltaPacket())
                .getEntries()
                .size());

        TrackSyncPacket radio = roundTrip(TrackSyncPacket.radio(18L, "station-id"), new TrackSyncPacket());
        assertEquals(MediaSourceType.RADIO, radio.getSourceType());
        assertEquals("station-id", radio.getSourceId());
        assertEquals(18L, radio.getGeneration());
        assertEquals(0L, radio.getPositionMs());
        assertEquals(0L, radio.getStartAtMs());
        assertFalse(radio.isPaused());
        ByteBuf radioBytes = Unpooled.buffer();
        TrackSyncPacket.radio(18L, "station-id").toBytes(radioBytes);
        assertEquals(20, radioBytes.readableBytes());
    }

    @Test
    public void roundTripsEveryPacketAndPreservesWireFields() {
        SearchRequestPacket search = roundTrip(new SearchRequestPacket("café"), new SearchRequestPacket());
        assertEquals("café", search.getQuery());

        AddToPlaylistPacket add = roundTrip(new AddToPlaylistPacket("id", 201_000L), new AddToPlaylistPacket());
        assertEquals("id", add.getVideoId());
        assertEquals(201_000L, add.getDurationMs());
        assertEquals("id", add.getTitle());

        PlayNowPacket playNow = roundTrip(new PlayNowPacket("id", 201_000L), new PlayNowPacket());
        assertEquals("id", playNow.getVideoId());
        assertEquals(201_000L, playNow.getDurationMs());
        assertEquals("id", playNow.getTitle());
        AddChartsToPlaylistPacket addCharts = roundTrip(
            new AddChartsToPlaylistPacket(
                Arrays.asList(
                    new AddChartsToPlaylistPacket.Entry("id", 1L),
                    new AddChartsToPlaylistPacket.Entry("id-2", 120_000L))),
            new AddChartsToPlaylistPacket());
        assertEquals(
            2,
            addCharts.getEntries()
                .size());
        assertEquals("id", addCharts.getEntries().get(0).getTitle());
        AddChartsToPlaylistPacket removeCharts = roundTrip(
            new AddChartsToPlaylistPacket(Arrays.asList(new AddChartsToPlaylistPacket.Entry("id", 1L)), true),
            new AddChartsToPlaylistPacket());
        assertTrue(removeCharts.isRemove());

        RemoveFromPlaylistPacket remove = roundTrip(new RemoveFromPlaylistPacket("id"), new RemoveFromPlaylistPacket());
        assertEquals("id", remove.getVideoId());
        roundTrip(new ClearPlaylistPacket(), new ClearPlaylistPacket());

        ReadyPacket ready = roundTrip(new ReadyPacket("id"), new ReadyPacket());
        assertEquals("id", ready.getVideoId());

        ClockSyncRequestPacket clockRequest = roundTrip(
            new ClockSyncRequestPacket(1_234L),
            new ClockSyncRequestPacket());
        assertEquals(1_234L, clockRequest.getClientSentAtMs());
        ClockSyncResponsePacket clockResponse = roundTrip(
            new ClockSyncResponsePacket(1_234L, 6_400L, 6_500L),
            new ClockSyncResponsePacket());
        assertEquals(1_234L, clockResponse.getClientSentAtMs());
        assertEquals(6_400L, clockResponse.getServerReceivedAtMs());
        assertEquals(6_500L, clockResponse.getServerSentAtMs());

        ImportPlaylistPacket importPlaylist = roundTrip(
            new ImportPlaylistPacket("https://youtu.be/id?list=PLtest"),
            new ImportPlaylistPacket());
        assertEquals("https://youtu.be/id?list=PLtest", importPlaylist.getPlaylistUrl());
        ImportVideoPacket importVideo = roundTrip(
            new ImportVideoPacket("https://youtu.be/id"),
            new ImportVideoPacket());
        assertEquals("https://youtu.be/id", importVideo.getVideoUrl());
        RequestChartsPacket chartsRequest = roundTrip(new RequestChartsPacket(true), new RequestChartsPacket());
        assertTrue(chartsRequest.isForceRefresh());
        RequestChartsPacket regionalChartsRequest = roundTrip(
            new RequestChartsPacket("US", true),
            new RequestChartsPacket());
        assertEquals("US", regionalChartsRequest.getRegionCode());
        assertTrue(regionalChartsRequest.isForceRefresh());

        ReorderPlaylistPacket reorder = roundTrip(new ReorderPlaylistPacket(3, 1), new ReorderPlaylistPacket());
        assertEquals(3, reorder.getFromIndex());
        assertEquals(1, reorder.getTargetIndex());

        SeekRequestPacket seek = roundTrip(new SeekRequestPacket(0.75f), new SeekRequestPacket());
        assertEquals(0.75f, seek.getProgress(), 0.0f);

        roundTrip(new TogglePlaybackPacket(), new TogglePlaybackPacket());
        roundTrip(new SkipTrackPacket(), new SkipTrackPacket());
        roundTrip(new PreviousTrackPacket(), new PreviousTrackPacket());
        roundTrip(new ToggleLoopPacket(), new ToggleLoopPacket());
        LoopStatePacket loopState = roundTrip(new LoopStatePacket(true), new LoopStatePacket());
        assertTrue(loopState.isLooping());
        roundTrip(new ToggleShufflePacket(), new ToggleShufflePacket());
        ShuffleStatePacket shuffleState = roundTrip(new ShuffleStatePacket(true), new ShuffleStatePacket());
        assertTrue(shuffleState.isShuffling());

        List<SearchResultsPacket.Entry> results = Arrays.asList(
            new SearchResultsPacket.Entry("id-1", "One", "Channel A", "1:00", "thumb-1"),
            new SearchResultsPacket.Entry("id-2", "Two", "Channel B", "2:00", "thumb-2"));
        SearchResultsPacket searchResults = roundTrip(new SearchResultsPacket(results), new SearchResultsPacket());
        assertEquals(results, searchResults.getResults());
        SearchResultsPacket chartResults = roundTrip(new SearchResultsPacket(results, true), new SearchResultsPacket());
        assertTrue(chartResults.isCharts());
        SearchResultsPacket regionalChartResults = roundTrip(
            new SearchResultsPacket(Collections.<SearchResultsPacket.Entry>emptyList(), true, "GLOBAL"),
            new SearchResultsPacket());
        assertTrue(regionalChartResults.isCharts());
        assertEquals("GLOBAL", regionalChartResults.getChartRegionCode());

        PlaylistResyncRequestPacket resync = roundTrip(
            new PlaylistResyncRequestPacket(12L),
            new PlaylistResyncRequestPacket());
        assertEquals(12L, resync.getKnownRevision());

        List<String> completedChartIds = Arrays.asList("chart-1", "chart-2");
        ChartAddCompletionPacket chartCompletion = roundTrip(
            new ChartAddCompletionPacket(completedChartIds),
            new ChartAddCompletionPacket());
        assertEquals(completedChartIds, chartCompletion.getCompletedVideoIds());

        byte[] audio = new byte[AudioChunkPacket.CHUNK_SIZE];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i * 31);
        }
        for (long offset : new long[] { 0L, -1L }) {
            AudioChunkPacket chunk = roundTrip(
                new AudioChunkPacket("id", "title", 1, 2, offset, audio),
                new AudioChunkPacket());
            assertEquals("id", chunk.getVideoId());
            assertEquals("title", chunk.getTitle());
            assertEquals(1, chunk.getChunkIndex());
            assertEquals(2, chunk.getTotalChunks());
            assertEquals(offset, chunk.getStartOffsetMs());
            assertArrayEquals(audio, chunk.getData());
        }

        NowPlayingPacket nowPlaying = roundTrip(new NowPlayingPacket("title", 0.75f), new NowPlayingPacket());
        assertEquals("title", nowPlaying.getTitle());
        assertEquals(0.75f, nowPlaying.getProgress(), 0.0f);

        PausePacket pause = roundTrip(new PausePacket(123456789L), new PausePacket());
        assertEquals(123456789L, pause.getPositionMs());

        ResumePacket resume = roundTrip(new ResumePacket(987654321L, 987654999L), new ResumePacket());
        assertEquals(987654321L, resume.getPositionMs());
        assertEquals(987654999L, resume.getStartAtMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedStringBeforeAllocation() {
        ByteBuf buf = Unpooled.buffer();
        PacketBufferUtil.writeString(buf, repeat('x', PacketBufferUtil.MAX_STRING_BYTES + 1));
    }

    @Test
    public void preservesUtf8StringBytes() {
        String value = "caf" + (char) 0x00e9;
        SearchRequestPacket decoded = roundTrip(new SearchRequestPacket(value), new SearchRequestPacket());
        assertEquals(value, decoded.getQuery());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedCount() {
        ByteBuf buf = Unpooled.buffer();
        PacketBufferUtil.writeCount(buf, PacketBufferUtil.MAX_COLLECTION_SIZE + 1);
        new SearchResultsPacket().fromBytes(buf);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedAudioArrayBeforeAllocation() {
        ByteBuf buf = Unpooled.buffer();
        PacketBufferUtil.writeString(buf, "id");
        PacketBufferUtil.writeString(buf, "title");
        buf.writeInt(0);
        buf.writeInt(1);
        buf.writeLong(0L);
        PacketBufferUtil.writeCount(buf, AudioChunkPacket.CHUNK_SIZE + 1);
        new AudioChunkPacket().fromBytes(buf);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRadioSearchQueryOverCharacterLimit() {
        new RadioSearchRequestPacket(repeat('q', 101));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRadioStationUuidOverByteLimitBeforeAllocation() {
        ByteBuf buf = Unpooled.buffer();
        PacketBufferUtil.writeString(buf, repeat('u', 65));
        new SelectRadioStationPacket().fromBytes(buf);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRadioSearchResultsOverEntryLimitBeforeAllocation() {
        ByteBuf buf = Unpooled.buffer();
        PacketBufferUtil.writeCount(buf, 51);
        new RadioSearchResultsPacket().fromBytes(buf);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRadioStationNameOverByteLimit() {
        new RadioSearchResultsPacket.Entry("station", repeat('n', 201));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRadioStatusOverByteLimit() {
        new RadioStatePacket(true, 1L, "station", "Station", repeat('s', 161));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedRadioAudioDataBeforeAllocation() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeLong(1L);
        buf.writeLong(2L);
        ByteBufUtils.writeVarInt(buf, PacketBufferUtil.MAX_BYTE_ARRAY_BYTES + 1, 5);
        new RadioAudioChunkPacket().fromBytes(buf);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedRadioAudioDataInConstructor() {
        new RadioAudioChunkPacket(1L, 2L, new byte[PacketBufferUtil.MAX_BYTE_ARRAY_BYTES + 1]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidAudioChunkIndex() {
        new AudioChunkPacket("id", "title", 2, 2, 0L, new byte[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsExcessiveAudioChunkCount() {
        new AudioChunkPacket("id", "title", 0, AudioChunkPacket.MAX_CHUNKS + 1, 0L, new byte[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownDurationForFiniteQueueMutation() {
        new AddToPlaylistPacket("id", 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownDurationForChartQueueMutation() {
        new AddChartsToPlaylistPacket.Entry("id", 0L);
    }

    @Test
    public void registersAllMessagesFromCommonCodeWithExpectedSides() throws IOException {
        String source = normalizeSource(readSource("src/main/java/com/horizonradio/network/HorizonRadioNetwork.java"));
        assertTrue(source.contains("NetworkRegistry.INSTANCE.newSimpleChannel(HorizonRadioProtocol.CHANNEL_NAME)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.AddToPlaylistHandler.class, AddToPlaylistPacket.class, 1, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.PlayNowHandler.class, PlayNowPacket.class, 24, Side.SERVER)"));
        assertTrue(source.contains("registerMessage(ServerMessageHandlers.AddChartsToPlaylistHandler.class"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.RemoveFromPlaylistHandler.class, RemoveFromPlaylistPacket.class, 2, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.ReorderPlaylistHandler.class, ReorderPlaylistPacket.class, 10, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.SeekRequestHandler.class, SeekRequestPacket.class, 11, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.TogglePlaybackHandler.class, TogglePlaybackPacket.class, 12, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.SkipTrackHandler.class, SkipTrackPacket.class, 13, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.PreviousTrackHandler.class, PreviousTrackPacket.class, 14, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.ToggleLoopHandler.class, ToggleLoopPacket.class, 15, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.LoopStateHandler.class, LoopStatePacket.class, 16, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.ToggleShuffleHandler.class, ToggleShufflePacket.class, 17, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.ShuffleStateHandler.class, ShuffleStatePacket.class, 18, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.PlaylistSyncHandler.class, PlaylistSyncPacket.class, 5, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.PauseHandler.class, PausePacket.class, 8, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.ResumeHandler.class, ResumePacket.class, 9, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.SelectRadioStationHandler.class, SelectRadioStationPacket.class, 26, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.StopRadioHandler.class, StopRadioPacket.class, 27, Side.SERVER)"));
        assertEquals(1, countOccurrences(source, "SelectRadioStationPacket.class, 26, Side.SERVER"));
        assertEquals(1, countOccurrences(source, "StopRadioPacket.class, 27, Side.SERVER"));
        assertFalse(source.contains("SearchRequestHandler.class"));
        assertFalse(source.contains("ImportPlaylistHandler.class"));
        assertFalse(source.contains("ImportVideoHandler.class"));
        assertFalse(source.contains("RequestChartsHandler.class"));
        assertFalse(source.contains("RadioSearchRequestHandler.class"));
        assertFalse(source.contains("ReadyHandler.class"));
        assertFalse(source.contains("SearchResultsHandler.class"));
        assertFalse(source.contains("AudioChunkHandler.class"));
        assertFalse(source.contains("NowPlayingHandler.class"));
        assertFalse(source.contains("RadioSearchResultsHandler.class"));
        assertFalse(source.contains("RadioStateHandler.class"));
        assertFalse(source.contains("RadioAudioStartHandler.class"));
        assertFalse(source.contains("RadioAudioChunkHandler.class"));
        assertFalse(source.contains("AudioChunkPacket.class"));
        assertFalse(source.contains("NowPlayingPacket.class"));
        assertFalse(source.contains("RadioSearchResultsPacket.class"));
        assertFalse(source.contains("RadioStatePacket.class"));
        assertFalse(source.contains("RadioAudioStartPacket.class"));
        assertFalse(source.contains("RadioAudioChunkPacket.class"));
        assertEquals(24, countOccurrences(source, "registerMessage("));
        assertEquals(1, countOccurrences(source, "registerMessages()"));
    }

    @Test
    public void clientboundHandlerHasNoClientOnlyImports() throws IOException {
        String source = readSource("src/main/java/com/horizonradio/network/ClientboundMessageHandlers.java");
        assertTrue(source.contains("HorizonRadio.proxy"));
        assertTrue(!source.contains("net.minecraft.client"));
        assertTrue(!source.contains("org.lwjgl"));
        assertTrue(!source.contains("javax.sound"));
        assertTrue(!source.contains("ClientProxy"));
        assertTrue(!source.contains("cpw.mods.fml.client"));
        assertTrue(source.contains("HorizonRadio.proxy.handlePlaylistDelta(message)"));
    }

    @Test
    public void commonHandlersAndClientLifecycleDoNotRetainLegacyMediaTraffic() throws IOException {
        String serverHandlers = readSource("src/main/java/com/horizonradio/network/ServerMessageHandlers.java");
        String client = readSource("src/main/java/com/horizonradio/client/HorizonRadioClient.java");
        String proxy = readSource("src/main/java/com/horizonradio/client/ClientProxy.java");
        String radioBrowser = readSource("src/main/java/com/horizonradio/server/RadioBrowserService.java");

        assertFalse(serverHandlers.contains("net.minecraft.client"));
        assertFalse(serverHandlers.contains("org.lwjgl"));
        assertFalse(serverHandlers.contains("javax.sound"));
        assertFalse(serverHandlers.contains("ClientProxy"));
        assertFalse(client.contains("AudioChunkPacket"));
        assertFalse(client.contains("ChartAddCompletionPacket"));
        assertFalse(client.contains("RadioSearchResultsPacket"));
        assertFalse(proxy.contains("AudioChunkPacket"));
        assertFalse(proxy.contains("ChartAddCompletionPacket"));
        assertFalse(proxy.contains("handleChartAddCompletion"));
        assertFalse(proxy.contains("NowPlayingPacket"));
        assertFalse(proxy.contains("RadioSearchResultsPacket"));
        assertFalse(proxy.contains("SearchResultsPacket"));
        assertFalse(radioBrowser.contains("RadioSearchResultsPacket"));
        assertFalse(radioBrowser.contains("RadioStatePacket"));
    }

    @Test
    public void radioServerHandlersAuthenticateAndScheduleThroughServerExecutor() throws IOException {
        String source = readSource("src/main/java/com/horizonradio/network/ServerMessageHandlers.java");
        assertTrue(source.contains("final EntityPlayerMP player = player(context)"));
        assertTrue(source.contains("ServerThreadExecutor.execute(server, task)"));
        assertTrue(source.contains("hook.handleSelectRadio(player, message.getStationUuid())"));
        assertTrue(source.contains("hook.handleStopRadio(player)"));
        assertTrue(source.contains("hook.handlePlaylistResyncRequest(player, message.getKnownRevision())"));
    }

    @Test
    public void clientTransportUsesForgeChannel() throws IOException {
        String source = readSource("src/main/java/com/horizonradio/client/HorizonRadioClient.java");
        assertTrue(source.contains("CHANNEL.sendToServer(new AddToPlaylistPacket(videoId, durationMs))"));
        assertTrue(source.contains("CHANNEL.sendToServer(new PlayNowPacket(videoId, durationMs))"));
        assertTrue(source.contains("CHANNEL.sendToServer(new RemoveFromPlaylistPacket(videoId))"));
        assertFalse(source.contains("CHANNEL.sendToServer(new SearchRequestPacket(query))"));
        assertFalse(source.contains("CHANNEL.sendToServer(new RequestChartsPacket(regionCode, forceRefresh))"));
        assertFalse(source.contains("CHANNEL.sendToServer(new ImportPlaylistPacket(playlistUrl))"));
        assertFalse(source.contains("CHANNEL.sendToServer(new ImportVideoPacket(videoUrl))"));
        assertFalse(source.contains("CHANNEL.sendToServer(new ReadyPacket(videoId))"));
        assertFalse(source.contains("CHANNEL.sendToServer(new RadioSearchRequestPacket(query))"));
    }

    @Test
    public void doesNotRegisterChartAddCompletionAsClientboundMessage() throws IOException {
        String source = normalizeSource(readSource("src/main/java/com/horizonradio/network/HorizonRadioNetwork.java"));
        assertFalse(source.contains("ClientboundMessageHandlers.ChartAddCompletionHandler.class"));
        assertFalse(source.contains("ChartAddCompletionPacket.class"));
        assertTrue(source.contains("ClockSyncRequestPacket.class, 33, Side.SERVER"));
        assertTrue(source.contains("ClockSyncResponsePacket.class, 34, Side.CLIENT"));
        assertTrue(source.contains("PlaylistDeltaPacket.class, 36, Side.CLIENT"));
        assertTrue(source.contains("PlaylistResyncRequestPacket.class, 37, Side.SERVER"));
    }

    private static <T extends cpw.mods.fml.common.network.simpleimpl.IMessage> T roundTrip(T source, T target) {
        ByteBuf buf = Unpooled.buffer();
        source.toBytes(buf);
        target.fromBytes(buf);
        return target;
    }

    private static String repeat(char value, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static String readSource(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), Charset.forName("UTF-8"));
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String normalizeSource(String source) {
        return source.replaceAll("\\s+", " ")
            .replaceAll("\\s*\\.\\s*", ".")
            .replaceAll("\\(\\s+", "(")
            .replaceAll("\\s+\\)", ")")
            .trim();
    }
}
