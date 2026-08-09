package com.horizonradio.core.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

import org.junit.Test;

import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.server.PlaylistManager;

public class PlaylistStateTest {

    @Test
    public void preservesOrderWithoutEnforcingConfiguredMaximum() {
        PlaylistState state = new PlaylistState(2);

        assertTrue(state.add(entry("one", "Alice")));
        assertTrue(state.add(entry("two", "Bob")));
        assertTrue(state.add(entry("three", "Carol")));

        assertEquals(
            Arrays.asList(entry("one", "Alice"), entry("two", "Bob"), entry("three", "Carol")),
            state.snapshot());
    }

    @Test
    public void onlyTheOwnerCanRemoveAnEntry() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("one", "Alice"));

        assertEquals(-1, state.removeOwned("one", "Bob"));
        assertEquals(0, state.removeOwned("one", "Alice"));
        assertTrue(
            state.snapshot()
                .isEmpty());
    }

    @Test
    public void currentRemovalShiftsIndexAndResetsAnEmptyQueue() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("one", "Alice"));
        state.add(entry("two", "Bob"));
        state.add(entry("three", "Carol"));
        state.startTrack(1, "two", 1000L, 42L);

        assertEquals(0, state.removeOwned("one", "Alice"));
        assertEquals(0, state.getCurrentIndex());
        assertEquals("two", state.getCurrentVideoId());

        assertEquals(0, state.removeOwned("two", "Bob"));
        assertEquals(-1, state.getCurrentIndex());
        assertFalse(state.isPlaying());
        assertNull(state.getCurrentVideoId());
        assertEquals(
            1,
            state.snapshot()
                .size());

        assertEquals(0, state.removeOwned("three", "Carol"));
        assertEquals(-1, state.getCurrentIndex());
        assertFalse(state.isPlaying());
        assertTrue(
            state.snapshot()
                .isEmpty());
    }

    @Test
    public void completedCurrentTrackIsRemovedBeforeTheNextTrackStarts() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("one", "Alice"));
        state.add(entry("two", "Bob"));
        state.add(entry("three", "Carol"));
        state.startTrack(0, "one", 1000L, 42L);

        assertEquals(
            "one",
            state.removeCurrent()
                .getVideoId());
        assertEquals(-1, state.getCurrentIndex());
        assertFalse(state.isPlaying());
        assertEquals(Arrays.asList(entry("two", "Bob"), entry("three", "Carol")), state.snapshot());

        PlaylistEntry next = state.advanceToNext(2000L);
        assertEquals("two", next.getVideoId());
        assertEquals(0, state.getCurrentIndex());
    }

    @Test
    public void immediateSelectionMovesQueuedTrackToFrontAndPreservesOtherQueueEntries() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("current", "Alice"));
        state.add(entry("next", "Bob"));
        state.add(entry("selected", "Carol"));
        state.add(entry("last", "Dave"));
        state.startTrack(0, "current", 120_000L, 0L);

        PlaylistEntry selected = state.prepareImmediatePlayback(state.get(2));

        assertEquals(entry("selected", "Carol"), selected);
        assertEquals(
            Arrays.asList(entry("selected", "Carol"), entry("next", "Bob"), entry("last", "Dave")),
            state.snapshot());
        assertEquals(-1, state.getCurrentIndex());
        assertFalse(state.isPlaying());
        assertEquals(
            "current",
            state.takeLastTrack()
                .getVideoId());
    }

    @Test
    public void immediateSelectionOfCurrentTrackResetsPlaybackAndPreservesLastTrack() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("prior", "Alice"));
        state.startTrack(0, "prior", 120_000L, 0L);
        state.removeCurrent();

        PlaylistEntry current = entry("current", "Bob");
        state.add(current);
        PlaylistEntry next = entry("next", "Carol");
        state.add(next);
        state.startTrack(0, "current", 120_000L, 0L);

        PlaylistEntry selected = state.prepareImmediatePlayback(current);

        assertEquals(current, selected);
        assertEquals(Arrays.asList(current, next), state.snapshot());
        assertEquals(0, state.findIndex("current"));
        assertEquals(-1, state.getCurrentIndex());
        assertFalse(state.isPlaying());
        assertNull(state.getCurrentVideoId());
        assertEquals(
            "prior",
            state.takeLastTrack()
                .getVideoId());
    }

    @Test
    public void queuedEntriesCanBeMovedWithoutMovingTheCurrentTrack() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("current", "Alice"));
        state.add(entry("two", "Bob"));
        state.add(entry("three", "Carol"));
        state.startTrack(0, "current", 1000L, 42L);

        assertTrue(state.moveQueued(2, 1));
        assertEquals(
            Arrays.asList(entry("current", "Alice"), entry("three", "Carol"), entry("two", "Bob")),
            state.snapshot());
        assertEquals(0, state.getCurrentIndex());
        assertFalse(state.moveQueued(0, 2));
    }

    @Test
    public void durationParserUsesThreeMinuteFallback() {
        assertEquals(3L * 60L * 1000L, PlaylistState.parseDuration(null));
        assertEquals(3L * 60L * 1000L, PlaylistState.parseDuration("not-a-duration"));
        assertEquals(3L * 60L * 1000L, PlaylistState.parseDuration(""));
        assertEquals(3L * 60L * 1000L, PlaylistState.parseDuration("-1:00"));
        assertEquals(1L * 60L * 60L * 1000L + 2L * 60L * 1000L + 3L * 1000L, PlaylistState.parseDuration("1:02:03"));
    }

    @Test
    public void searchDurationLimitExcludesFifteenMinutesAndLonger() {
        long limit = 15L * 60L * 1000L;

        assertTrue(PlaylistManager.isSearchDurationAllowed("14:59", limit));
        assertFalse(PlaylistManager.isSearchDurationAllowed("15:00", limit));
        assertFalse(PlaylistManager.isSearchDurationAllowed("1:00:00", limit));
        assertFalse(PlaylistManager.isSearchDurationAllowed("unknown", limit));
    }

    @Test
    public void oversizedAudioIsRejectedBeforePacketConstruction() {
        long maximum = (long) AudioChunkPacket.CHUNK_SIZE * AudioChunkPacket.MAX_CHUNKS;

        assertTrue(PlaylistManager.supportsAudioLength(maximum));
        assertFalse(PlaylistManager.supportsAudioLength(maximum + 1L));
    }

    @Test
    public void seekUpdatesTheSharedPlaybackClockAndClampsToTrackLength() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("one", "Alice"));
        state.startTrack(0, "one", 120_000L, 10_000L);
        state.markLoaded("one", 10_000L);

        assertEquals(30_000L, state.seek(30_000L, 100_000L));
        assertEquals(70_000L, state.getPlaybackStartTime());
        assertEquals(119_999L, state.seek(200_000L, 200_000L));
        assertEquals(80_001L, state.getPlaybackStartTime());
    }

    @Test
    public void pauseAndResumePreserveTheSharedPlaybackPosition() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("one", "Alice"));
        state.startTrack(0, "one", 120_000L, 70_000L);
        state.markLoaded("one", 70_000L);

        assertEquals(30_000L, state.pausePlayback(30_000L, 100_000L));
        assertTrue(state.isPaused());
        assertEquals(30_000L, state.resumePlayback(110_000L));
        assertFalse(state.isPaused());
        assertEquals(80_000L, state.getPlaybackStartTime());
    }

    @Test
    public void remembersTheRemovedTrackAndCanPutItBackAtTheFront() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("current", "Bob"));
        state.add(entry("next", "Alice"));
        state.startTrack(0, "current", 120_000L, 0L);
        state.markLoaded("current", 0L);

        assertEquals(
            "current",
            state.removeCurrent()
                .getVideoId());
        assertEquals(
            "current",
            state.takeLastTrack()
                .getVideoId());
        state.addAtFront(entry("current", "Bob"));
        assertEquals(
            "current",
            state.get(0)
                .getVideoId());
        assertEquals(
            "next",
            state.get(1)
                .getVideoId());
    }

    @Test
    public void loopModeCanBeToggledAndSurvivesTrackAdvances() {
        PlaylistState state = new PlaylistState(5);
        assertTrue(state.toggleLooping());
        assertTrue(state.isLooping());
        state.add(entry("one", "Alice"));
        state.advanceToNext(120_000L);
        assertTrue(state.isLooping());
        assertFalse(state.toggleLooping());
    }

    @Test
    public void shuffleModeCanBeToggledAndKeepsTheCurrentTrackFixed() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("current", "Alice"));
        state.add(entry("two", "Bob"));
        state.add(entry("three", "Carol"));
        state.startTrack(0, "current", 120_000L, 0L);

        assertTrue(state.toggleShuffling());
        state.shuffleQueued();
        assertEquals(
            "current",
            state.get(0)
                .getVideoId());
        assertTrue(state.isShuffling());
        assertFalse(state.toggleShuffling());
    }

    @Test
    public void lateJoinReadyTimeoutAndDisconnectTransitionsAreDeterministic() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("one", "Alice"));
        state.startTrack(0, "one", 10_000L, 1_000L);
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertTrue(state.beginLateJoin(first, 2_500L, 2_000L));
        assertFalse(state.beginLateJoin(second, 9_000L, 2_100L));
        assertTrue(state.isSyncing());
        assertEquals(2_500L, state.getPausedPositionMs());
        assertEquals(2, state.getPendingPlayerCount());

        assertFalse(state.ready(first, "other-track"));
        assertFalse(state.ready(first, "one"));
        assertEquals(1, state.getPendingPlayerCount());
        assertTrue(state.disconnect(second));
        assertTrue(state.isSyncing());
        assertEquals(0, state.getPendingPlayerCount());

        state.resume(3_000L);
        assertFalse(state.isSyncing());
        assertEquals(2_000L, state.getPlaybackStartTime());
        assertFalse(state.ready(first, "one"));

        assertTrue(state.beginLateJoin(first, 4_000L, 5_000L));
        assertTrue(state.forceResume());
        assertFalse(state.isSyncing());
    }

    @Test
    public void stoppingPlaybackPreservesPlaylistButClearsLateJoinState() {
        PlaylistState state = new PlaylistState(5);
        state.add(entry("one", "Alice"));
        state.startTrack(0, "one", 10_000L, 1_000L);
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000003");
        assertTrue(state.beginLateJoin(player, 2_500L, 2_000L));

        state.stopPlayback();

        assertEquals(1, state.size());
        assertFalse(state.isPlaying());
        assertFalse(state.isSyncing());
        assertEquals(0, state.getPendingPlayerCount());
        assertEquals(-1, state.getCurrentIndex());
        assertEquals(null, state.getCurrentVideoId());
    }

    @Test
    public void strictForgeAndJavaEightScopeIsVisibleInTheStepThreeSources() throws IOException {
        String manager = read("src/main/java/com/horizonradio/server/PlaylistManager.java");
        String state = read("src/main/java/com/horizonradio/core/server/PlaylistState.java");
        String commonProxy = read("src/main/java/com/horizonradio/CommonProxy.java");
        String horizonRadio = read("src/main/java/com/horizonradio/HorizonRadio.java");
        String serverEvents = read("src/main/java/com/horizonradio/server/ServerEvents.java");
        String handlers = read("src/main/java/com/horizonradio/network/ServerMessageHandlers.java");
        String network = normalizeSource(read("src/main/java/com/horizonradio/network/HorizonRadioNetwork.java"));

        assertTrue(state.contains("CopyOnWriteArrayList"));
        assertTrue(manager.contains("EntityPlayerMP"));
        assertTrue(manager.contains("playerEntityList"));
        assertTrue(manager.contains("getCommandSenderName()"));
        assertTrue(manager.contains("getUniqueID()"));
        assertTrue(manager.contains("new ChatComponentText"));
        assertTrue(manager.contains("EnumChatFormatting"));
        assertTrue(manager.contains("ServerThreadExecutor.execute"));
        assertTrue(manager.contains("HorizonRadioNetwork.CHANNEL.sendTo"));
        assertTrue(manager.contains("AudioChunkPacket.CHUNK_SIZE"));
        assertTrue(manager.contains("-1L"));

        for (String source : Arrays.asList(manager, state, commonProxy, horizonRadio, serverEvents, handlers)) {
            assertFalse(source.contains("ServerPlayerEntity"));
            assertFalse(source.contains("getPlayerManager"));
            assertFalse(source.contains("server.execute"));
            assertFalse(source.contains("net." + "fabricmc"));
            assertFalse(source.contains("net.minecraft.text"));
            assertFalse(source.contains("import net.minecraft.util.Formatting"));
            assertFalse(source.contains("import com.horizonradio.client"));
            assertFalse(source.contains(" var "));
            assertFalse(source.contains("re" + "cord "));
            assertFalse(source.contains("Path." + "of("));
            assertFalse(source.contains("Files." + "readString("));
        }

        assertTrue(commonProxy.contains("new YouTubeService()"));
        assertTrue(commonProxy.contains("new AudioDownloadService"));
        assertTrue(commonProxy.contains("new PlaylistManager"));
        assertTrue(commonProxy.contains("ServerMessageHandlers.setHook"));
        assertTrue(commonProxy.contains("handleSearch"));
        assertTrue(commonProxy.contains("handleAdd"));
        assertTrue(commonProxy.contains("handlePlayNow"));
        assertTrue(commonProxy.contains("handleRemove"));
        assertTrue(commonProxy.contains("handleReady"));
        assertTrue(commonProxy.contains("ServerMessageHandlers.setHook(null)"));
        assertTrue(handlers.contains("ServerThreadExecutor.execute"));
        assertTrue(handlers.contains("PlayNowHandler"));
        assertTrue(manager.contains("handlePlayNow"));
        assertTrue(manager.contains("prepareImmediatePlayback"));

        assertTrue(network.contains("AddToPlaylistPacket.class, 1, Side.SERVER"));
        assertTrue(network.contains("RemoveFromPlaylistPacket.class, 2, Side.SERVER"));
        assertTrue(network.contains("ReadyPacket.class, 3, Side.SERVER"));
        assertTrue(network.contains("PlaylistSyncPacket.class, 5, Side.CLIENT"));
        assertTrue(network.contains("AudioChunkPacket.class, 6, Side.CLIENT"));
        assertTrue(network.contains("NowPlayingPacket.class, 7, Side.CLIENT"));
        assertTrue(network.contains("PausePacket.class, 8, Side.CLIENT"));
        assertTrue(network.contains("ResumePacket.class, 9, Side.CLIENT"));
    }

    @Test
    public void exceptionalDownloadCompletionHasAQueuedFailurePath() throws IOException {
        String manager = read("src/main/java/com/horizonradio/server/PlaylistManager.java");

        assertTrue(manager.contains("whenComplete(new BiConsumer<Path, Throwable>()"));
        assertTrue(manager.contains("downloadFailure"));
        assertTrue(manager.contains("downloadFailed(generation, selectedEntry, selectedIndex)"));
    }

    @Test
    public void successfulDownloadFileRuntimeFailuresStayOnQueuedFailurePath() throws IOException {
        String manager = read("src/main/java/com/horizonradio/server/PlaylistManager.java");
        int callbackStart = manager.indexOf("public void accept(final Path filePath, Throwable downloadFailure)");
        int callbackEnd = manager.indexOf("private void downloadFailed", callbackStart);
        assertTrue(callbackStart >= 0);
        assertTrue(callbackEnd > callbackStart);

        String callbackBody = manager.substring(callbackStart, callbackEnd);
        int tryIndex = callbackBody.indexOf("try {");
        int validationIndex = callbackBody.indexOf("Files.isRegularFile(filePath)");
        int readIndex = callbackBody.indexOf("readAudioBytes(filePath)");
        int runtimeCatchIndex = callbackBody.indexOf("catch (RuntimeException exception)");

        assertTrue(tryIndex >= 0);
        assertTrue(tryIndex < validationIndex);
        assertTrue(validationIndex < readIndex);
        assertTrue(runtimeCatchIndex > readIndex);
        String runtimeFailureBody = callbackBody.substring(runtimeCatchIndex);
        assertTrue(runtimeFailureBody.contains("enqueueServerTask(new Runnable()"));
        assertTrue(runtimeFailureBody.contains("downloadFailed(generation, selectedEntry, selectedIndex)"));
    }

    @Test
    public void currentRemovalResumesPausedPlayersBeforeStateRemoval() throws IOException {
        String manager = read("src/main/java/com/horizonradio/server/PlaylistManager.java");
        int resumeIndex = manager.indexOf("resumePausedClientsBeforeCurrentRemoval");
        int removeIndex = manager.indexOf("int removeIndex = state.remove");

        assertTrue(manager.contains("state.findIndex(videoId)"));
        assertTrue(resumeIndex >= 0);
        assertTrue(removeIndex >= 0);
        assertTrue(resumeIndex < removeIndex);
        assertTrue(manager.contains("new ResumePacket(pausedPositionMs)"));
    }

    @Test
    public void lateJoinTimeoutIsScheduledOncePerSyncCycle() throws IOException {
        String manager = read("src/main/java/com/horizonradio/server/PlaylistManager.java");
        int scheduleStart = manager.indexOf("private void scheduleSyncTimeout()");
        int scheduleEnd = manager.indexOf("private void doResume()", scheduleStart);
        assertTrue(scheduleStart >= 0);
        assertTrue(scheduleEnd > scheduleStart);

        String scheduleBody = manager.substring(scheduleStart, scheduleEnd);
        assertTrue(scheduleBody.contains("syncTimeoutFuture != null"));
        assertFalse(scheduleBody.contains("cancelFuture(syncTimeoutFuture)"));
    }

    @Test
    public void lateJoinLookupAndReadRuntimeFailuresRemovePendingSafely() throws IOException {
        String manager = read("src/main/java/com/horizonradio/server/PlaylistManager.java");
        int requestStart = manager.indexOf("private void requestLateJoinAudio");
        int requestEnd = manager.indexOf("private void enqueueLateJoinFailure", requestStart);
        assertTrue(requestStart >= 0);
        assertTrue(requestEnd > requestStart);

        String requestBody = manager.substring(requestStart, requestEnd);
        assertTrue(requestBody.contains("catch (IOException exception)"));
        assertTrue(requestBody.contains("catch (RuntimeException exception)"));
        assertTrue(requestBody.contains("enqueueLateJoinFailure(playerUuid, videoId)"));
    }

    private static PlaylistEntry entry(String videoId, String addedBy) {
        return new PlaylistEntry(videoId, videoId + " title", "1:00", addedBy);
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), Charset.forName("UTF-8"));
    }

    private static String normalizeSource(String source) {
        return source.replaceAll("\\s+", " ")
            .replaceAll("\\s*\\.\\s*", ".")
            .replaceAll("\\(\\s+", "(")
            .replaceAll("\\s+\\)", ")")
            .trim();
    }
}
