package com.horizonradio.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketRoundTripTest {

    @Test
    public void roundTripsEveryPacketAndPreservesWireFields() {
        SearchRequestPacket search = roundTrip(new SearchRequestPacket("café"), new SearchRequestPacket());
        assertEquals("café", search.getQuery());

        AddToPlaylistPacket add = roundTrip(new AddToPlaylistPacket("id", "title", "3:21"), new AddToPlaylistPacket());
        assertEquals("id", add.getVideoId());
        assertEquals("title", add.getTitle());
        assertEquals("3:21", add.getDuration());
        AddChartsToPlaylistPacket addCharts = roundTrip(
            new AddChartsToPlaylistPacket(
                Arrays.asList(
                    new AddChartsToPlaylistPacket.Entry("id", "title", ""),
                    new AddChartsToPlaylistPacket.Entry("id-2", "title-2", "2:00"))),
            new AddChartsToPlaylistPacket());
        assertEquals(
            2,
            addCharts.getEntries()
                .size());
        AddChartsToPlaylistPacket removeCharts = roundTrip(
            new AddChartsToPlaylistPacket(Arrays.asList(new AddChartsToPlaylistPacket.Entry("id", "title", "")), true),
            new AddChartsToPlaylistPacket());
        assertTrue(removeCharts.isRemove());

        RemoveFromPlaylistPacket remove = roundTrip(new RemoveFromPlaylistPacket("id"), new RemoveFromPlaylistPacket());
        assertEquals("id", remove.getVideoId());
        roundTrip(new ClearPlaylistPacket(), new ClearPlaylistPacket());

        ReadyPacket ready = roundTrip(new ReadyPacket("id"), new ReadyPacket());
        assertEquals("id", ready.getVideoId());

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

        List<PlaylistSyncPacket.Entry> entries = Arrays.asList(
            new PlaylistSyncPacket.Entry("id-1", "One", "1:00", "Alice"),
            new PlaylistSyncPacket.Entry("id-2", "Two", "2:00", "Bob"));
        PlaylistSyncPacket playlist = roundTrip(new PlaylistSyncPacket(entries), new PlaylistSyncPacket());
        assertEquals(entries, playlist.getEntries());

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

        ResumePacket resume = roundTrip(new ResumePacket(987654321L), new ResumePacket());
        assertEquals(987654321L, resume.getPositionMs());
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
    public void rejectsInvalidAudioChunkIndex() {
        new AudioChunkPacket("id", "title", 2, 2, 0L, new byte[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsExcessiveAudioChunkCount() {
        new AudioChunkPacket("id", "title", 0, AudioChunkPacket.MAX_CHUNKS + 1, 0L, new byte[0]);
    }

    @Test
    public void registersAllMessagesFromCommonCodeWithExpectedSides() throws IOException {
        String source = normalizeSource(readSource("src/main/java/com/horizonradio/network/HorizonRadioNetwork.java"));
        assertTrue(source.contains("NetworkRegistry.INSTANCE.newSimpleChannel(HorizonRadioProtocol.CHANNEL_NAME)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.SearchRequestHandler.class, SearchRequestPacket.class, 0, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.ImportPlaylistHandler.class, ImportPlaylistPacket.class, 19, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.ImportVideoHandler.class, ImportVideoPacket.class, 20, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.RequestChartsHandler.class, RequestChartsPacket.class, 21, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.AddToPlaylistHandler.class, AddToPlaylistPacket.class, 1, Side.SERVER)"));
        assertTrue(source.contains("registerMessage(ServerMessageHandlers.AddChartsToPlaylistHandler.class"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.RemoveFromPlaylistHandler.class, RemoveFromPlaylistPacket.class, 2, Side.SERVER)"));
        assertTrue(
            source.contains(
                "registerMessage(ServerMessageHandlers.ReadyHandler.class, ReadyPacket.class, 3, Side.SERVER)"));
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
                "registerMessage(ClientboundMessageHandlers.SearchResultsHandler.class, SearchResultsPacket.class, 4, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.PlaylistSyncHandler.class, PlaylistSyncPacket.class, 5, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.AudioChunkHandler.class, AudioChunkPacket.class, 6, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.NowPlayingHandler.class, NowPlayingPacket.class, 7, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.PauseHandler.class, PausePacket.class, 8, Side.CLIENT)"));
        assertTrue(
            source.contains(
                "registerMessage(ClientboundMessageHandlers.ResumeHandler.class, ResumePacket.class, 9, Side.CLIENT)"));
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
    }

    @Test
    public void clientTransportUsesForgeChannel() throws IOException {
        String source = readSource("src/main/java/com/horizonradio/client/HorizonRadioClient.java");
        assertTrue(source.contains("CHANNEL.sendToServer(new SearchRequestPacket(query))"));
        assertTrue(source.contains("CHANNEL.sendToServer(new AddToPlaylistPacket(videoId, title, duration))"));
        assertTrue(source.contains("CHANNEL.sendToServer(new RemoveFromPlaylistPacket(videoId))"));
        assertTrue(source.contains("CHANNEL.sendToServer(new ReadyPacket(videoId))"));
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
