# Modernization Client Controllers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `HorizonRadioClient` into a small Forge-facing facade over independently testable discovery, queue, playback, cache, and presentation components.

**Architecture:** Extract immutable presentation types first, then move one state cluster at a time behind explicit controller interfaces. Keep static entry points as forwarding methods until all GUI/network callers migrate; do not rewrite behavior and structure simultaneously.

**Tech Stack:** Java 8-compatible output via Jabel, Forge 1.7.10, JUnit 4, `CompletableFuture`, existing media/core/network abstractions.

**Spec:** `docs/superpowers/specs/2026-08-22-project-modernization-design.md`

## Global Constraints

- Execute after safety/backpressure and legacy/package-boundary plans.
- Preserve user-visible behavior, active packet layout, queue semantics, playback generations, and cache neighborhood of two previous/two next tracks.
- Asynchronous results publish only when request generation and originating UI owner are current.
- Controllers must be instantiable and must not load Forge GUI classes in pure unit tests.
- `HorizonRadioClient` remains the lifecycle/composition facade, not a second implementation.
- Every extraction begins with existing characterization tests passing and ends with the full related suite passing.

---

### Task 1: Extract client presentation models from the screen

**Files:**
- Create: `src/main/java/com/horizonradio/client/presentation/SongResultView.java`
- Create: `src/main/java/com/horizonradio/client/presentation/RadioStationView.java`
- Create: `src/main/java/com/horizonradio/client/presentation/PlaylistEntryView.java`
- Create: `src/test/java/com/horizonradio/client/presentation/PlaylistEntryViewTest.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/FavoriteResultComposer.java`
- Modify: `src/test/java/com/horizonradio/client/FavoriteResultComposerTest.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`
- Modify: `src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java`
- Modify: `src/test/java/com/horizonradio/client/HorizonRadioClientModeTest.java`
- Modify: `src/test/java/com/horizonradio/client/RadioClientStateTest.java`

**Interfaces:**
- `SongResultView(videoId, title, channel, duration, thumbnail)` with value equality.
- `RadioStationView(stationUuid, name)` with value equality.
- `PlaylistEntryView(MediaSourceType, sourceId, addedBy, SearchResult, RadioStation)` with `isFinite`, `displayTitle`, and `displayDuration`.

- [ ] **Step 1: Write failing presentation tests**

```java
@Test
public void playlistViewUsesBoundedSourceFallbackWithoutMetadata() {
    PlaylistEntryView view = new PlaylistEntryView(
        MediaSourceType.YOUTUBE, "abcdefghijklmnopqrstuvwxyz123456789", "player", null, null);
    assertEquals("abcdefghijklmnopqrstuvwxyz123...", view.displayTitle());
    assertEquals("", view.displayDuration());
    assertTrue(view.isFinite());
}
```

Add video metadata, station metadata, duration parsing, equality, and null-source fallback cases matching current nested-class behavior.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.client.presentation.PlaylistEntryViewTest
```

- [ ] **Step 3: Implement top-level value types**

Copy validation/value semantics from the current nested types. Use private final fields plus getters rather than public mutable fields. Keep `SOURCE_ID_FALLBACK_LIMIT = 32` and `Objects.equals/hash`.

```java
public final class SongResultView {
    public SongResultView(String videoId, String title, String channel, String duration, String thumbnail);
    public String getVideoId();
    public String getTitle();
    public String getChannel();
    public String getDuration();
    public String getThumbnail();
}
```

- [ ] **Step 4: Migrate callers mechanically**

Replace `HorizonRadioScreen.SearchResult`, `RadioStationResult`, and `PlaylistEntry` references with the new types. Replace field reads such as `result.videoId` with getters. Delete the three nested classes only after compilation succeeds.

- [ ] **Step 5: Run client model/layout tests**

```bash
./gradlew test --tests 'com.horizonradio.client.presentation.*'
./gradlew test --tests com.horizonradio.client.FavoriteResultComposerTest
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/horizonradio/client src/test/java/com/horizonradio/client
git commit -m "refactor: extract client presentation models"
```

### Task 2: Centralize defensively copied presentation state

**Files:**
- Create: `src/main/java/com/horizonradio/client/presentation/ClientPresentationStore.java`
- Create: `src/test/java/com/horizonradio/client/presentation/ClientPresentationStoreTest.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`

**Interfaces:**
- Store owns queue, charts, playlist import results, radio results/state, now-playing/progress, pause/loop/shuffle, and selected chart region.
- Every list getter returns a defensive copy; every setter copies input.

- [ ] **Step 1: Write failing copy/isolation tests**

```java
@Test
public void snapshotsCannotMutateStoredQueue() {
    ClientPresentationStore store = new ClientPresentationStore();
    List<PlaylistEntryView> input = new ArrayList<PlaylistEntryView>();
    input.add(entry("one"));
    store.setQueue(input);
    input.clear();
    List<PlaylistEntryView> first = store.getQueue();
    first.clear();
    assertEquals(1, store.getQueue().size());
}
```

Add `clear()` and scalar state tests.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.client.presentation.ClientPresentationStoreTest
```

- [ ] **Step 3: Implement synchronized store methods**

Use this surface:

```java
public final class ClientPresentationStore {
    public synchronized void setQueue(List<PlaylistEntryView> value);
    public synchronized List<PlaylistEntryView> getQueue();
    public synchronized void setCharts(List<SongResultView> value, String regionCode, long cachedAtMs);
    public synchronized List<SongResultView> getCharts();
    public synchronized void setPlaylistResults(List<SongResultView> value);
    public synchronized void setRadioResults(List<RadioStationView> value);
    public synchronized void setPlayback(String title, float progress, boolean paused);
    public synchronized void setLooping(boolean value);
    public synchronized void setShuffling(boolean value);
    public synchronized void setRadioPresentation(ClientRadioPresentation value);
    public synchronized void clear();
}
```

Implement corresponding getters used by the facade; normalize null lists to empty and progress to the existing valid range.

- [ ] **Step 4: Replace facade cache fields with the store**

Delete `CACHED_PLAYLIST`, `CACHED_CHARTS`, `CACHED_PLAYLIST_RESULTS`, `CACHED_RADIO_RESULTS`, and matching scalar cache fields after forwarding getters/setters to one `ClientPresentationStore` instance.

- [ ] **Step 5: Run facade cache tests and commit**

```bash
./gradlew test --tests com.horizonradio.client.presentation.ClientPresentationStoreTest
./gradlew test --tests 'com.horizonradio.client.HorizonRadioClient*Test'
git add src/main/java/com/horizonradio/client/presentation src/main/java/com/horizonradio/client/HorizonRadioClient.java
git add src/test/java/com/horizonradio/client
git commit -m "refactor: centralize client presentation state"
```

### Task 3: Extract discovery and metadata workflows

**Files:**
- Create: `src/main/java/com/horizonradio/client/discovery/ClientDiscoveryController.java`
- Create: `src/main/java/com/horizonradio/client/discovery/DiscoveryRequest.java`
- Create: `src/test/java/com/horizonradio/client/discovery/ClientDiscoveryControllerTest.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/media/ClientMediaService.java`
- Modify: discovery-related client tests.

**Interfaces:**
- `DiscoveryRequest.Kind`: `SEARCH`, `CHARTS`, `PLAYLIST_IMPORT`, `VIDEO_IMPORT`, `RADIO`.
- Controller methods: `search`, `charts`, `importPlaylist`, `importVideo`, `searchRadio`, `cancelOwner`, `clear`.
- Listener receives loading, song results, radio results, and failure with request kind/generation/owner.

- [ ] **Step 1: Write stale-result and cancellation tests**

```java
@Test
public void newerSearchPreventsOlderCompletionFromPublishing() {
    FakeMediaService media = new FakeMediaService();
    RecordingListener listener = new RecordingListener();
    ClientDiscoveryController controller = new ClientDiscoveryController(media, DIRECT_SCHEDULER, listener);
    Object owner = new Object();
    controller.search("old", owner);
    controller.search("new", owner);
    media.completeSearch("old", song("old"));
    media.completeSearch("new", song("new"));
    assertEquals(Collections.singletonList("new"), listener.publishedVideoIds());
}
```

Add owner cancellation, stale chart duration completion, import failure, radio failure, null-result, and scheduler-thread publication cases.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.client.discovery.ClientDiscoveryControllerTest
```

- [ ] **Step 3: Implement request identity**

```java
public final class DiscoveryRequest {
    public enum Kind { SEARCH, CHARTS, PLAYLIST_IMPORT, VIDEO_IMPORT, RADIO }
    private final Kind kind;
    private final long generation;
    private final Object owner;
    public DiscoveryRequest(Kind kind, long generation, Object owner);
    public Kind getKind();
    public long getGeneration();
    public Object getOwner();
    public boolean matches(Kind expectedKind, long expectedGeneration, Object expectedOwner);
}
```

The block defines the immutable request surface. Reject null kinds/owners, compare owner identity rather than value equality, and use one monotonically increasing generation per kind. Retain and cancel the active `CompletableFuture<?>` per kind.

- [ ] **Step 4: Implement controller/listener boundary**

```java
public interface Listener {
    void onLoading(DiscoveryRequest request);
    void onSongs(DiscoveryRequest request, List<SearchResult> results, String regionCode);
    void onStations(DiscoveryRequest request, List<RadioStation> stations);
    void onFailure(DiscoveryRequest request, String message);
}

public interface Scheduler {
    void execute(Runnable task);
}
```

Every completion schedules one listener task, rechecks that the request is current, then publishes a defensive copy. Cancellation of a superseded future is best-effort and never publishes an error.

- [ ] **Step 5: Move workflows from the facade**

Move search, chart lookup/duration resolution, playlist/video import, radio search, request generations, owner tracking, and failure translation. Keep static facade methods as one-line delegates and listener-to-store/screen adapters.

- [ ] **Step 6: Run discovery/facade tests**

```bash
./gradlew test --tests 'com.horizonradio.client.discovery.*'
./gradlew test --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
./gradlew test --tests com.horizonradio.client.HorizonRadioClientFavoritesTest
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/horizonradio/client/discovery src/main/java/com/horizonradio/client/HorizonRadioClient.java
git add src/main/java/com/horizonradio/client/media/ClientMediaService.java src/test/java/com/horizonradio/client
git commit -m "refactor: extract client discovery controller"
```

### Task 4: Extract queue synchronization and mutation coordination

**Files:**
- Create: `src/main/java/com/horizonradio/client/queue/ClientQueueController.java`
- Create: `src/main/java/com/horizonradio/client/queue/QueueSelection.java`
- Create: `src/test/java/com/horizonradio/client/queue/ClientQueueControllerTest.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: queue/delta client tests.

**Interfaces:**
- `QueueSelection(videoId, durationMs)` validates non-empty ID and positive finite duration.
- Controller handles snapshot, delta, pending add resolution, resync request, add/remove/play-now/clear/reorder, and private/server mode routing.
- Listener publishes queue views and status messages.

- [ ] **Step 1: Write snapshot/delta/pending-resolution tests**

```java
@Test
public void revisionGapRequestsExactlyOneResyncUntilSnapshotArrives() {
    RecordingTransport transport = new RecordingTransport();
    ClientQueueController controller = controller(transport);
    controller.applySnapshot(snapshot(4L, "one"));
    assertFalse(controller.applyDelta(deltaRemove(6L, 0)));
    assertFalse(controller.applyDelta(deltaRemove(7L, 0)));
    assertEquals(1, transport.resyncRevisions.size());
    controller.applySnapshot(snapshot(7L));
    assertFalse(controller.isResyncPending());
}
```

Add add/remove/move/clear, duplicate source, pending chart/playlist completion, private-mode mutation, and invalid selection tests.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.client.queue.ClientQueueControllerTest
```

- [ ] **Step 3: Implement immutable selection**

```java
public final class QueueSelection {
    public QueueSelection(String videoId, long durationMs) {
        if (videoId == null || videoId.trim().isEmpty()) throw new IllegalArgumentException("videoId is required");
        if (durationMs <= 0L) throw new IllegalArgumentException("durationMs must be positive");
        this.videoId = videoId;
        this.durationMs = durationMs;
    }
    public String getVideoId() { return videoId; }
    public long getDurationMs() { return durationMs; }
}
```

- [ ] **Step 4: Implement controller boundaries**

```java
public interface Transport {
    void add(String videoId, long durationMs);
    void playNow(String videoId, long durationMs);
    void addMany(List<QueueSelection> entries, boolean remove);
    void remove(String sourceId);
    void clear();
    void reorder(int fromIndex, int targetIndex);
    void requestResync(long knownRevision);
}

public interface Listener {
    void onQueueChanged(List<PlaylistEntryView> entries);
    void onPendingResolved(Object owner, List<String> sourceIds, boolean success, String message);
}
```

Own `ClientQueueState`, `ClientLocalPlaylistState`, playback mode, resync flag, and pending-add records in the controller.

- [ ] **Step 5: Move queue logic and keep facade delegates**

Move snapshot/delta conversion, metadata-to-view mapping trigger, mutation sending, pending resolutions, and private queue selections. The facade retains packet-shaped static methods that convert once and delegate.

- [ ] **Step 6: Run queue and mode tests**

```bash
./gradlew test --tests 'com.horizonradio.client.queue.*'
./gradlew test --tests com.horizonradio.client.HorizonRadioClientModeTest
./gradlew test --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest
./gradlew test --tests com.horizonradio.core.client.ClientQueueStateTest
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/horizonradio/client/queue src/main/java/com/horizonradio/client/HorizonRadioClient.java
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client
git commit -m "refactor: extract client queue controller"
```

### Task 5: Extract audio cache and playback coordination

**Files:**
- Create: `src/main/java/com/horizonradio/client/playback/AudioCacheController.java`
- Create: `src/main/java/com/horizonradio/client/playback/ClientPlaybackController.java`
- Create: `src/test/java/com/horizonradio/client/playback/AudioCacheControllerTest.java`
- Create: `src/test/java/com/horizonradio/client/playback/ClientPlaybackControllerTest.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/audio/AudioPlayer.java`
- Modify: playback/cache client tests.

**Interfaces:**
- Cache controller owns download requests, current/neighbor prefetch, recently played IDs, and pruning.
- Playback controller owns source/generation/timing, clock offset, finite/radio handoff, pause/resume/stop/tick, and presentation callbacks.

- [ ] **Step 1: Write cache-window and coalescing tests**

```java
@Test
public void keepsCurrentTwoPreviousAndTwoNextFiniteTracks() {
    RecordingCache cache = new RecordingCache();
    AudioCacheController controller = new AudioCacheController(cache, 2);
    controller.updateWindow(queue("a", "b", "c", "d", "e", "f"), 3, Arrays.asList("b", "c"));
    assertEquals(new LinkedHashSet<String>(Arrays.asList("b", "c", "d", "e", "f")), cache.lastKeepSet);
}
```

Add one-second settle, two-second target-change cooldown, duplicate in-flight request, radio entries ignored, cancellation, and shutdown tests.

- [ ] **Step 2: Write playback generation tests**

```java
@Test
public void staleFiniteDownloadCannotReplaceNewerGeneration() {
    FakeAudioCache cache = new FakeAudioCache();
    RecordingPlayer player = new RecordingPlayer();
    ClientPlaybackController controller = controller(cache, player);
    controller.handleSync(youtubeSync(10L, "old"));
    controller.handleSync(youtubeSync(11L, "new"));
    cache.complete("old", path("old.wav"));
    assertFalse(player.loadedIds.contains("old"));
    cache.complete("new", path("new.wav"));
    assertEquals(Collections.singletonList("new"), player.loadedIds);
}
```

Add radio generation, stop, pause/resume, private playback tick, clock offset, and failure presentation cases.

- [ ] **Step 3: Verify RED**

```bash
./gradlew test --tests 'com.horizonradio.client.playback.*'
```

- [ ] **Step 4: Implement cache controller surface**

```java
public interface CacheBackend {
    CompletableFuture<Path> download(String videoId);
    void cancel(String videoId);
    void keepOnly(Collection<String> videoIds);
    void shutdown();
}

public final class AudioCacheController {
    public CompletableFuture<Path> require(String videoId);
    public void updateQueue(List<PlaylistEntry> queue, int currentIndex, long nowMs);
    public void rememberPlayed(String videoId);
    public void tick(long nowMs);
    public void cancelActive();
    public void shutdown();
}
```

Keep constants `NEIGHBOURHOOD = 2`, `SETTLE_MILLIS = 1000L`, and `CHANGE_COOLDOWN_MILLIS = 2000L`.

- [ ] **Step 5: Implement playback controller surface**

```java
public interface Listener {
    void onPlayback(String title, float progress, boolean paused);
    void onRadio(ClientRadioPresentation presentation);
    void onFailure(String message);
}

public final class ClientPlaybackController {
    public void handleSync(TrackSyncPacket packet);
    public void handlePause(long positionMs);
    public void handleResume(long positionMs, long startAtMs);
    public void handleClockSync(ClockSyncResponsePacket packet, long receivedAtMs);
    public void tick(long nowMs);
    public void stop();
    public void clear();
}
```

Keep packet adaptation at this client/network boundary; internal helpers use source type, source ID, generation, and timing primitives.

- [ ] **Step 6: Move active fields/methods from the facade**

Move active-track fields, clock offset, playback generation, radio callbacks, private finite advancement, prefetch state, recently played IDs, and cache pruning. Replace facade methods with synchronized forwarding only where Forge callbacks still require static access.

- [ ] **Step 7: Run playback/cache suites**

```bash
./gradlew test --tests 'com.horizonradio.client.playback.*'
./gradlew test --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest
./gradlew test --tests com.horizonradio.client.HorizonRadioClientModeTest
./gradlew test --tests 'com.horizonradio.client.audio.*'
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/horizonradio/client/playback src/main/java/com/horizonradio/client/HorizonRadioClient.java
git add src/main/java/com/horizonradio/client/audio/AudioPlayer.java src/test/java/com/horizonradio/client
git commit -m "refactor: extract client playback controllers"
```

### Task 6: Reduce `HorizonRadioClient` to lifecycle composition and forwarding

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/ClientProxy.java`
- Modify: `src/main/java/com/horizonradio/network/ClientboundMessageHandlers.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: all `HorizonRadioClient*Test` classes.

**Interfaces:**
- Facade owns component construction/reset and static Forge/network/GUI forwarding.
- No business rule, async generation map, queue mutation algorithm, playback timing algorithm, or cache-window calculation remains in the facade.

- [ ] **Step 1: Add a facade delegation test**

Inject recording controllers through a package-private `installForTest(ClientComponents)` seam and assert static entry points delegate once with unchanged values.

```java
HorizonRadioClient.installForTest(components);
HorizonRadioClient.sendSearch("ambient");
assertEquals(Collections.singletonList("ambient"), discovery.searchQueries);
```

- [ ] **Step 2: Introduce one composition holder**

```java
final class ClientComponents {
    final ClientDiscoveryController discovery;
    final ClientQueueController queue;
    final ClientPlaybackController playback;
    final AudioCacheController cache;
    final ClientPresentationStore presentation;
    ClientComponents(ClientDiscoveryController discovery, ClientQueueController queue,
        ClientPlaybackController playback, AudioCacheController cache, ClientPresentationStore presentation) {
        this.discovery = discovery;
        this.queue = queue;
        this.playback = playback;
        this.cache = cache;
        this.presentation = presentation;
    }
}
```

Construct production components from `ClientProxy`; tests install fakes and restore defaults in teardown.

- [ ] **Step 3: Replace remaining implementation methods with delegates**

Group facade methods by discovery, queue, playback, presentation, favorites/config, and lifecycle. A forwarding method performs only null normalization or packet-to-controller conversion documented by its target interface.

- [ ] **Step 4: Remove obsolete facade fields/helpers**

Run semantic usage and delete fields now owned by controllers plus helper methods with no caller. Keep client config/favorites in the facade only until they have one clear lifecycle owner; do not create another controller solely to reduce line count.

- [ ] **Step 5: Run all client and network handler tests**

```bash
./gradlew test --tests 'com.horizonradio.client.*'
./gradlew test --tests com.horizonradio.network.PacketRoundTripTest
```

- [ ] **Step 6: Run complete verification and commit**

```bash
./gradlew spotlessCheck test packagingTest build
git diff --check
git add src/main/java/com/horizonradio/client src/main/java/com/horizonradio/network/ClientboundMessageHandlers.java
git add src/test/java/com/horizonradio/client
git commit -m "refactor: reduce client facade to composition"
```
