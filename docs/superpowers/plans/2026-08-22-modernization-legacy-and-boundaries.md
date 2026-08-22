# Modernization Legacy Removal and Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove proven obsolete production paths and establish honest core, network, media, server, and client package boundaries.

**Architecture:** Characterize the 24 active packet contract first, delete unregistered serializers and packet adapters, remove external-tool configuration, introduce a transport-neutral queue delta, then mechanically move embedded media code to `com.horizonradio.media`.

**Tech Stack:** Java 8-compatible output via Jabel, Forge 1.7.10, Gradle, JUnit 4, IntelliJ semantic usage/call hierarchy, source/package audits.

**Spec:** `docs/superpowers/specs/2026-08-22-project-modernization-design.md`

## Global Constraints

- Execute this plan after `2026-08-22-modernization-safety-and-backpressure.md`.
- Preserve the 24 active packet registrations and exact wire bytes.
- Do not remove annotation-, Forge-, reflection-, serialization-, resource-, or registration-driven entry points.
- Remove code referenced only by tests that preserve an obsolete implementation.
- Keep negative audits that prohibit `yt-dlp`, `youtube-dl`, `ffmpeg`, and external processes.
- Keep persisted config migration only for still-supported user data; unknown removed JSON fields may be ignored by Gson.
- Separate mechanical package moves from behavior changes.

---

### Task 1: Remove external-tool configuration and no-op adapters

**Files:**
- Modify: `src/main/java/com/horizonradio/core/config/HorizonRadioConfig.java`
- Modify: `src/test/java/com/horizonradio/HorizonRadioConfigTest.java`
- Modify: `src/main/java/com/horizonradio/server/AudioDownloadService.java`
- Rename: `src/test/java/com/horizonradio/server/AudioDownloadCommandTest.java` to `src/test/java/com/horizonradio/server/AudioDownloadCacheTest.java`
- Modify: `README.md`
- Modify: `docs/COMPATIBILITY.md`

**Interfaces:**
- `HorizonRadioConfig` retains playlist size, maximum duration, download directory, and debug-chat state only.
- `AudioDownloadService` retains backend/executor/test-seam constructors; cookie and dependency-check constructors disappear.

- [ ] **Step 1: Write a failing saved-config test**

```java
@Test
public void savedConfigOmitsRemovedExternalToolFields() throws Exception {
    File directory = Files.createTempDirectory("horizonradio-config-no-external-tools").toFile();
    try {
        HorizonRadioConfig.load(directory).save(directory);
        String json = new String(Files.readAllBytes(new File(directory, "horizonradio.json").toPath()), UTF_8);
        assertFalse(json.contains("youtubeCookiesFromBrowser"));
        assertFalse(json.contains("youtubeCookiesFile"));
    } finally {
        deleteRecursively(directory);
    }
}
```

Keep a load test containing those unknown keys and assert supported fields still load; do not assert legacy getters.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.HorizonRadioConfigTest.savedConfigOmitsRemovedExternalToolFields
```

- [ ] **Step 3: Remove the fields from the config model**

Delete both defaults, fields, constructor parameters, JSON reads/writes, and getters. Reduce construction to:

```java
private HorizonRadioConfig(int maxPlaylistSize, int maxTrackDurationMinutes, String downloadDir,
    boolean serverDebugChat);
```

The block defines the retained constructor surface. Assign each argument to its same-named final field and keep the existing validation/default-loading behavior for those four active settings.

- [ ] **Step 4: Remove no-op service constructors**

Delete constructors accepting `boolean checkDependencies`, `youtubeCookiesFromBrowser`, or `youtubeCookiesFile`. Keep the package-private backend/metadata/cancellation seams used by current embedded-backend tests.

- [ ] **Step 5: Rename the active cache test accurately**

Move the file and class to `AudioDownloadCacheTest`; retain all cache-hit, corrupt-cache, in-flight sharing, cancellation, and commit-race tests.

- [ ] **Step 6: Remove active documentation fields**

Delete cookie fields and text implying external downloader support from README and compatibility examples. Keep text that the JAR is self-contained and needs no external executable.

- [ ] **Step 7: Test negative audits and commit**

```bash
./gradlew test --tests com.horizonradio.HorizonRadioConfigTest
./gradlew test --tests com.horizonradio.server.AudioDownloadCacheTest
./gradlew test --tests com.horizonradio.server.StandaloneMediaSourceAuditTest
git add src/main/java/com/horizonradio/core/config/HorizonRadioConfig.java
git add src/main/java/com/horizonradio/server/AudioDownloadService.java
git add src/test/java/com/horizonradio/HorizonRadioConfigTest.java src/test/java/com/horizonradio/server
git add README.md docs/COMPATIBILITY.md
git commit -m "refactor: remove external downloader remnants"
```

### Task 2: Lock the active packet inventory and delete unregistered serializers

**Files:**
- Modify: `src/test/java/com/horizonradio/core/protocol/HorizonRadioProtocolTest.java`
- Modify: `src/test/java/com/horizonradio/network/PacketRoundTripTest.java`
- Delete: `src/main/java/com/horizonradio/network/packets/AudioChunkPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/ChartAddCompletionPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/ImportPlaylistPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/ImportVideoPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/NowPlayingPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/RadioAudioChunkPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/RadioAudioStartPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/RadioSearchRequestPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/RadioSearchResultsPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/RadioStatePacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/ReadyPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/RequestChartsPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/SearchRequestPacket.java`
- Delete: `src/main/java/com/horizonradio/network/packets/SearchResultsPacket.java`

**Interfaces:**
- Produces: one explicit test inventory containing exactly the 24 classes registered in `HorizonRadioNetwork`.
- Removes: compatibility-only serializers with no production registration.

- [ ] **Step 1: Add an active-registration source audit**

Parse `HorizonRadioNetwork.java` in `HorizonRadioProtocolTest` and assert each expected packet class and numeric ID occurs exactly once. Build the expected map explicitly:

```java
Map<String, Integer> active = new LinkedHashMap<String, Integer>();
active.put("AddToPlaylistPacket", 1);
active.put("RemoveFromPlaylistPacket", 2);
active.put("PlaylistSyncPacket", 5);
active.put("PausePacket", 8);
active.put("ResumePacket", 9);
active.put("ReorderPlaylistPacket", 10);
active.put("SeekRequestPacket", 11);
active.put("TogglePlaybackPacket", 12);
active.put("SkipTrackPacket", 13);
active.put("PreviousTrackPacket", 14);
active.put("ToggleLoopPacket", 15);
active.put("LoopStatePacket", 16);
active.put("ToggleShufflePacket", 17);
active.put("ShuffleStatePacket", 18);
active.put("AddChartsToPlaylistPacket", 22);
active.put("ClearPlaylistPacket", 23);
active.put("PlayNowPacket", 24);
active.put("SelectRadioStationPacket", 26);
active.put("StopRadioPacket", 27);
active.put("ClockSyncRequestPacket", 33);
active.put("ClockSyncResponsePacket", 34);
active.put("TrackSyncPacket", 35);
active.put("PlaylistDeltaPacket", 36);
active.put("PlaylistResyncRequestPacket", 37);
assertEquals(24, active.size());

Set<String> serverbound = new LinkedHashSet<String>(Arrays.asList(
    "AddToPlaylistPacket", "AddChartsToPlaylistPacket", "RemoveFromPlaylistPacket",
    "ClearPlaylistPacket", "PlayNowPacket", "SelectRadioStationPacket", "StopRadioPacket",
    "ReorderPlaylistPacket", "SeekRequestPacket", "TogglePlaybackPacket", "SkipTrackPacket",
    "PreviousTrackPacket", "ToggleLoopPacket", "ToggleShufflePacket",
    "PlaylistResyncRequestPacket", "ClockSyncRequestPacket"));
Set<String> clientbound = new LinkedHashSet<String>(Arrays.asList(
    "LoopStatePacket", "ShuffleStatePacket", "PlaylistSyncPacket", "PlaylistDeltaPacket",
    "TrackSyncPacket", "PausePacket", "ResumePacket", "ClockSyncResponsePacket"));
assertTrue(Collections.disjoint(serverbound, clientbound));
Set<String> directedPackets = new LinkedHashSet<String>(serverbound);
directedPackets.addAll(clientbound);
assertEquals(active.keySet(), directedPackets);
```

Keep these collections independent of production constants. Parse each matching `registerMessage` call and assert the ID plus `Side.SERVER`/`Side.CLIENT` from the explicit sets. The build/documentation plan later replaces the stale architecture inventory from this reviewed table; do not weaken the test to match current documentation.

- [ ] **Step 2: Verify the inventory passes before deletion**

```bash
./gradlew test --tests com.horizonradio.core.protocol.HorizonRadioProtocolTest
```

- [ ] **Step 3: Remove obsolete round-trip coverage**

Delete imports and test methods that instantiate the 14 classes listed above. Retain bounds/roundtrip coverage for every class in the active map and the source audit proving production code does not construct removed request/relay types.

- [ ] **Step 4: Delete the serializers**

Delete exactly the 14 listed packet files. Do not compact or reuse their historical numeric IDs.

- [ ] **Step 5: Verify packet and compile behavior**

```bash
./gradlew test --tests com.horizonradio.core.protocol.HorizonRadioProtocolTest
./gradlew test --tests com.horizonradio.network.PacketRoundTripTest
./gradlew compileJava compileTestJava
```

Expected: active round trips pass; no main source imports a deleted packet.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/horizonradio/network/packets
git add src/test/java/com/horizonradio/core/protocol/HorizonRadioProtocolTest.java
git add src/test/java/com/horizonradio/network/PacketRoundTripTest.java
git commit -m "refactor: remove unregistered packet serializers"
```

### Task 3: Remove packet-relay adapters from active audio code

**Files:**
- Modify: `src/main/java/com/horizonradio/client/audio/AudioPlayer.java`
- Modify: `src/test/java/com/horizonradio/client/RadioClientStateTest.java`
- Modify: `src/test/java/com/horizonradio/client/audio/AudioPlayerTest.java`
- Delete: `src/main/java/com/horizonradio/core/audio/AudioChunkAssembler.java`
- Delete: `src/test/java/com/horizonradio/core/audio/AudioChunkAssemblyTest.java`
- Modify: `src/test/java/com/horizonradio/client/LocalRadioHandoffSourceAuditTest.java`

**Interfaces:**
- Keep: `beginLocalTrack`, `loadLocalTrack`, `beginLocalRadioPcm`, `bufferLocalRadioPcm`, `stopRadio`, volume, progress, and shutdown.
- Remove: packet-typed `receiveChunk`, `startRadio`, and `receiveRadioChunk` adapters plus finite relay assembly.
- Keep: `RadioStreamBuffer`, because direct local radio PCM actively uses it.

- [ ] **Step 1: Characterize direct radio buffering**

Convert one packet-based radio test to the active API before deleting adapters:

```java
assertTrue(player.beginLocalRadioPcm(7L));
player.bufferLocalRadioPcm(7L, new byte[] { 1, 2, 3, 4 });
player.bufferLocalRadioPcm(7L, new byte[] { 5, 6, 7, 8 });
assertTrue(sourceLineFactory.awaitWrite());
```

Run it and confirm PASS.

- [ ] **Step 2: Remove finite packet assembly**

Delete the `AudioChunkAssembler` field, `receiveChunk(AudioChunkPacket)`, `loadTrack(CompletedTrack, ...)`, and `isValidChunkZero`. Delete the assembler class and its preservation-only tests.

- [ ] **Step 3: Remove radio packet adapters**

Delete `startRadio(RadioAudioStartPacket)` and `receiveRadioChunk(RadioAudioChunkPacket)`. Keep their shared local implementations and convert active jitter/handoff tests to `beginLocalRadioPcm` and `bufferLocalRadioPcm`.

- [ ] **Step 4: Tighten the source audit**

Replace assertions about constructing old packet classes with assertions that `AudioPlayer` exposes no method taking a class from `network.packets`:

```java
for (Method method : AudioPlayer.class.getDeclaredMethods()) {
    for (Class<?> parameter : method.getParameterTypes()) {
        assertFalse(parameter.getName().startsWith("com.horizonradio.network.packets."));
    }
}
```

- [ ] **Step 5: Test and commit**

```bash
./gradlew test --tests com.horizonradio.client.RadioClientStateTest
./gradlew test --tests com.horizonradio.client.audio.AudioPlayerTest
./gradlew test --tests com.horizonradio.client.LocalRadioHandoffSourceAuditTest
git add src/main/java/com/horizonradio/client/audio/AudioPlayer.java src/main/java/com/horizonradio/core/audio
git add src/test/java/com/horizonradio/client src/test/java/com/horizonradio/core/audio
git commit -m "refactor: remove inactive audio relay adapters"
```

### Task 4: Remove deprecated metadata compatibility APIs

**Files:**
- Modify: `src/main/java/com/horizonradio/core/model/PlaylistEntry.java`
- Modify: `src/main/java/com/horizonradio/core/server/PlaylistState.java`
- Modify: `src/main/java/com/horizonradio/network/packets/AddToPlaylistPacket.java`
- Modify: `src/main/java/com/horizonradio/network/packets/PlayNowPacket.java`
- Modify: `src/main/java/com/horizonradio/network/packets/AddChartsToPlaylistPacket.java`
- Modify: `src/main/java/com/horizonradio/network/packets/PlaylistSyncPacket.java`
- Modify: `src/main/java/com/horizonradio/network/packets/TrackSyncPacket.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: tests that currently call removed overloads under `src/test/java/com/horizonradio/`

**Interfaces:**
- Finite mutations use source/video ID plus positive `durationMs` only.
- Source-aware models expose `getSourceType`, `getSourceId`, and `getDurationMs` only.

- [ ] **Step 1: Migrate tests and production callers to active APIs**

Replace examples such as:

```java
new PlaylistEntry("video", "Title", "3:00", "player")
```

with:

```java
PlaylistEntry.youtube("video", 180_000L, "player")
```

Replace `TrackSyncPacket.getVideoId()` with `getSourceId()` after asserting `getSourceType() == YOUTUBE`. Replace packet title/duration accessors with local presentation metadata or `getDurationMs()`.

- [ ] **Step 2: Run migrated tests before API deletion**

```bash
./gradlew test --tests 'com.horizonradio.core.*' --tests 'com.horizonradio.network.*' --tests 'com.horizonradio.client.*'
```

Expected: PASS while deprecated methods still exist but have no project-owned caller.

- [ ] **Step 3: Delete deprecated adapters**

Delete the legacy `PlaylistEntry` constructor, `getVideoId`, `getTitle`, `getDuration`, and `legacyDurationMillis`; the deprecated constructors/accessors in add/play-now/chart/snapshot/track-sync packets; `PlaylistState.startTrack`; and string-duration `ClientTransport`, `sendAdd`, and `sendPlayNow` overloads.

- [ ] **Step 4: Add an absence audit**

Add to the protocol/source audit:

```java
assertFalse(mainSource.contains("@Deprecated"));
assertFalse(mainSource.contains("youtubeCookiesFromBrowser"));
assertFalse(mainSource.contains("youtubeCookiesFile"));
```

Scope `mainSource` to project-owned `src/main/java`; third-party deprecations do not count.

- [ ] **Step 5: Compile, test, and commit**

```bash
./gradlew compileJava compileTestJava
./gradlew test
git add src/main/java src/test/java
git commit -m "refactor: remove migrated compatibility APIs"
```

### Task 5: Remove the packet dependency from core queue state

**Files:**
- Create: `src/main/java/com/horizonradio/core/client/QueueDelta.java`
- Create: `src/test/java/com/horizonradio/core/client/QueueDeltaTest.java`
- Modify: `src/main/java/com/horizonradio/core/client/ClientQueueState.java`
- Modify: `src/main/java/com/horizonradio/network/packets/PlaylistDeltaPacket.java`
- Modify: `src/test/java/com/horizonradio/core/client/ClientQueueStateTest.java`
- Modify: `src/test/java/com/horizonradio/network/PlaylistDeltaPacketTest.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`

**Interfaces:**
- Produces: transport-neutral `QueueDelta` with factories `add`, `remove`, `move`, `clear`, and `replace`.
- Produces: `PlaylistDeltaPacket.toCoreDelta()` adapter.
- Changes: `ClientQueueState.applyDelta(QueueDelta)`.

- [ ] **Step 1: Write failing core delta tests**

```java
@Test
public void appliesTransportNeutralAdd() {
    ClientQueueState state = new ClientQueueState();
    state.applySnapshot(0L, false, false, Collections.<PlaylistEntry>emptyList());
    QueueDelta delta = QueueDelta.add(1L, PlaylistEntry.youtube("video", 0L, "player"), 0);
    assertTrue(state.applyDelta(delta));
    assertEquals("video", state.snapshot().get(0).getSourceId());
}
```

Cover invalid revision, duplicate source, invalid index, move, clear, and replace.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.core.client.QueueDeltaTest
```

- [ ] **Step 3: Implement immutable `QueueDelta`**

Use this surface:

```java
public final class QueueDelta {
    public enum Operation { ADD, REMOVE, MOVE, CLEAR, REPLACE }
    public static QueueDelta add(long revision, PlaylistEntry entry, int index);
    public static QueueDelta remove(long revision, int index);
    public static QueueDelta move(long revision, int index, int targetIndex);
    public static QueueDelta clear(long revision);
    public static QueueDelta replace(long revision, List<PlaylistEntry> entries);
    public long getRevision();
    public Operation getOperation();
    public PlaylistEntry getEntry();
    public int getIndex();
    public int getTargetIndex();
    public List<PlaylistEntry> getEntries();
}
```

The block defines the immutable public surface. Require non-negative revisions and indices, require entries only for `ADD`, copy and wrap replacement lists with `Collections.unmodifiableList`, and expose empty optionals as `null`, `-1`, or an empty list consistently with the tests.

Queue entries distributed by delta use `durationMs = 0L` because duration remains client-local/server timing state, matching current behavior.

- [ ] **Step 4: Change core state to consume `QueueDelta`**

Remove the `PlaylistDeltaPacket` import and switch on `QueueDelta.Operation`. `QueueDelta` already carries `PlaylistEntry`, so delete packet-entry conversion.

- [ ] **Step 5: Add the network adapter**

Implement in `PlaylistDeltaPacket`:

```java
public QueueDelta toCoreDelta() {
    switch (operation) {
        case ADD: return QueueDelta.add(queueRevision, toCoreEntry(entry), index);
        case REMOVE: return QueueDelta.remove(queueRevision, index);
        case MOVE: return QueueDelta.move(queueRevision, index, targetIndex);
        case CLEAR: return QueueDelta.clear(queueRevision);
        case REPLACE: return QueueDelta.replace(queueRevision, toCoreEntries(entries));
        default: throw new IllegalStateException("unsupported playlist operation");
    }
}
```

Call `CLIENT_QUEUE.applyDelta(packet.toCoreDelta())` from the current client facade.

- [ ] **Step 6: Prove core has no transport import**

Add a source assertion scanning `src/main/java/com/horizonradio/core`:

```java
assertFalse(source.contains("import com.horizonradio.network."));
assertFalse(source.contains("import cpw.mods.fml."));
assertFalse(source.contains("import net.minecraft."));
```

- [ ] **Step 7: Test and commit**

```bash
./gradlew test --tests 'com.horizonradio.core.client.*'
./gradlew test --tests com.horizonradio.network.PlaylistDeltaPacketTest
git add src/main/java/com/horizonradio/core/client src/main/java/com/horizonradio/network/packets/PlaylistDeltaPacket.java
git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/test/java/com/horizonradio/core/client
git add src/test/java/com/horizonradio/network/PlaylistDeltaPacketTest.java
git commit -m "refactor: decouple queue state from packets"
```

### Task 6: Move embedded media out of the server namespace

**Files:**
- Move: `src/main/java/com/horizonradio/server/media/*.java` to `src/main/java/com/horizonradio/media/`
- Move: `src/main/java/com/horizonradio/server/AudioDownloadService.java` to `src/main/java/com/horizonradio/media/AudioDownloadService.java`
- Move: `src/main/java/com/horizonradio/server/RadioBrowserService.java` to `src/main/java/com/horizonradio/media/RadioBrowserService.java`
- Move: `src/main/java/com/horizonradio/server/YouTubeService.java` to `src/main/java/com/horizonradio/media/YouTubeService.java`
- Move: matching tests from `src/test/java/com/horizonradio/server/media/` to `src/test/java/com/horizonradio/media/`
- Move: service tests from `src/test/java/com/horizonradio/server/` to `src/test/java/com/horizonradio/media/`
- Modify: imports in `src/main/java/com/horizonradio/client/`, `src/main/java/com/horizonradio/core/`, and remaining tests.
- Modify: `docs/ARCHITECTURE.md`

**Interfaces:**
- Keeps every media class name and active method signature; only packages/imports change.
- Produces: no client import from `com.horizonradio.server` for media work.

- [ ] **Step 1: Strengthen the boundary test before moving**

Extend the source audit to require:

```java
assertFalse(clientSource.contains("import com.horizonradio.server.AudioDownloadService"));
assertFalse(clientSource.contains("import com.horizonradio.server.RadioBrowserService"));
assertFalse(clientSource.contains("import com.horizonradio.server.YouTubeService"));
assertFalse(clientSource.contains("import com.horizonradio.server.media."));
```

Run it and verify failure on the current package layout.

- [ ] **Step 2: Move media implementation files mechanically**

Use IDE package refactoring or exact `git mv` operations, then change package declarations from `com.horizonradio.server.media` to `com.horizonradio.media` and the three service declarations from `com.horizonradio.server` to `com.horizonradio.media`. Do not alter method bodies in this step.

- [ ] **Step 3: Move tests mechanically**

Mirror production packages under `src/test/java/com/horizonradio/media`. Keep test class names and fixtures unchanged except the already-renamed cache test.

- [ ] **Step 4: Repair imports and audit text**

Update client/service/test imports. Keep actual server classes limited to queue, lifecycle, timing, and scheduling. Update architecture package tables to name `com.horizonradio.media`.

- [ ] **Step 5: Compile and run all media tests**

```bash
./gradlew compileJava compileTestJava
./gradlew test --tests 'com.horizonradio.media.*'
```

Expected: PASS with no behavior diff.

- [ ] **Step 6: Run package boundary and dedicated-server audits**

```bash
./gradlew test --tests com.horizonradio.server.StandaloneMediaSourceAuditTest
./gradlew test --tests com.horizonradio.server.ServerEventsStructureTest
```

- [ ] **Step 7: Commit the mechanical move**

```bash
git add src/main/java/com/horizonradio/media src/test/java/com/horizonradio/media
git add src/main/java/com/horizonradio src/test/java/com/horizonradio docs/ARCHITECTURE.md
git commit -m "refactor: move embedded media to media package"
```

### Task 7: Remove proven test-only and unused production code

**Files:**
- Delete: `src/main/java/com/horizonradio/core/audio/AudioPlayerState.java`
- Delete: `src/test/java/com/horizonradio/core/audio/AudioPlayerStateTest.java`
- Delete: `src/main/java/com/horizonradio/core/server/ChartCache.java`
- Delete: `src/test/java/com/horizonradio/core/server/ChartCacheTest.java`
- Modify: `src/main/java/com/horizonradio/core/server/PlaylistState.java`
- Modify: `src/main/java/com/horizonradio/client/audio/AudioPlayer.java`
- Modify: `src/main/java/com/horizonradio/media/YouTubeService.java`
- Modify: `src/main/java/com/horizonradio/media/YouTubeStreamResolver.java`
- Modify: `src/main/java/com/horizonradio/media/BoundedInputStream.java`
- Modify: `src/main/java/com/horizonradio/media/RadioInputSession.java`
- Modify: `src/main/java/com/horizonradio/media/RadioJitterBuffer.java`
- Modify: `src/main/java/com/horizonradio/media/OggPageReader.java`
- Modify: `src/main/java/com/horizonradio/media/YouTubeMediaModels.java`
- Modify: `src/test/java/com/horizonradio/server/YouTubeServiceTest.java`

**Interfaces:**
- Removes only symbols already shown by semantic usage analysis to be test-only or declaration-only.
- Keeps framework overrides, serialization constructors, decoder callbacks, and direct radio buffering even when ordinary callers are not visible.

- [ ] **Step 1: Record semantic evidence for the deletion set**

Run IDE Find Usages/call hierarchy and `rg` for each exact symbol. Confirm these current findings: `AudioPlayerState` and `ChartCache` are referenced only by their own tests; `PlaylistState.removeOwned` and `PlaylistState.getMaxPlaylistSize` have no caller; `AudioPlayer.getCurrentTitle` and `AudioPlayer.isAwaitingResume` have no caller.

```bash
rg -n 'AudioPlayerState|ChartCache|removeOwned|getMaxPlaylistSize|getCurrentTitle|isAwaitingResume' src/main/java src/test/java
```

Expected: no framework annotation or registration references any deletion candidate.

- [ ] **Step 2: Delete test-only state/cache classes**

Delete `AudioPlayerState`, `AudioPlayerStateTest`, `ChartCache`, and `ChartCacheTest`. Do not remove `ClientLocalPlaylistState`, `ClientQueueState`, `RadioStreamBuffer`, or `RadioJitterBuffer`; each has an active production caller.

- [ ] **Step 3: Remove declaration-only methods**

Delete exactly these methods after package moves:

```text
PlaylistState.removeOwned
PlaylistState.getMaxPlaylistSize
AudioPlayer.getCurrentTitle
AudioPlayer.isAwaitingResume
YouTubeService.fetchGermanTopCharts
YouTubeService.parseGermanTopCharts
YouTubeStreamResolver.ScriptParser.emptyString
BoundedInputStream.getRemaining
RadioInputSession.isClosed
RadioJitterBuffer.getBufferedBytes
RadioJitterBuffer.getMaximumBytes
RadioJitterBuffer.getStartupThresholdBytes
OggPageReader.Page.getGranulePosition
OggPageReader.Page.isEndOfStream
YouTubeMediaModels.ResolvedAudioStream(URL, MediaFormat, int, long)
YouTubeMediaModels.ResolvedAudioStream.getBitrate
```

Remove preservation-only tests for these methods; keep tests for the active neighboring behavior.

- [ ] **Step 4: Re-run semantic inspections**

Run IntelliJ warnings across `src/main/java`. Resolve project-owned dead-code, deprecated-API, ignored-resource, nullability, and constant-condition warnings. Do not mechanically apply Java-17 suggestions such as records, `List.copyOf`, or pattern matching because runtime output remains Java 8 compatible.

- [ ] **Step 5: Compile and run the full suite**

```bash
./gradlew compileJava compileTestJava
./gradlew test
```

Expected: PASS; no deleted symbol remains in source.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: remove proven dead production code"
```

### Task 8: Verify legacy removal and package boundaries

**Files:**
- Modify only files already touched by this plan when verification exposes an integration defect.

**Interfaces:**
- Produces: active-only packet/audio/config code and one-way package dependencies.

- [ ] **Step 1: Scan for forbidden remnants**

```bash
rg -n -i 'youtubeCookiesFromBrowser|youtubeCookiesFile|newCachedThreadPool|@Deprecated' src/main/java README.md docs
rg -n 'import com\.horizonradio\.network' src/main/java/com/horizonradio/core
rg -n 'import com\.horizonradio\.server' src/main/java/com/horizonradio/client
```

Expected: no result except deliberately documented historical context and negative audit patterns; no core network import; no client media import from server.

- [ ] **Step 2: Confirm active packet count**

```bash
./gradlew test --tests com.horizonradio.core.protocol.HorizonRadioProtocolTest
./gradlew test --tests com.horizonradio.network.PacketRoundTripTest
```

Expected: exactly 24 active registrations and all active round trips pass.

- [ ] **Step 3: Run formatter, suite, package audit, and build**

```bash
./gradlew spotlessCheck test packagingTest build
```

Expected: PASS with no unexpected skips.

- [ ] **Step 4: Inspect final diff**

```bash
git diff --check
git status --short
git diff --stat
```

Expected: clean checks and only reviewed legacy/boundary changes.
