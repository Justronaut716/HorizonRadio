# HorizonRadio Playback Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add selectable `Privat` and `Server` client playback modes plus a visible disabled `Group` button, while preserving the existing server protocol and preventing private-mode actions from using it.

**Architecture:** `HorizonRadioClient` becomes the single playback-mode gateway. The existing `ClientQueueState` remains the server-authoritative view, while a new `ClientLocalPlaylistState` owns the private queue and local playback state; the GUI always renders the active queue through the existing cache. Private finite tracks and radio streams reuse `AudioPlayer`, `AudioDownloadService`, and `ClientRadioPlayback`, with mode and generation checks on every asynchronous callback.

**Tech Stack:** Java 8, Minecraft Forge 1.7.10 GUI classes, JUnit 4, Gson client configuration, existing Java Sound playback services, and the existing Forge packet transport.

## Global Constraints

- `SERVER` is the default for missing or invalid persisted mode values.
- `GROUP` is visible but disabled and cannot change client state, playback, or network traffic.
- `PRIVATE` sends no HorizonRadio playlist, playback, radio, resync, or clock-sync packets.
- `SERVER` preserves the current `ClientTransport`, `ClientQueueState`, and synchronization behavior.
- The private playlist is memory-only and is cleared when entering private mode, leaving private mode, or disconnecting.
- No server-side classes, packet formats, or new network packets are required.
- Every local download callback must validate both the active mode and playback generation before touching playback state.
- No fragmented-MP4 demuxer or unrelated OpenAL/Java Sound refactor is part of this feature.

---

### Task 1: Add playback-mode values and configuration persistence

**Files:**
- Create: `src/main/java/com/horizonradio/client/PlaybackMode.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClientConfig.java`
- Test: `src/test/java/com/horizonradio/client/PlaybackModeTest.java`
- Modify: `src/test/java/com/horizonradio/client/HorizonRadioClientConfigTest.java`

**Interfaces:**
- Produces `PlaybackMode.fromPersistedName(String)`, `PlaybackMode.getPersistedName()`, and `PlaybackMode.isSelectable()`.
- Produces `HorizonRadioClientConfig.getPlaybackMode()` and `HorizonRadioClientConfig.save(float, ClientFavorites, PlaybackMode)`.
- Existing `save(float)` and `save(float, ClientFavorites)` retain their current behavior while preserving the mode loaded into the config object.

- [ ] **Step 1: Write failing enum and configuration tests**

Add tests for the exact persistence contract:

```java
@Test
public void persistedNamesRoundTripAndGroupIsNotSelectable() {
    assertEquals(PlaybackMode.PRIVATE, PlaybackMode.fromPersistedName("private"));
    assertEquals("server", PlaybackMode.SERVER.getPersistedName());
    assertFalse(PlaybackMode.GROUP.isSelectable());
}

@Test
public void missingOrInvalidPlaybackModeDefaultsToServer() throws IOException {
    File directory = Files.createTempDirectory("horizonradio-playback-mode-default").toFile();
    try {
        assertEquals(PlaybackMode.SERVER, HorizonRadioClientConfig.load(directory).getPlaybackMode());
        write(directory, "{\"playbackMode\":\"not-a-mode\"}");
        assertEquals(PlaybackMode.SERVER, HorizonRadioClientConfig.load(directory).getPlaybackMode());
    } finally {
        deleteRecursively(directory);
    }
}

@Test
public void privateModeSurvivesConfigurationRoundTrip() throws IOException {
    File directory = Files.createTempDirectory("horizonradio-playback-mode-roundtrip").toFile();
    try {
        HorizonRadioClientConfig.load(directory)
            .save(0.35f, new ClientFavorites(), PlaybackMode.PRIVATE);
        assertEquals(PlaybackMode.PRIVATE, HorizonRadioClientConfig.load(directory).getPlaybackMode());
    } finally {
        deleteRecursively(directory);
    }
}
```

Extend the existing volume/favorites tests so a later volume or favorites save does not erase the persisted mode.

- [ ] **Step 2: Run the focused tests and verify they fail for the missing API**

Run:

```bash
./gradlew test --tests com.horizonradio.client.PlaybackModeTest --tests com.horizonradio.client.HorizonRadioClientConfigTest
```

Expected: compilation failures for the missing enum, getter, and save overload.

- [ ] **Step 3: Implement the enum and backward-compatible JSON field**

Implement `PlaybackMode` with stable lower-case values `private`, `server`, and `group`. `fromPersistedName` trims and compares case-insensitively; null, empty, and unknown values return `SERVER`. Only `PRIVATE` and `SERVER` return `true` from `isSelectable()`.

Add a final `PlaybackMode playbackMode` field to `HorizonRadioClientConfig`. Update all constructors and the missing/malformed-file fallback paths to use `SERVER`. Read the optional JSON property `playbackMode`; do not fail the whole configuration when it is absent or invalid. Write it as:

```json
{"volume":0.35,"favoriteSongs":[],"favoriteRadios":[],"playbackMode":"private"}
```

Keep the old save overloads and make them delegate to the new overload using the config object's stored mode. The new overload normalizes null or `GROUP` to `SERVER` before writing.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run the same Gradle command from Step 2. Expected: all enum and configuration tests pass, including legacy JSON without `playbackMode`.

- [ ] **Step 5: Commit the persistence boundary**

```bash
git add src/main/java/com/horizonradio/client/PlaybackMode.java src/main/java/com/horizonradio/client/HorizonRadioClientConfig.java src/test/java/com/horizonradio/client/PlaybackModeTest.java src/test/java/com/horizonradio/client/HorizonRadioClientConfigTest.java
git commit -m "feat: persist client playback mode"
```

### Task 2: Create deterministic private playlist state

**Files:**
- Create: `src/main/java/com/horizonradio/core/client/ClientLocalPlaylistState.java`
- Test: `src/test/java/com/horizonradio/core/client/ClientLocalPlaylistStateTest.java`

**Interfaces:**
- Consumes `PlaylistEntry`, `MediaSourceType`, and the same current-track semantics already implemented by `PlaylistState`.
- Produces a client-only, non-networked state object constructed as `new ClientLocalPlaylistState(int maxPlaylistSize)`.
- Required public methods are:

```java
boolean add(PlaylistEntry entry);
List<PlaylistEntry> snapshot();
PlaylistEntry get(int index);
int size();
int findIndex(MediaSourceType sourceType, String sourceId);
int remove(MediaSourceType sourceType, String sourceId);
PlaylistEntry removeCurrent();
void clear();
boolean moveQueued(int fromIndex, int targetIndex);
PlaylistEntry prepareImmediatePlayback(PlaylistEntry requested);
boolean selectRadioAtFront(PlaylistEntry station);
boolean pauseRadioPlayback();
void startFiniteTrack(int index, long startAtMs);
void startRadioTrack(int index);
long currentPositionMs(long nowMs);
long pausePlayback(long positionMs, long nowMs);
long resumePlayback(long nowMs);
long seek(long positionMs, long nowMs);
int getCurrentIndex();
PlaylistEntry getCurrentEntry();
MediaSourceType getCurrentSourceType();
String getCurrentSourceId();
boolean isPlaying();
boolean isPaused();
long getPlaybackStartTime();
boolean toggleLooping();
boolean isLooping();
boolean toggleShuffling();
boolean isShuffling();
void shuffleQueued(Random random);
boolean wasPreviousRestarted();
void markPreviousRestarted();
PlaylistEntry takeLastTrack();
void resetPlayback();
```

- [ ] **Step 1: Write failing state tests**

Cover queue isolation, validation, current-track transitions, bounded pause/seek, previous-track bookkeeping, and queued-only reorder/shuffle:

```java
@Test
public void localStateOwnsItsOwnEntriesAndRejectsDuplicateSources() {
    ClientLocalPlaylistState state = new ClientLocalPlaylistState(3);
    PlaylistEntry first = PlaylistEntry.youtube("one", 60_000L, "Private");
    assertTrue(state.add(first));
    assertFalse(state.add(PlaylistEntry.youtube("one", 60_000L, "Private")));
    assertEquals(Collections.singletonList(first), state.snapshot());
    List<PlaylistEntry> copy = state.snapshot();
    copy.clear();
    assertEquals(1, state.size());
}

@Test
public void immediatePlaybackAndNextRemovalKeepCurrentIndexConsistent() {
    ClientLocalPlaylistState state = new ClientLocalPlaylistState(5);
    PlaylistEntry first = PlaylistEntry.youtube("one", 60_000L, "Private");
    PlaylistEntry second = PlaylistEntry.youtube("two", 60_000L, "Private");
    assertTrue(state.add(first));
    assertTrue(state.add(second));
    assertEquals(first, state.prepareImmediatePlayback(first));
    state.startFiniteTrack(0, 1_000L);
    assertEquals(first, state.getCurrentEntry());
    assertEquals(5_000L, state.pausePlayback(5_000L, 10_000L));
    assertEquals(7_000L, state.seek(7_000L, 11_000L));
    assertTrue(state.isPaused());
    state.removeCurrent();
    assertEquals(-1, state.getCurrentIndex());
    assertEquals(Collections.singletonList(second), state.snapshot());
}
```

Add tests proving `moveQueued` cannot move the active item, `seek` clamps to `durationMs - 1`, radio state has no finite duration, and `shuffleQueued(new Random(7L))` never moves the active entry.

- [ ] **Step 2: Run the new state test and verify it fails**

Run:

```bash
./gradlew test --tests com.horizonradio.core.client.ClientLocalPlaylistStateTest
```

Expected: compilation failure because `ClientLocalPlaylistState` does not exist.

- [ ] **Step 3: Implement only the client-side state machine**

Use a private `ArrayList<PlaylistEntry>` and scalar playback fields. Enforce these rules in the implementation:

- reject null entries, non-positive finite durations, duplicate `(sourceType, sourceId)` pairs, and entries over `maxPlaylistSize`;
- return defensive snapshots;
- `prepareImmediatePlayback` moves or inserts the requested entry at index zero, records the interrupted finite track for `previous`, and resets playback without making network decisions;
- `moveQueued` accepts only valid indices after the current index, matching the existing server behavior;
- `startFiniteTrack` and `startRadioTrack` set `currentIndex`, source fields, playing state, and timing fields without touching any Minecraft or audio class;
- pause, resume, and seek clamp finite positions and return `-1L` when no finite track is active;
- `removeCurrent` records the removed finite track in `lastTrack`, resets playback, and leaves remaining entries in order;
- `toggleShuffling` only changes the flag; `shuffleQueued(Random)` shuffles entries after the current index;
- `clear` removes entries and resets playback/previous state while preserving no server revision or network concept.

- [ ] **Step 4: Run state tests and the existing server state tests**

Run:

```bash
./gradlew test --tests com.horizonradio.core.client.ClientLocalPlaylistStateTest --tests com.horizonradio.core.server.PlaylistStateTest
```

Expected: all new local-state tests and all existing server `PlaylistState` tests pass.

- [ ] **Step 5: Commit the isolated local state machine**

```bash
git add src/main/java/com/horizonradio/core/client/ClientLocalPlaylistState.java src/test/java/com/horizonradio/core/client/ClientLocalPlaylistStateTest.java
git commit -m "feat: add client-local playlist state"
```

### Task 3: Add the mode gateway, queue routing, and synchronization guards

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Create: `src/test/java/com/horizonradio/client/HorizonRadioClientModeTest.java`

**Interfaces:**
- Consumes `PlaybackMode` and `ClientLocalPlaylistState` from Tasks 1 and 2.
- Produces `HorizonRadioClient.getPlaybackMode()` and `HorizonRadioClient.setPlaybackMode(PlaybackMode)`.
- Keeps all existing public `sendAdd`, `sendPlayNow`, `sendAddChartsToPlaylist`, `sendPlaylistResultsToQueue`, `sendRemove`, `sendClearPlaylist`, `sendReorder`, `sendSeek`, `sendTogglePlayback`, `sendSkipTrack`, `sendPreviousTrack`, `sendToggleLoop`, `sendToggleShuffle`, `sendSelectRadio`, `sendStopRadio`, and `sendClockSync` signatures.
- Adds package-visible deterministic test seams `static synchronized void onClientTick(long clientNowMs)` and `static boolean shouldAcceptPrivateAudioCompletion(PlaybackMode currentMode, long currentGeneration, long expectedGeneration, String currentVideoId, String expectedVideoId)`; neither seam sends packets.

- [ ] **Step 1: Write failing mode-routing tests**

Use a recording `ClientTransport` and reset the static client state in `@Before`/`@After`. Add tests with these assertions:

```java
@Test
public void privateAddUsesLocalQueueWithoutTransport() {
    HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);

    HorizonRadioClient.sendAdd("private-song", 120_000L);

    assertEquals(0, transport.addCount);
    assertEquals("private-song", HorizonRadioClient.getCachedPlaylist().get(0).sourceId);
}

@Test
public void privateModeIgnoresServerPlaylistAndTrackPackets() {
    HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
    HorizonRadioClient.handlePlaylistSnapshot(snapshotPacket(4L, "server-song"));
    HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(4L, "server-song", 0L, 0L, false));

    assertTrue(HorizonRadioClient.getCachedPlaylist().isEmpty());
    assertNull(HorizonRadioClient.getCachedNowPlaying());
}

@Test
public void switchingBackToServerClearsPrivateViewAndRequestsSnapshotAndClock() {
    HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
    HorizonRadioClient.sendAdd("private-song", 120_000L);

    HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);

    assertTrue(HorizonRadioClient.getCachedPlaylist().isEmpty());
    assertEquals(1, transport.playlistResyncCount);
    assertEquals(1, transport.clockSyncCount);
}
```

Also verify that `SERVER` still delegates add/remove/seek/playback/radio actions to the recording transport and that calling `setPlaybackMode(GROUP)` leaves the mode unchanged.

Define `snapshotPacket(long revision, String videoId)` in the test class with one YouTube `PlaylistSyncPacket.Entry`, and make the recording transport expose counters for add, remove, seek, playback, radio, resync, clock-sync, and a `totalPacketCount()` sum.

- [ ] **Step 2: Run the mode tests and verify they fail**

Run:

```bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientModeTest
```

Expected: compilation failures for the mode gateway and local routing.

- [ ] **Step 3: Add mode state and explicit transition methods**

Add a static `PlaybackMode playbackMode = PlaybackMode.SERVER`, a static `ClientLocalPlaylistState LOCAL_QUEUE`, and a private `setActivePlaybackMode` transition boundary. `loadClientConfig` loads the configured selectable mode; null config falls back to `SERVER`.

Implement transitions in this order:

```text
set PRIVATE:
  stop and invalidate server/local audio work;
  cancel the active download;
  clear LOCAL_QUEUE and local presentation;
  publish PRIVATE and refresh the GUI from the empty local queue;
  persist PRIVATE with the current volume and favorites.

set SERVER:
  stop and invalidate private audio work;
  clear LOCAL_QUEUE and private presentation;
  reset CLIENT_QUEUE and server resync bookkeeping;
  publish SERVER and refresh the GUI from the empty server view;
  send one complete playlist resync request and one clock-sync request;
  persist SERVER with the current volume and favorites.

set GROUP or null:
  do nothing and keep the current mode.
```

The stop/invalidate step must increment the local playback generation before the new mode is published so late completions cannot update the new presentation.

- [ ] **Step 4: Route queue and control actions through the gateway**

Change the existing action methods so `SERVER` uses the current `transport` path and `PRIVATE` uses `LOCAL_QUEUE`:

- map finite selections to `PlaylistEntry.youtube(videoId, durationMs, "Private")`;
- add, play-now, remove, clear, reorder, seek, play/pause, skip, previous, loop, and shuffle mutate only `LOCAL_QUEUE` in private mode;
- when a private queue action changes state, call `refreshCachedPlaylistFromActiveQueue()` and update the existing screen cache;
- for chart and playlist selection resolution, call `clearPendingAdds` locally and do not call `awaitPendingAddResolution` or `sendPlaylistResync` in private mode;
- for `remove=true` chart/playlist actions, remove the mapped local IDs rather than sending a chart packet;
- keep search, metadata, chart, playlist-import, and radio-search futures unchanged because they are client-side discovery operations;
- make `requestPlaylistResync`, `handlePlaylistSnapshot`, `handlePlaylistDelta`, `handleTrackSync`, `handlePause`, `handleResume`, `handleClockSync`, `updateLooping`, and `updateShuffling` return before doing server-state work unless the active mode is `SERVER`;
- use private presentation helpers for local loop/shuffle state so incoming server state cannot overwrite private buttons;
- make `sendClockSync()` a no-op outside `SERVER`.

Refactor the current `refreshCachedPlaylistFromQueue` into an active-queue refresh that selects `LOCAL_QUEUE.snapshot()` in private mode and `CLIENT_QUEUE.snapshot()` in server mode. Keep prefetching client-side finite audio from the selected active queue.

- [ ] **Step 5: Run routing and regression tests**

Run:

```bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientModeTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest
```

Expected: private routing and synchronization-guard tests pass, while existing server-mode transport and track-sync tests remain green.

- [ ] **Step 6: Commit the mode gateway**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/test/java/com/horizonradio/client/HorizonRadioClientModeTest.java
git commit -m "feat: route client actions by playback mode"
```

### Task 4: Implement private finite-track playback and generation-safe downloads

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/test/java/com/horizonradio/client/HorizonRadioClientModeTest.java`

**Interfaces:**
- Consumes `LOCAL_QUEUE` and mode transitions from Task 3.
- Reuses `AudioPlayer.beginLocalTrack`, `AudioPlayer.loadLocalTrack`, `AudioPlayer.pause`, `AudioPlayer.resume`, `AudioPlayer.stop`, and `AudioDownloadService.download/cancelDownload` without changing their public contracts.
- Produces private finite playback that advances from `HorizonRadioClient.onClientTick()` and never emits a Forge packet.

- [ ] **Step 1: Write failing finite-playback tests**

Add deterministic tests for starting an entry, local clock presentation, completion, loop, shuffle, and stale downloads. The test should be able to call `onClientTick(long)` with a controlled timestamp. Add a helper assertion for the active local generation/source if the existing static fields are not otherwise observable.

```java
@Test
public void privateTickAdvancesToNextEntryWithoutTransport() {
    HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
    HorizonRadioClient.sendAdd("one", 1_000L);
    HorizonRadioClient.sendAdd("two", 1_000L);
    HorizonRadioClient.sendPlayNow("one", 1_000L);
    long startedAt = privateTrackStartAt();

    HorizonRadioClient.onClientTick(startedAt + 1_001L);

    assertEquals("two", currentPrivateSourceId());
    assertEquals(0, transport.totalPacketCount());
}

@Test
public void latePrivateDownloadCompletionIsIgnoredAfterModeSwitch() {
    HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
    HorizonRadioClient.sendPlayNow("song", 1_000L);
    long generation = activePrivateGeneration();
    HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);
    assertFalse(HorizonRadioClient.shouldAcceptPrivateAudioCompletion(
        PlaybackMode.SERVER, generation, generation, "song", "song"));
}
```

Define `privateTrackStartAt()`, `currentPrivateSourceId()`, and `activePrivateGeneration()` in the test class as small reflection helpers for the existing package-private client fields; the helpers must read only and must not mutate production state. `activePrivateGeneration()` supplies the `generation` value in the stale-completion test after `sendPlayNow("song", 1_000L)`.

Test that a private failure leaves no partially displayed replacement and does not invoke any transport method. The existing `AudioDownloadService` fake-backend constructor can be used to return a completed future or a failed/cancelled future without touching the real network.

- [ ] **Step 2: Run the finite-playback tests and verify the new behavior is missing**

Run:

```bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientModeTest
```

Expected: failures for missing local start, tick advancement, or generation checks.

- [ ] **Step 3: Add private finite-track start and completion helpers**

Implement a private start helper with this behavior:

```text
startPrivateFinite(entry, positionMs, paused):
  increment local playback generation;
  stop any radio session and current clip;
  set LOCAL_QUEUE current finite entry and timing state;
  set active source/generation/presentation fields;
  call AudioPlayer.beginLocalTrack(videoId, positionMs, paused ? 0 : startAtMs, paused);
  request metadata for title/duration;
  call AudioDownloadService.download(videoId);
  attach one completion callback that schedules onto the client thread.
```

The callback must reject null paths, failures, non-regular files, changed mode, changed generation, and changed source ID. On accepted completion it calls `AudioPlayer.loadLocalTrack`. A failed completion stops/clears the local audio presentation, keeps the queue data valid, and reports through the existing debug-chat path without retrying automatically.

Use a monotonically increasing client-local generation for all private starts/stops. `setPlaybackMode`, `clearCache`, skip, previous, remove-current, and radio transitions invalidate the generation before stopping audio. Keep server `TrackSyncPacket` generation checks separate by resetting the active server generation when returning to `SERVER`.

- [ ] **Step 4: Advance private finite tracks from the client tick**

Make `onClientTick()` delegate to package-visible `onClientTick(long clientNowMs)`. In private mode, compute the local position from `ClientLocalPlaylistState.currentPositionMs(clientNowMs)`, update title/progress, and when the finite duration expires:

1. restart the same index when looping;
2. otherwise remove the current entry, shuffle queued entries when shuffling is enabled, and start the next finite entry;
3. stop and clear the local playback presentation when no next entry exists.

In server mode retain the existing presentation-only clock logic and never use the private queue.

- [ ] **Step 5: Implement private pause, resume, seek, skip, previous, loop, and shuffle audio alignment**

For private finite playback, compute a local position, update `LOCAL_QUEUE`, and call the existing `AudioPlayer.pause`/`resume` methods. For seeking, clamp the requested progress against the current entry duration and align the loaded/pending clip through `AudioPlayer.pause(position)` followed by `AudioPlayer.resume(position, 0L)` when playback was active. Radio sources remain excluded from finite seek/pause controls.

Mirror the existing server previous-track policy: the first previous press seeks the current finite entry to zero; a second press before the current position exceeds 10 seconds selects `LOCAL_QUEUE.takeLastTrack()` when available. Skip removes the current entry and starts the next. Every path refreshes the active playlist and presentation without calling `transport`.

- [ ] **Step 6: Run finite playback and regression tests**

Run:

```bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientModeTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.audio.AudioPlayerTest
```

Expected: private finite playback, stale callback, and existing Java Sound unit tests pass.

- [ ] **Step 7: Commit private finite playback**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/test/java/com/horizonradio/client/HorizonRadioClientModeTest.java
git commit -m "feat: play private client tracks locally"
```

### Task 5: Complete private radio playback and client lifecycle guards

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/ClientProxy.java`
- Modify: `src/test/java/com/horizonradio/client/HorizonRadioClientModeTest.java`
- Modify: `src/test/java/com/horizonradio/client/RadioClientStateTest.java`

**Interfaces:**
- Consumes `ClientRadioPlayback.start/stop` and the existing `StatusListener` callbacks.
- Produces direct private radio selection/stop behavior and startup/disconnect guards for Forge synchronization.

- [ ] **Step 1: Write failing radio and lifecycle tests**

Add tests that verify:

```java
@Test
public void privateRadioSelectionUsesLocalStateAndNoRadioPacket() {
    HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
    HorizonRadioClient.updateRadioSearchResults(
        Collections.singletonList(new RadioStation("station", "Station", "http://stream", true, false)));

    HorizonRadioClient.sendSelectRadio("station");

    assertEquals(0, transport.selectRadioCount);
    assertEquals(MediaSourceType.RADIO, currentPrivateSourceType());
}

@Test
public void privateConnectDoesNotSendClockSync() {
    HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
    QueueClientTaskScheduler scheduler = new QueueClientTaskScheduler();
    new ClientProxy.ClientEvents(scheduler).onConnect(null);
    scheduler.runAllOnClientThread();
    assertEquals(0, transport.clockSyncCount);
}
```

Define `QueueClientTaskScheduler` in the test package as the same small FIFO scheduler used by the existing client-state tests: it implements `ClientProxy.ClientTaskScheduler`, stores scheduled `Runnable` instances, and exposes `runAllOnClientThread()`.

Also test that `handleLocalRadioStarted` and `handleLocalRadioFailure` accept only the current private generation, while a stale callback after a mode switch is ignored. Keep the existing disconnect cleanup test and assert that it clears both local and server queue presentation without changing the persisted selected mode.

- [ ] **Step 2: Run the new lifecycle tests and verify they fail**

Run:

```bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientModeTest --tests com.horizonradio.client.RadioClientStateTest
```

Expected: private radio still reaches the transport and client connect still emits clock synchronization.

- [ ] **Step 3: Route private radio actions directly to `ClientRadioPlayback`**

In private mode, `sendSelectRadio` must:

1. resolve or create `PlaylistEntry.radio(stationUuid, "Private")`;
2. stop the current finite clip and any prior radio session;
3. select the station at the local queue front and allocate a fresh local generation;
4. publish a local radio presentation;
5. call `clientRadioPlayback.start(generation, stationUuid)` without invoking `transport.sendSelectRadio`.

`sendStopRadio` must pause the local radio state, invalidate the radio generation, stop `ClientRadioPlayback`, stop the Java Sound radio line, and publish the existing inactive/stopped presentation. Server mode continues to call the existing transport.

Use the mode and generation checks in `isActiveRadio`, `handleLocalRadioStarted`, and `handleLocalRadioFailure` so a late stream status cannot restore a previous mode's UI.

- [ ] **Step 4: Guard Forge lifecycle entry points**

In `ClientProxy.ClientEvents.onConnect`, schedule `HorizonRadioClient.sendClockSync()` only when `HorizonRadioClient.getPlaybackMode() == PlaybackMode.SERVER`. Keep the guard inside `HorizonRadioClient.sendClockSync()` as the race-safe boundary. Leave packet handlers scheduled on the client thread, but rely on the mode guards in `handlePlaylistSnapshot`, `handlePlaylistDelta`, `handleClockSync`, `handleTrackSync`, `handlePause`, `handleResume`, `updateLooping`, and `updateShuffling` at execution time.

Ensure `clearCache()` stops `AudioPlayer`, stops `ClientRadioPlayback`, cancels downloads, clears both queue states and pending server-resync bookkeeping, refreshes an empty active presentation, and preserves the selected mode and volume.

- [ ] **Step 5: Run radio, lifecycle, and full client tests**

Run:

```bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientModeTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.audio.ClientRadioPlaybackTest
```

Expected: direct private radio behavior, guarded connect/disconnect behavior, and existing radio playback tests pass.

- [ ] **Step 6: Commit radio and lifecycle handling**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/main/java/com/horizonradio/client/ClientProxy.java src/test/java/com/horizonradio/client/HorizonRadioClientModeTest.java src/test/java/com/horizonradio/client/RadioClientStateTest.java
git commit -m "feat: isolate private radio lifecycle"
```

### Task 6: Add the three-mode GUI controls

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- Consumes `PlaybackMode`, `HorizonRadioClient.getPlaybackMode()`, and `HorizonRadioClient.setPlaybackMode(PlaybackMode)`.
- Produces three text `ControlButton` instances labeled exactly `Privat`, `Server`, and `Group`.

- [ ] **Step 1: Write failing GUI tests**

Extend `GuiLayoutTest.TestScreen` with a helper that finds a button by its display label and add assertions for creation, active state, disabled state, and action routing:

```java
@Test
public void playbackModeButtonsExposePrivateServerAndDisabledGroup() {
    TestScreen screen = new TestScreen();
    screen.setScreenSize(300, 285);
    try {
        screen.initialize();
        assertTrue(screen.hasButtonLabel("Privat"));
        assertTrue(screen.hasButtonLabel("Server"));
        assertFalse(screen.buttonWithLabel("Group").enabled);
        assertEquals(PlaybackMode.SERVER, HorizonRadioClient.getPlaybackMode());
        screen.clickButtonLabel("Privat");
        assertEquals(PlaybackMode.PRIVATE, HorizonRadioClient.getPlaybackMode());
        screen.clickButtonLabel("Server");
        assertEquals(PlaybackMode.SERVER, HorizonRadioClient.getPlaybackMode());
    } finally {
        HorizonRadioScreen.clearActiveScreen(screen);
    }
}
```

Test that invoking the disabled `Group` ID directly does not change the mode and that mode buttons do not overlap the existing panel bounds in the normal layout constants.

- [ ] **Step 2: Run the GUI tests and verify they fail**

Run:

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
```

Expected: the mode labels and test helper cannot be found.

- [ ] **Step 3: Add mode buttons without changing the existing panel content layout**

Add unique IDs after the existing button IDs, mode-button constants, and three `ControlButton` fields. Create them in `initGui()` before the existing tab/control buttons, positioned in a row above the panel using the existing `panelLeft()` and `panelTop()` coordinates. Keep the current panel dimensions, tabs, search controls, queue controls, and volume slider unchanged.

Use `ControlButton(String label)` so the established active styling is reused. Set `Privat` or `Server` active based on `HorizonRadioClient.getPlaybackMode()`. Create `Group` with `enabled = false`; do not register an action for it beyond a defensive no-op branch.

Update active styling each draw or after a mode action so reopening the screen reflects persisted mode. `actionPerformed` must call `HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE)` or `SERVER` only for the two selectable IDs.

- [ ] **Step 4: Run GUI and client regression tests**

Run:

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.HorizonRadioClientModeTest
```

Expected: all mode-button assertions and existing layout/action tests pass.

- [ ] **Step 5: Commit the GUI controls**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "feat: add playback mode buttons"
```

### Task 7: Full verification and implementation handoff

**Files:**
- Modify: `docs/superpowers/specs/2026-08-14-playback-modes-design.md` only if implementation evidence requires a precise correction.
- Test: all existing and new test sources from Tasks 1-6.

- [ ] **Step 1: Run focused feature tests together**

```bash
./gradlew test --tests com.horizonradio.client.PlaybackModeTest --tests com.horizonradio.client.HorizonRadioClientConfigTest --tests com.horizonradio.core.client.ClientLocalPlaylistStateTest --tests com.horizonradio.client.HorizonRadioClientModeTest --tests com.horizonradio.client.GuiLayoutTest
```

Expected: all focused persistence, local-state, routing, lifecycle, and GUI tests pass.

- [ ] **Step 2: Run the complete project checks**

```bash
./gradlew spotlessCheck checkstyleMain checkstyleTest test
```

Expected: formatting, main-source checks, test-source checks, and the complete JUnit suite pass.

- [ ] **Step 3: Inspect the final diff for scope and safety**

```bash
git diff origin/UI-Improvement HEAD --stat
git diff --check origin/UI-Improvement HEAD
git status --short --branch
```

Confirm that the diff contains no server packet/protocol changes, no fMP4 implementation, no direct `org.lwjgl.openal` calls, no automatic private-mode network sends, and no generated partial audio artifacts.

- [ ] **Step 4: Commit only any required verification correction**

If a correction is required, run the relevant focused test again and commit it with a message describing the concrete correction. If no correction is required, leave the verified implementation commits intact and report the exact commands and results.

## Self-review checklist

- Spec coverage: Tasks 1-2 cover persisted mode and independent local state; Tasks 3-5 cover all private/server transitions, packet guards, local finite/radio playback, cancellation, stale callback rejection, and disconnect behavior; Task 6 covers all three buttons and disabled Group behavior; Task 7 covers regression and scope verification.
- Completeness scan: every implementation step specifies concrete files, interfaces, tests, commands, and expected outcomes.
- Type consistency: the mode enum, config getter/save overload, local state constructor/methods, and client gateway methods are named consistently across all tasks.
