# Client-Side Music and Radio Pipelines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans (recommended) to implement this plan task-by-task. Steps use (`[ ]`) syntax for tracking.

**Goal:** Make YouTube tracks and live radio client-side queue sources while keeping the server authoritative for queue order, timing, and controls with no server media traffic.

**Architecture:** The server stores source IDs, queue order, finite-track durations supplied by clients, and playback state. Clients perform YouTube/Radio Browser discovery, resolve queue metadata, download/decode finite audio, and open live-radio streams directly. The Minecraft protocol carries only compact queue snapshots/deltas, source-aware playback state, and control actions.

**Tech Stack:** Java 8-compatible Forge 1.7.10 mod, SimpleNetworkWrapper, Netty ByteBuf packets, CompletableFuture, Java Sound, existing standalone Java media backend, JUnit 4, Gradle Kotlin DSL, Spotless. The implementation must retain the existing Java 8 source compatibility.

## Global Constraints

- The server is authoritative for queue order, accepted mutations, playback source, playback generation, and finite-track timing.
- No normal music or radio audio bytes cross the Minecraft server connection.
- Finite music uses an absolute server start time and a finite duration.
- Radio uses a station UUID and live-edge playback without a start position.
- Radio is never paused or seeked like a finite file; volume remains local.
- Client metadata or audio failures remain local and never trigger a server media fallback.
- A client that detects a queue revision gap requests a snapshot before applying later deltas.
- Any asynchronous work from an older playback generation is ignored.
- Existing untracked user documents in the worktree must not be staged or modified.
- Every production behavior change follows the repository's red-green-refactor test cycle.

---

## Task 1: Introduce the source-aware queue model

**Files:**

- Create: `src/main/java/com/horizonradio/core/model/MediaSourceType.java`
- Modify: `src/main/java/com/horizonradio/core/model/PlaylistEntry.java`
- Modify: `src/main/java/com/horizonradio/core/server/PlaylistState.java`
- Test: `src/test/java/com/horizonradio/core/model/MediaSourceTypeTest.java`
- Test: `src/test/java/com/horizonradio/core/server/PlaylistStateTest.java`

**Interfaces:**

- `MediaSourceType` exposes exactly `YOUTUBE` and `RADIO`, with a stable byte wire value and a strict conversion method that rejects unknown values.
- `PlaylistEntry.youtube(String videoId, long durationMs, String addedBy)` creates a finite entry.
- `PlaylistEntry.radio(String stationUuid, String addedBy)` creates a live entry with no duration.
- `PlaylistEntry.getSourceType()`, `getSourceId()`, `getDurationMs()`, `getAddedBy()`, `isFinite()`, and `isRadio()` are the queue accessors required by later tasks. A client-side ID-only projection may use `durationMs == 0` to mean "not resolved yet"; the server accepts only a positive duration for a finite queue mutation or scheduled track.
- `PlaylistState.findIndex(MediaSourceType sourceType, String sourceId)` identifies an entry without relying on a title.
- `PlaylistState.getCurrentSourceType()` and `getCurrentSourceId()` expose the current source.
- `PlaylistState.startFiniteTrack(int index, String sourceId, long durationMs, long startAtMs)` starts a YouTube entry.
- `PlaylistState.startRadioTrack(int index, String stationUuid)` starts a radio entry without timing.
- `PlaylistState.getQueueRevision()` and `PlaylistState.markQueueMutation()` provide the monotonic queue revision.

- [ ] **Step 1: Write failing model tests**

```java
@Test
public void createsFiniteAndLiveEntriesWithoutDisplayMetadata() {
    PlaylistEntry song = PlaylistEntry.youtube("video-id", 180_000L, "Alice");
    PlaylistEntry radio = PlaylistEntry.radio("station-id", "Bob");

    assertEquals(MediaSourceType.YOUTUBE, song.getSourceType());
    assertEquals("video-id", song.getSourceId());
    assertEquals(180_000L, song.getDurationMs());
    assertTrue(song.isFinite());
    assertFalse(song.isRadio());

    assertEquals(MediaSourceType.RADIO, radio.getSourceType());
    assertEquals(0L, radio.getDurationMs());
    assertTrue(radio.isRadio());
}

@Test(expected = IllegalArgumentException.class)
public void rejectsDurationOnRadioEntry() {
    new PlaylistEntry(MediaSourceType.RADIO, "station-id", 1L, "Alice");
}
```

- [ ] **Step 2: Run the focused tests and verify the expected API/model failure**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.model.MediaSourceTypeTest --tests com.horizonradio.core.server.PlaylistStateTest
```

Expected: compilation or assertion failure because the source-aware entry API does not exist yet.

- [ ] **Step 3: Implement the source-aware model**

Replace title/duration-string storage in `PlaylistEntry` with source type, source ID, finite duration in milliseconds, and added-by name. Validate non-empty IDs, non-negative duration, and the rule that radio duration is zero. Allow zero for a finite client-side ID-only projection, but require a positive duration in server mutation/start validation. Update equality and `toString()` to use the new fields.

Update `PlaylistState` so current-track bookkeeping uses source type/source ID instead of `currentVideoId`. Keep finite pause/seek methods restricted to `YOUTUBE` entries. Add radio insertion/removal helpers that preserve queued finite entries.

- [ ] **Step 4: Run the focused tests and the existing state tests**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.model.MediaSourceTypeTest --tests com.horizonradio.core.server.PlaylistStateTest
```

Expected: PASS.

- [ ] **Step 5: Commit the queue model**

```bash
git add src/main/java/com/horizonradio/core/model/MediaSourceType.java src/main/java/com/horizonradio/core/model/PlaylistEntry.java src/main/java/com/horizonradio/core/server/PlaylistState.java src/test/java/com/horizonradio/core/model/MediaSourceTypeTest.java src/test/java/com/horizonradio/core/server/PlaylistStateTest.java
git commit -m "refactor: model playlist sources explicitly"
```

## Task 2: Define compact queue and source-sync packets

**Files:**

- Modify: `src/main/java/com/horizonradio/network/packets/PlaylistSyncPacket.java`
- Create: `src/main/java/com/horizonradio/network/packets/PlaylistDeltaPacket.java`
- Create: `src/main/java/com/horizonradio/network/packets/PlaylistResyncRequestPacket.java`
- Modify: `src/main/java/com/horizonradio/network/packets/AddToPlaylistPacket.java`
- Modify: `src/main/java/com/horizonradio/network/packets/PlayNowPacket.java`
- Modify: `src/main/java/com/horizonradio/network/packets/AddChartsToPlaylistPacket.java`
- Modify: `src/main/java/com/horizonradio/network/packets/TrackSyncPacket.java`
- Modify: `src/main/java/com/horizonradio/network/HorizonRadioNetwork.java`
- Modify: `src/main/java/com/horizonradio/network/ClientboundMessageHandlers.java`
- Modify: `src/main/java/com/horizonradio/network/ServerMessageHandlers.java`
- Test: `src/test/java/com/horizonradio/network/PlaylistDeltaPacketTest.java`
- Test: `src/test/java/com/horizonradio/network/PacketRoundTripTest.java`
- Test: `src/test/java/com/horizonradio/network/TrackSyncPacketTest.java`

**Interfaces:**

- `PlaylistSyncPacket(long queueRevision, boolean shuffling, boolean looping, List<Entry> entries)` contains only revision, shuffle/loop state, source type, source ID, and added-by name.
- `PlaylistDeltaPacket` has operations `ADD`, `REMOVE`, `MOVE`, `CLEAR`, and `REPLACE`, each carrying one revision. Use static factories `add`, `remove`, `move`, `clear`, and `replace` so invalid operation-specific fields cannot be constructed accidentally.
- `PlaylistResyncRequestPacket(long knownRevision)` is client-to-server and contains no queue contents.
- `AddToPlaylistPacket(String videoId, long durationMs)` and `PlayNowPacket(String videoId, long durationMs)` contain no title or formatted duration.
- `AddChartsToPlaylistPacket.Entry(String videoId, long durationMs)` contains no title.
- `TrackSyncPacket` carries `MediaSourceType sourceType`, `String sourceId`, `long generation`, `long positionMs`, `long startAtMs`, and `boolean paused`. Provide `youtube(...)` and `radio(...)` factories.
- Radio packets reject non-zero position, non-zero start time, or paused state. YouTube packets retain finite timing validation.
- TrackSync serialization writes `sourceType`, `sourceId`, and `generation` for both sources, then writes `positionMs`, `startAtMs`, and `paused` only for `YOUTUBE`; the on-wire `RADIO` form therefore contains only station UUID and generation after the source type.
- Register `PlaylistDeltaPacket` as clientbound ID 36 and `PlaylistResyncRequestPacket` as serverbound ID 37; retain all existing IDs unchanged for retained messages and never reuse an ID for a different message.

- [ ] **Step 1: Add failing round-trip and validation tests**

```java
@Test
public void playlistDeltaRoundTripsEachCompactOperation() {
    PlaylistDeltaPacket add = PlaylistDeltaPacket.add(
        9L,
        new PlaylistDeltaPacket.Entry(MediaSourceType.YOUTUBE, "video-id", "Alice"),
        2);
    PlaylistDeltaPacket decoded = roundTrip(add, new PlaylistDeltaPacket());

    assertEquals(9L, decoded.getQueueRevision());
    assertEquals(PlaylistDeltaPacket.Operation.ADD, decoded.getOperation());
    assertEquals(MediaSourceType.YOUTUBE, decoded.getEntry().getSourceType());
    assertEquals("video-id", decoded.getEntry().getSourceId());
    assertEquals(2, decoded.getIndex());
}

@Test
public void playlistSnapshotRoundTripsRevisionAndControlFlagsWithoutMetadata() {
    PlaylistSyncPacket packet = new PlaylistSyncPacket(
        12L,
        true,
        false,
        Arrays.asList(new PlaylistSyncPacket.Entry(MediaSourceType.RADIO, "station-id", "Alice")));
    PlaylistSyncPacket decoded = roundTrip(packet, new PlaylistSyncPacket());

    assertEquals(12L, decoded.getQueueRevision());
    assertTrue(decoded.isShuffling());
    assertFalse(decoded.isLooping());
    assertEquals(MediaSourceType.RADIO, decoded.getEntries().get(0).getSourceType());
}

@Test
public void radioTrackSyncHasNoFiniteTimingFields() {
    TrackSyncPacket packet = TrackSyncPacket.radio(4L, "station-id");
    assertEquals(MediaSourceType.RADIO, packet.getSourceType());
    assertEquals(0L, packet.getPositionMs());
    assertEquals(0L, packet.getStartAtMs());
    assertFalse(packet.isPaused());
}

@Test(expected = IllegalArgumentException.class)
public void rejectsRadioTrackSyncWithStartTime() {
    new TrackSyncPacket(MediaSourceType.RADIO, "station-id", 4L, 0L, 1L, false);
}
```

Add equivalent round-trip assertions for `REMOVE`, `MOVE`, `CLEAR`, and `REPLACE`, including an oversized `REPLACE` rejection before list allocation. The snapshot test must assert that no title, duration, thumbnail, or stream URL is serialized.

- [ ] **Step 2: Run the focused protocol tests and verify failure**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.network.PlaylistDeltaPacketTest --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.network.TrackSyncPacketTest
```

Expected: compilation failure for the missing packets and changed constructors.

- [ ] **Step 3: Implement bounded serialization**

Serialize source type as one byte, source IDs with the existing bounded string helper, and queue revisions/indices as primitive fields. Enforce the existing maximum playlist size in snapshot, replace, and delta decoding before allocating lists. Encode only fields required by each delta operation.

Update packet tests for the ID-only wire format and add source-aware TrackSync coverage. Update the handler registrations and add handlers that forward queue deltas to the client proxy and resync requests to the server hook.

- [ ] **Step 4: Run protocol tests and inspect packet sizes**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.network.PlaylistDeltaPacketTest --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.network.TrackSyncPacketTest
```

Expected: PASS, with no title, thumbnail, duration string, or audio byte field in queue/source-sync packets.

- [ ] **Step 5: Commit the compact protocol**

```bash
git add src/main/java/com/horizonradio/network src/test/java/com/horizonradio/network
git commit -m "feat: add compact source-aware queue protocol"
```

## Task 3: Implement direct client-side YouTube and Radio Browser access

**Files:**

- Create: `src/main/java/com/horizonradio/client/media/ClientMediaService.java`
- Create: `src/main/java/com/horizonradio/client/media/ClientMetadataCache.java`
- Modify: `src/main/java/com/horizonradio/server/RadioBrowserService.java`
- Modify: `src/main/java/com/horizonradio/server/AudioDownloadService.java`
- Test: `src/test/java/com/horizonradio/client/media/ClientMediaServiceTest.java`
- Test: `src/test/java/com/horizonradio/client/media/ClientMetadataCacheTest.java`

**Interfaces:**

`ClientMediaService` exposes these operations:

```java
CompletableFuture<List<SearchResult>> search(String query, long maxDurationMs);
CompletableFuture<List<SearchResult>> fetchCharts(ChartRegion region);
CompletableFuture<List<SearchResult>> importPlaylist(String playlistUrl);
CompletableFuture<SearchResult> importVideo(String videoUrl);
CompletableFuture<List<RadioStation>> searchRadio(String query);
CompletableFuture<RadioStation> lookupRadio(String stationUuid);
CompletableFuture<SearchResult> resolveVideo(String videoId);
```

The concrete service uses the existing Java InnerTube, metadata, playlist-parser, and Radio Browser implementations directly from the client. Its constructor accepts a `RemoteProvider` adapter with these exact methods:

```java
CompletableFuture<List<SearchResult>> search(String query, long maxDurationMs);
CompletableFuture<List<SearchResult>> fetchCharts(ChartRegion region);
CompletableFuture<String> extractPlaylistJson(String playlistUrl);
CompletableFuture<String> extractVideoJson(String videoUrl);
CompletableFuture<List<RadioStation>> searchRadio(String query);
CompletableFuture<RadioStation> lookupRadio(String stationUuid);
```

Production `RemoteProvider` delegates to `YouTubeService`, `AudioDownloadService`, and `RadioBrowserService`; test providers are deterministic. It must never call `HorizonRadioNetwork.CHANNEL`. Make `RadioBrowserService.sanitizeForPublication` public so the client can reject broken station records before creating a local stream.

`ClientMetadataCache` caches `SearchResult` by video ID and `RadioStation` by station UUID, de-duplicates concurrent lookups, and exposes a local loading/error state without contacting the server.

Define `ClientMediaService.RemoteProvider` with the six methods shown above. Define `ClientMetadataCache.MetadataProvider` with `resolveVideo(String)` and `lookupRadio(String)` returning the corresponding futures; `ClientMediaService` implements that provider in production. The cache constructor accepts only this provider, so its tests can use a deterministic fake without Minecraft networking.

- [ ] **Step 1: Write failing direct-access tests**

```java
@Test
public void searchUsesInjectedClientProviderAndReturnsMetadataLocally() throws Exception {
    FakeProvider provider = new FakeProvider();
    provider.searchResults = Arrays.asList(new SearchResult("video-id", "Title", "Channel", "1:00", "thumb"));

    ClientMediaService service = new ClientMediaService(provider);

    assertEquals(provider.searchResults, service.search("query", 900_000L).get());
    assertEquals("query", provider.lastQuery);
    assertEquals(900_000L, provider.lastMaxDurationMs);
}

@Test
public void metadataCacheSharesConcurrentVideoLookup() throws Exception {
    FakeMediaService service = new FakeMediaService();
    ClientMetadataCache cache = new ClientMetadataCache(service);

    CompletableFuture<SearchResult> first = cache.video("video-id");
    CompletableFuture<SearchResult> second = cache.video("video-id");

    assertSame(first, second);
    assertEquals(1, service.videoLookupCalls);
}
```

The test fixtures are part of these tests, not production code: `FakeProvider` implements `ClientMediaService.RemoteProvider` and records `lastQuery`, `lastMaxDurationMs`, and configured result futures; `FakeMediaService` implements `ClientMetadataCache.MetadataProvider`, increments `videoLookupCalls`, and returns one pending `CompletableFuture<SearchResult>` per distinct video ID.

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.client.media.ClientMediaServiceTest --tests com.horizonradio.client.media.ClientMetadataCacheTest
```

Expected: compilation failure because the client media classes and APIs do not exist.

- [ ] **Step 3: Implement the direct adapters**

Do not move the existing request/parser classes. Do not add a Minecraft packet call to a client media class. Reuse `PlaylistImportService.parse()` and `parseVideo()` for imported metadata, reuse `YouTubeService.search()` and `fetchTopCharts()` for direct client requests, and use `RadioBrowserService.search()` and `lookup()` for station discovery.

Add an `AudioDownloadService.resolveVideoMetadata(String videoId)` helper that builds a safe YouTube watch URL, resolves JSON locally, and parses one `SearchResult`. Keep audio cache/download behavior unchanged for the client cache. Do not change `YouTubeService`; its existing public search/chart methods are usable from the injected production adapter.

- [ ] **Step 4: Run the focused tests and existing parser/service tests**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.client.media.ClientMediaServiceTest --tests com.horizonradio.client.media.ClientMetadataCacheTest --tests com.horizonradio.server.YouTubeServiceTest --tests com.horizonradio.server.RadioBrowserServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit direct client discovery**

```bash
git add src/main/java/com/horizonradio/client/media src/main/java/com/horizonradio/server/RadioBrowserService.java src/main/java/com/horizonradio/server/AudioDownloadService.java src/test/java/com/horizonradio/client/media
git commit -m "feat: add direct client media discovery"
```

## Task 4: Add revisioned client queue state and local metadata rendering

**Files:**

- Create: `src/main/java/com/horizonradio/core/client/ClientQueueState.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/ClientProxy.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Test: `src/test/java/com/horizonradio/core/client/ClientQueueStateTest.java`
- Test: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`
- Test: `src/test/java/com/horizonradio/client/HorizonRadioClientConfigTest.java`

**Interfaces:**

`ClientQueueState` exposes:

```java
void applySnapshot(long revision, boolean shuffling, boolean looping, List<PlaylistEntry> entries);
boolean applyDelta(PlaylistDeltaPacket delta);
boolean isSnapshotRequired();
long getRevision();
boolean isShuffling();
boolean isLooping();
List<PlaylistEntry> snapshot();
```

It accepts a delta only when `delta.getQueueRevision() == revision + 1`. A gap or malformed operation leaves the current queue unchanged and sets `isSnapshotRequired()` to true. A snapshot replaces the queue and the server-authoritative shuffle/loop flags and clears the resync-required state.

`HorizonRadioClient` adds:

```java
public static synchronized void handlePlaylistSnapshot(PlaylistSyncPacket packet);
public static synchronized void handlePlaylistDelta(PlaylistDeltaPacket packet);
public static synchronized void requestPlaylistResync();
public static synchronized List<HorizonRadioScreen.PlaylistEntry> getCachedPlaylist();
```

The client transport changes to local operations for search, charts, imports, and radio search. Server-bound methods accept only IDs/durations:

```java
static final class PlaylistSelection {
    String videoId;
    long durationMs;
}

void sendAdd(String videoId, long durationMs);
void sendPlayNow(String videoId, long durationMs);
void sendAddChartsToPlaylist(List<PlaylistSelection> selections);
```

- [ ] **Step 1: Write failing revision tests**

```java
@Test
public void appliesOnlyContiguousDeltas() {
    ClientQueueState state = new ClientQueueState();
    state.applySnapshot(4L, false, false, entries("one"));

    assertTrue(state.applyDelta(PlaylistDeltaPacket.add(5L, entry("two"), 1)));
    assertFalse(state.applyDelta(PlaylistDeltaPacket.add(7L, entry("three"), 2)));
    assertTrue(state.isSnapshotRequired());
    assertEquals(Arrays.asList("one", "two"), sourceIds(state.snapshot()));
}

@Test
public void snapshotClearsRevisionGapState() {
    ClientQueueState state = new ClientQueueState();
    state.applySnapshot(4L, false, false, entries("one"));
    assertFalse(state.applyDelta(PlaylistDeltaPacket.remove(6L, 0)));
    state.applySnapshot(6L, true, false, entries("replacement"));

    assertFalse(state.isSnapshotRequired());
    assertEquals(6L, state.getRevision());
    assertTrue(state.isShuffling());
}
```

- [ ] **Step 2: Run the focused queue/UI tests and verify failure**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.client.ClientQueueStateTest --tests com.horizonradio.client.GuiLayoutTest
```

Expected: compilation failure for the missing revisioned queue state.

- [ ] **Step 3: Implement snapshot/delta application**

Make queue updates authoritative: do not modify the cached shared queue when the user clicks Add, Remove, or Reorder. Apply only the server snapshot/delta. On a revision gap, send one `PlaylistResyncRequestPacket` and suppress duplicate requests until a snapshot arrives.

Change the GUI playlist entry model to store source type/source ID/added-by and optional local metadata. Render a loading label or bounded source ID if metadata is not available. Resolve metadata lazily through `ClientMetadataCache`; do not send a metadata request to Minecraft.

- [ ] **Step 4: Wire direct search/chart/import/radio-search results**

Initialize `ClientMediaService` and `ClientMetadataCache` in `ClientProxy.preInit`. Update search, chart, import, and radio-search loading/error states from client futures on the Minecraft client thread. Preserve request-generation checks so an older local search cannot overwrite a newer query.

- [ ] **Step 5: Run the focused client tests**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.client.ClientQueueStateTest --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.HorizonRadioClientConfigTest
```

Expected: PASS, with queue rendering driven by IDs plus local metadata.

- [ ] **Step 6: Commit the client queue and local discovery wiring**

```bash
git add src/main/java/com/horizonradio/core/client src/main/java/com/horizonradio/client src/test/java/com/horizonradio/core/client src/test/java/com/horizonradio/client
git commit -m "feat: apply revisioned client-side queue state"
```

## Task 5: Make the server queue authoritative and media-free

**Files:**

- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java`
- Modify: `src/main/java/com/horizonradio/CommonProxy.java`
- Modify: `src/main/java/com/horizonradio/network/ServerMessageHandlers.java`
- Modify: `src/main/java/com/horizonradio/network/ClientboundMessageHandlers.java`
- Modify: `src/main/java/com/horizonradio/network/HorizonRadioNetwork.java`
- Modify: `src/main/java/com/horizonradio/server/ServerEvents.java` to remove shutdown calls for removed server media services
- Test: `src/test/java/com/horizonradio/server/PlaylistManagerTest.java`
- Test: `src/test/java/com/horizonradio/core/server/PlaylistStateTest.java`
- Test: `src/test/java/com/horizonradio/network/PacketRoundTripTest.java`

**Interfaces:**

- The production `PlaylistManager` constructor accepts only the Minecraft server and configuration directory; it no longer owns `YouTubeService`, `AudioDownloadService`, `RadioBrowserService`, `RadioStreamService`, `ChartCache`, or a periodic progress broadcaster.
- `handleAddToPlaylist(EntityPlayerMP, String videoId, long durationMs)` validates only the ID, duration, playlist limits, and permissions.
- `handlePlayNow(EntityPlayerMP, String videoId, long durationMs)` uses the same validation and removes an active radio entry before finite playback.
- `handleSelectRadio(EntityPlayerMP, String stationUuid)` validates the station ID, inserts/replaces a radio entry at index zero, increments the playback generation, and broadcasts a radio TrackSync without looking up Radio Browser.
- `handleStopRadio(EntityPlayerMP)` removes the active radio entry and starts the next finite entry, if present.
- `syncToPlayer(EntityPlayerMP)` sends the ID-only snapshot and one source-aware TrackSync; it never sends NowPlaying metadata or audio chunks.

- [ ] **Step 1: Write failing server behavior tests**

```java
@Test
public void selectingRadioCreatesQueueEntryWithoutDirectoryLookupOrRelay() {
    PlaylistManager manager = new PlaylistManager(testServer(), testConfigDirectory());

    manager.handleSelectRadio(testPlayer(), "station-id");

    assertEquals(MediaSourceType.RADIO, playlist(manager).get(0).getSourceType());
    assertEquals("station-id", playlist(manager).get(0).getSourceId());
    assertEquals(1, playlist(manager).size());
}

@Test
public void addingFiniteTrackReportsOnlyIdAndDurationAndLeavesRadioCurrent() {
    PlaylistManager manager = new PlaylistManager(testServer(), testConfigDirectory());
    manager.handleSelectRadio(testPlayer(), "station-id");

    manager.handleAddToPlaylist(testPlayer(), "video-id", 60_000L);

    assertEquals(MediaSourceType.RADIO, current(manager).getSourceType());
    assertEquals(MediaSourceType.YOUTUBE, playlist(manager).get(1).getSourceType());
    assertEquals(60_000L, playlist(manager).get(1).getDurationMs());
}

@Test
public void stoppingRadioRemovesIndexZeroAndStartsNextFiniteTrack() {
    PlaylistManager manager = new PlaylistManager(testServer(), testConfigDirectory());
    manager.handleSelectRadio(testPlayer(), "station-id");
    manager.handleAddToPlaylist(testPlayer(), "video-id", 60_000L);

    manager.handleStopRadio(testPlayer());

    assertEquals(MediaSourceType.YOUTUBE, current(manager).getSourceType());
    assertEquals("video-id", current(manager).getSourceId());
}
```

The test class defines the existing lightweight fixtures explicitly: `testServer()` returns the mocked server context, `testConfigDirectory()` returns a temporary directory, `testPlayer()` creates the existing profile-backed `EntityPlayerMP` fixture, `playlist(manager)` returns the manager state's immutable snapshot, and `current(manager)` returns the current entry. Absence of external lookup and relay calls is verified by the source-audit tests in Task 7, because the media-free manager has no media-service fields to inject or count.

- [ ] **Step 2: Run the focused server tests and verify failure**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.server.PlaylistManagerTest --tests com.horizonradio.core.server.PlaylistStateTest
```

Expected: compilation or assertion failure because the current manager still resolves/searches media and treats radio as a separate relay state.

- [ ] **Step 3: Remove server-side external media work**

Delete server search, chart refresh, YouTube import, radio search, Radio Browser lookup, radio candidate promotion, PCM relay, finite audio download, preload, late-join audio chunk, and periodic NowPlaying code from the production manager. Keep the standalone media classes available for client construction and compatibility tests, but do not instantiate them in `CommonProxy.onServerStarting`.

Retain only server-thread queue mutation, permission checks, duration validation, generation/timing, and compact packet broadcasts. Validate YouTube IDs with the existing syntax validator and station IDs with the bounded station-ID validator; never validate existence through an external request.

- [ ] **Step 4: Implement queue delta broadcasting**

Replace every full `syncToAll()` call with a revision increment and the smallest valid delta:

```text
add       → ADD(sourceType, sourceId, index, addedBy)
remove    → REMOVE(index)
reorder   → MOVE(fromIndex, targetIndex)
clear     → CLEAR
shuffle   → REPLACE(ordered source IDs)
radio swap/play-now → REPLACE when multiple queue changes are atomic
```

Send a full snapshot only on player join or a resync request. Do not send a NowPlaying packet, title, duration string, RadioState packet, RadioAudioStartPacket, RadioAudioChunkPacket, or AudioChunkPacket from any normal production path.

Loop and shuffle toggles remain server-authoritative: broadcast their existing compact boolean state packets for live updates, and include both flags in the ID-only snapshot for join/resync.

- [ ] **Step 5: Implement source-aware playback**

For YouTube, increment the generation, calculate `startAt = serverNow + 3000`, and send `TrackSyncPacket.youtube(...)`. Schedule automatic advancement from the stored client-reported duration. For radio, increment the generation and send `TrackSyncPacket.radio(...)`; do not create `startAt`, position, pause, or duration state.

Reject pause/seek while radio is current. Treat Skip while radio is current as removal of the radio entry followed by finite advancement. Keep loop/shuffle state server-authoritative; looping a live radio source has no finite replay action.

- [ ] **Step 6: Update server hooks and common initialization**

Remove server hook methods for search, imports, charts, and radio search. Add a resync hook. Keep server hooks for finite add/play-now, bulk ID/duration adds, queue mutations, finite controls, radio selection, and stop radio.

Change `CommonProxy.onServerStarting` to create only the media-free `PlaylistManager`. Keep the client-side `AudioDownloadService`, `ClientMediaService`, and `RadioInputSession` initialization in `ClientProxy`.

- [ ] **Step 7: Run the focused server/protocol tests**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.server.PlaylistManagerTest --tests com.horizonradio.core.server.PlaylistStateTest --tests com.horizonradio.network.PacketRoundTripTest
```

Expected: PASS, together with the Task 7 source audits proving that no server media service or radio relay method remains on the production path.

- [ ] **Step 8: Commit the media-free server**

```bash
git add src/main/java/com/horizonradio/server/PlaylistManager.java src/main/java/com/horizonradio/CommonProxy.java src/main/java/com/horizonradio/network src/main/java/com/horizonradio/server/ServerEvents.java src/test/java/com/horizonradio/server/PlaylistManagerTest.java src/test/java/com/horizonradio/core/server/PlaylistStateTest.java src/test/java/com/horizonradio/network/PacketRoundTripTest.java
git commit -m "feat: make server queue and sync media-free"
```

## Task 6: Implement local finite playback and direct live radio playback

**Files:**

- Create: `src/main/java/com/horizonradio/client/audio/ClientRadioPlayback.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/ClientProxy.java`
- Modify: `src/main/java/com/horizonradio/client/audio/AudioPlayer.java`
- Modify: `src/main/java/com/horizonradio/client/audio/PlaybackClock.java` to expose the existing server-clock conversion as a source-independent finite-track helper
- Test: `src/test/java/com/horizonradio/client/audio/ClientRadioPlaybackTest.java`
- Test: `src/test/java/com/horizonradio/client/HorizonRadioClientTrackSyncTest.java`
- Test: `src/test/java/com/horizonradio/client/RadioClientStateTest.java`
- Test: `src/test/java/com/horizonradio/client/audio/AudioPlayerTest.java`

**Interfaces:**

`ClientRadioPlayback` exposes:

```java
void start(long generation, String stationUuid);
void stop();
long getActiveGeneration();
```

Its injected station resolver returns `CompletableFuture<RadioStation>`. Its injected session factory has the exact method `RadioInputSession create(String streamUrl, RadioInputSession.RadioPcmListener listener)`. The callback path calls `AudioPlayer.startLocalRadio(generation)` once and `AudioPlayer.receiveLocalRadioPcm(generation, pcm)` for normalized PCM. It closes the session on stop, source replacement, disconnect, and stale generation.

`AudioPlayer` adds local equivalents to its existing relay methods:

```java
boolean startLocalRadio(long generation);
void receiveLocalRadioPcm(long generation, byte[] pcm);
```

The existing packet methods may remain for compatibility tests but are not called by registered production handlers.

- [ ] **Step 1: Write failing local-radio and source-aware sync tests**

```java
@Test
public void localRadioStartsOnlyAfterStationLookupAndUsesLiveEdge() {
    FakeStationResolver resolver = new FakeStationResolver();
    FakeRadioSessionFactory sessions = new FakeRadioSessionFactory();
    RecordingAudioSink audioSink = new RecordingAudioSink();
    ClientRadioPlayback playback = new ClientRadioPlayback(resolver, sessions, audioSink);

    playback.start(21L, "station-id");
    resolver.complete(new RadioStation("station-id", "Station", "https://radio.example/live", true, false));

    assertEquals("https://radio.example/live", sessions.lastUrl);
    assertEquals(21L, sessions.lastGeneration);
    assertEquals(1, audioSink.startLocalRadioCalls);
}

@Test
public void staleRadioCompletionDoesNotReplaceNewGeneration() {
    FakeStationResolver resolver = new FakeStationResolver();
    FakeRadioSessionFactory sessions = new FakeRadioSessionFactory();
    RecordingAudioSink audioSink = new RecordingAudioSink();
    ClientRadioPlayback playback = new ClientRadioPlayback(resolver, sessions, audioSink);

    playback.start(21L, "station-old");
    playback.start(22L, "station-new");
    resolver.completeFor(22L, new RadioStation("station-new", "New", "https://radio.example/new", true, false));
    resolver.completeFor(21L, new RadioStation("station-old", "Old", "https://radio.example/old", true, false));

    assertEquals(22L, playback.getActiveGeneration());
    assertEquals("https://radio.example/new", sessions.lastUrl);
    assertFalse(sessions.openedUrls.contains("https://radio.example/old"));
}
```

The test fixtures expose the exact seams used by the implementation: `FakeStationResolver` stores one future per generation and implements `completeFor`, `FakeRadioSessionFactory` records `lastUrl`, `lastGeneration`, and `openedUrls` while returning closeable sessions, and `RecordingAudioSink` exposes `startLocalRadioCalls` plus generation-checked PCM/stop counters. No fixture opens a real HTTP stream or Java Sound line.

Update `HorizonRadioClientTrackSyncTest` so a radio TrackSync is accepted by generation/source ID and finite timing fields are not used.

- [ ] **Step 2: Run focused audio/client tests and verify failure**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.client.audio.ClientRadioPlaybackTest --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.audio.AudioPlayerTest
```

Expected: compilation failure for the local-radio APIs and source-aware TrackSync handling.

- [ ] **Step 3: Refactor AudioPlayer's radio handoff for local PCM**

Extract the common radio initialization and generation checks from `startRadio(RadioAudioStartPacket)` into an internal method. Let packet-based relay input use the old sequence buffer, while local input assigns a monotonically increasing local sequence or writes through the same bounded buffer without constructing network packets. Preserve the existing Java Sound format (44.1 kHz, stereo, 16-bit signed PCM), jitter bounds, volume application, line cleanup, and audio executor isolation.

- [ ] **Step 4: Implement ClientRadioPlayback**

Resolve the station UUID locally, reject invalid or unavailable station records locally, create `RadioInputSession` with the resolved stream URL, and forward normalized PCM to the audio player. Every callback captures generation and station UUID; stale callbacks are dropped. A radio stream failure updates only local UI state and logs locally.

- [ ] **Step 5: Integrate source-aware TrackSync**

On a YouTube TrackSync, cancel the local radio session, resolve local video metadata, download/reuse the WAV cache, and schedule the clip using the existing server-clock catch-up logic. On a RADIO TrackSync, cancel finite download/clip work, resolve the station and start the direct live stream without a start timestamp. Pause/resume/seek handlers must ignore radio state.

Add a client-tick/local-presentation update that derives title, station label, duration, and finite progress from local metadata and local audio state. No progress packet is sent to or received from the server.

- [ ] **Step 6: Run focused tests and the existing media test suite**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.client.audio.ClientRadioPlaybackTest --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.audio.AudioPlayerTest --tests com.horizonradio.server.media.RadioInputSessionTest --tests com.horizonradio.server.media.RadioJitterBufferTest
```

Expected: PASS, including stale-generation and local failure behavior.

- [ ] **Step 7: Commit client playback**

```bash
git add src/main/java/com/horizonradio/client src/test/java/com/horizonradio/client
git commit -m "feat: play radio and finite tracks entirely on clients"
```

## Task 7: Remove production search/metadata/audio traffic and preserve compatibility boundaries

**Files:**

- Modify: `src/main/java/com/horizonradio/network/HorizonRadioNetwork.java`
- Modify: `src/main/java/com/horizonradio/network/ClientboundMessageHandlers.java`
- Modify: `src/main/java/com/horizonradio/network/ServerMessageHandlers.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/ClientProxy.java`
- Modify: `src/main/java/com/horizonradio/CommonProxy.java`
- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java`
- Modify: `src/main/java/com/horizonradio/core/config/HorizonRadioConfig.java`
- Test: `src/test/java/com/horizonradio/network/PacketRoundTripTest.java`
- Test: `src/test/java/com/horizonradio/server/ServerEventsStructureTest.java`
- Test: `src/test/java/com/horizonradio/core/server/PlaylistStateTest.java`
- Test: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Production traffic to remove:**

- client-to-server search, chart, YouTube import, and radio-search requests;
- server-to-client search results, chart results, import completion, and radio-search results;
- server-to-client title/progress `NowPlayingPacket`;
- server-to-client `RadioStatePacket`, `RadioAudioStartPacket`, and `RadioAudioChunkPacket`;
- server-to-client finite `AudioChunkPacket`;
- client-to-server finite `ReadyPacket` used by the old relay synchronization.

Keep obsolete packet classes only when existing compatibility tests need their serializers. They must be unregistered or unreachable from the production manager, and a source-level audit test must prove that no production playback path calls them.

- [ ] **Step 1: Add failing source-audit tests**

```java
@Test
public void serverManagerHasNoExternalMediaOrAudioRelayCalls() throws IOException {
    String source = readSource("src/main/java/com/horizonradio/server/PlaylistManager.java");

    assertFalse(source.contains("youTubeService."));
    assertFalse(source.contains("audioDownloadService."));
    assertFalse(source.contains("radioBrowserService."));
    assertFalse(source.contains("radioStreamService."));
    assertFalse(source.contains("chartCache."));
    assertFalse(source.contains("PlaylistImportService"));
    assertFalse(source.contains("AudioChunkPacket"));
    assertFalse(source.contains("RadioAudioChunkPacket"));
    assertFalse(source.contains("NowPlayingPacket"));
}
```

Add a second audit over `CommonProxy.java` and `HorizonRadioNetwork.java` that rejects server construction or registration of `YouTubeService`, `AudioDownloadService`, `RadioBrowserService`, `RadioStreamService`, search/result/import/chart packets, and all finite/radio audio relay packets. This proves the server is media-free at both ownership and transport-registration boundaries.

- [ ] **Step 2: Run the audit and packet registration tests**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.server.PlaylistManagerTest --tests com.horizonradio.network.PacketRoundTripTest
```

Expected: FAIL until all old production references and registrations are removed.

- [ ] **Step 3: Remove obsolete registrations and handlers**

Unregister the old search/result/import/chart/radio-relay message classes. Retain only queue mutation, queue snapshot/delta/resync, clock sync, finite source sync, and finite control registrations. Update handler source-audit assertions to guarantee no client-only Minecraft class leaks into common handlers.

- [ ] **Step 4: Make debug diagnostics log-only by default**

Add `serverDebugChat` to `HorizonRadioConfig` with default `false` and JSON key `serverDebugChat`. Change server debug helpers so regular sync/start diagnostics always use `LOGGER` and Minecraft-chat diagnostics are emitted only when `serverDebugChat` is true.

- [ ] **Step 5: Run the source audits and full existing tests**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.server.PlaylistManagerTest --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.core.server.PlaylistStateTest --tests com.horizonradio.client.GuiLayoutTest
```

Expected: PASS, with no server search/media/relay production path.

- [ ] **Step 6: Commit the traffic cleanup**

```bash
git add src/main/java/com/horizonradio/network/HorizonRadioNetwork.java src/main/java/com/horizonradio/network/ClientboundMessageHandlers.java src/main/java/com/horizonradio/network/ServerMessageHandlers.java src/main/java/com/horizonradio/client/HorizonRadioClient.java src/main/java/com/horizonradio/client/ClientProxy.java src/main/java/com/horizonradio/CommonProxy.java src/main/java/com/horizonradio/server/PlaylistManager.java src/main/java/com/horizonradio/core/config/HorizonRadioConfig.java src/test/java/com/horizonradio/network/PacketRoundTripTest.java src/test/java/com/horizonradio/server/ServerEventsStructureTest.java src/test/java/com/horizonradio/core/server/PlaylistStateTest.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "refactor: remove server media traffic paths"
```

## Task 8: Update documentation and perform complete verification

**Files:**

- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Test: `src/test/java/com/horizonradio/core/protocol/HorizonRadioProtocolTest.java`
- Test: `src/test/java/com/horizonradio/network/PacketRoundTripTest.java`

- [ ] **Step 1: Document the final two-source pipeline**

Document that the server receives only source IDs and finite durations for queue mutations, sends ID-only snapshots/deltas and source-aware sync, and performs no YouTube/Radio Browser lookup or audio relay. Document that radio has only station UUID plus generation and joins the live edge without `startAt`, position, or catch-up. The approved design spec is not changed during implementation unless a separately reviewed requirement changes.

- [ ] **Step 2: Update protocol documentation and packet counts**

Record the new packet IDs 36 and 37, the ID-only snapshot/delta formats, the removal of production result/audio registrations, and the compatibility-only status of old relay serializers.

- [ ] **Step 3: Run formatting and the complete test suite**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew spotlessCheck test
```

Expected: exit code 0, no test failures.

- [ ] **Step 4: Run the complete build and packaging verification**

Run:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew build
```

Expected: exit code 0, including compilation, tests, `jar`, `reobfJar`, and packaging checks.

- [ ] **Step 5: Check the final worktree and commit documentation**

Run:

```bash
git diff --check
git status --short
```

Stage only the intended source and documentation changes. Leave the pre-existing untracked documents untouched.

```bash
git add README.md docs/ARCHITECTURE.md src/test/java/com/horizonradio/core/protocol/HorizonRadioProtocolTest.java src/test/java/com/horizonradio/network/PacketRoundTripTest.java
git commit -m "docs: describe client-side music and radio pipelines"
```

## Final acceptance checklist

- [ ] YouTube search, charts, imports, metadata lookup, and audio download happen directly on each client.
- [ ] Radio search, station lookup, and live-stream connection happen directly on each client.
- [ ] The server stores no titles, thumbnails, station URLs, or audio bytes.
- [ ] All clients receive the same ordered source-ID queue.
- [ ] Queue mutations use revisions and compact deltas; revision gaps trigger one snapshot request.
- [ ] Finite tracks synchronize with generation, absolute server start time, and client-reported duration.
- [ ] Radio synchronizes with station UUID and generation only; no radio `startAt` or position is sent.
- [ ] Stale finite downloads and stale radio connections are ignored by generation.
- [ ] Pause/seek are unavailable for radio, while finite controls remain server-authoritative.
- [ ] No production path broadcasts `NowPlayingPacket`, finite audio chunks, or radio audio chunks.
- [ ] `spotlessCheck`, the full test suite, and the complete reobfuscated build pass.
