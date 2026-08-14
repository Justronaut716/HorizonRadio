# Direct Play on a Full Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow direct song and radio selection to evict queue position 0 when the server queue is full, while preserving the full-queue rejection for ordinary additions.

**Architecture:** Keep the eviction invariant in `PlaylistState`, which owns queue capacity and front mutations. Remove the two manager-side prechecks that currently reject direct play/radio selection before the state can apply the force-play policy; ordinary add and bulk-add paths remain unchanged.

**Tech Stack:** Java 8, Forge server-side queue manager, JUnit 4, Gradle, Spotless.

## Global Constraints

- When a client directly starts a song or selects a radio station while the server queue is full, remove queue position 0, place the requested source at the front, keep the configured maximum size, and begin playback.
- Normal queue additions remain capacity-limited and continue to report a full queue.
- Keep the behavior in `PlaylistState`; do not change client code, packet formats, or protocol behavior.
- Existing-current replacement and front-radio replacement remain atomic and do not evict an additional entry.
- Queue revisions and playback transitions continue through the existing state mutation and manager broadcast paths.
- Run the focused state/manager tests and the complete Gradle test/build checks.

---

## File Map

- Modify `src/main/java/com/horizonradio/core/server/PlaylistState.java`: centralize capacity-aware eviction for direct song/radio front selection without adding an extra queue-revision mutation.
- Modify `src/test/java/com/horizonradio/core/server/PlaylistStateTest.java`: prove direct selections evict the front at capacity and ordinary adds still reject capacity overflow.
- Modify `src/main/java/com/horizonradio/server/PlaylistManager.java`: stop rejecting direct song/radio requests solely because the queue is full; retain validation and ordinary-add capacity checks.
- Modify `src/test/java/com/horizonradio/server/PlaylistManagerTest.java`: prove manager-level direct song/radio requests succeed at the configured limit and update the existing full-radio expectation.

---

### Task 1: Make direct front selection capacity-aware in `PlaylistState`

**Files:**
- Modify: `src/main/java/com/horizonradio/core/server/PlaylistState.java:148-203`
- Test: `src/test/java/com/horizonradio/core/server/PlaylistStateTest.java`

**Interfaces:**
- Consumes: existing `PlaylistEntry`, `maxPlaylistSize`, `prepareImmediatePlayback(...)`, `selectRadioAtFront(...)`, and `canSelectRadioAtFront(...)`.
- Produces: the same public state methods, now capable of replacing queue position 0 when a direct selection needs room; no new network-facing API.

- [ ] **Step 1: Write failing state regressions**

Add these JUnit 4 tests to `PlaylistStateTest`:

```java
@Test
public void immediateFinitePlaybackEvictsFrontWhenFullWithoutActiveTrack() {
    PlaylistState state = new PlaylistState(2);
    state.add(PlaylistEntry.youtube("oldest", 1_000L, "Alice"));
    state.add(PlaylistEntry.youtube("queued", 1_000L, "Bob"));

    PlaylistEntry selected = state.prepareImmediatePlayback(
        PlaylistEntry.youtube("direct", 1_000L, "Carol"));

    assertEquals("direct", selected.getSourceId());
    assertEquals(2, state.size());
    assertEquals("direct", state.get(0).getSourceId());
    assertEquals("queued", state.get(1).getSourceId());
}

@Test
public void selectingRadioEvictsFrontWhenFull() {
    PlaylistState state = new PlaylistState(2);
    state.add(PlaylistEntry.youtube("oldest", 1_000L, "Alice"));
    state.add(PlaylistEntry.youtube("queued", 1_000L, "Bob"));
    PlaylistEntry station = PlaylistEntry.radio("station", "Carol");

    assertFalse(state.canSelectRadioAtFront(station));
    assertTrue(state.selectRadioAtFront(station));

    assertEquals(2, state.size());
    assertEquals(MediaSourceType.RADIO, state.get(0).getSourceType());
    assertEquals("station", state.get(0).getSourceId());
    assertEquals("queued", state.get(1).getSourceId());
    assertEquals(0, state.getCurrentIndex());
}

@Test
public void ordinaryAddStillRejectsAFullQueue() {
    PlaylistState state = new PlaylistState(1);
    state.add(PlaylistEntry.youtube("existing", 1_000L, "Alice"));

    assertFalse(state.add(PlaylistEntry.youtube("rejected", 1_000L, "Bob")));
    assertEquals(1, state.size());
    assertEquals("existing", state.get(0).getSourceId());
}
```

- [ ] **Step 2: Run the new tests and verify the expected failures**

Run:

```bash
./gradlew test --tests com.horizonradio.core.server.PlaylistStateTest
```

Expected: the new direct-song assertion reports a queue larger than 2 and the radio assertion reports `false`/an unchanged queue; the ordinary-add test remains green. These failures must come from the missing full-queue force-selection behavior, not compilation errors.

- [ ] **Step 3: Implement the smallest state change**

In `PlaylistState`:

1. Add this private helper, which removes index 0 only when the queue is non-empty and at capacity. If the removed entry was at `currentIndex`, set `currentIndex` to `-1`; if it was before a later current index, decrement that index. Do not call `markQueueMutation()` inside the helper, because the enclosing direct-selection method already records one atomic mutation:

```java
private void evictFrontForImmediateSelection() {
    if (playlist.isEmpty() || playlist.size() < maxPlaylistSize) {
        return;
    }
    playlist.remove(0);
    if (currentIndex > 0) {
        currentIndex--;
    } else if (currentIndex == 0) {
        currentIndex = -1;
    }
}
```
2. In `prepareImmediatePlayback(...)`, run the helper after removing an existing selected entry and any non-selected current entry, immediately before `playlist.add(0, selected)`. When an active current entry was removed, the queue is already below capacity, so the helper is a no-op; when there is no current entry and the queue is full, it removes the old front.
3. Keep `canSelectRadioAtFront(...)` as the non-mutating capacity query: it must continue to return `false` for a valid new station when the queue is full and the front is not already a radio. Add a private validity check if needed so the direct mutator does not use the capacity query as its admission decision.
4. In `selectRadioAtFront(...)`, validate the station directly, call the helper before `playlist.add(0, station)` when the front is not already a radio, preserve the existing front-radio replacement path, and call `markQueueMutation()` exactly once. The direct mutator must be able to evict a full queue even though `canSelectRadioAtFront(...)` reports that a non-mutating capacity check would fail.

Do not change `add(...)`, `addAtFront(...)`, packet classes, or playback broadcast code in this task.

- [ ] **Step 4: Run the state tests and the related regression suite**

Run:

```bash
./gradlew test --tests com.horizonradio.core.server.PlaylistStateTest
./gradlew test --tests com.horizonradio.server.PlaylistManagerTest
```

Expected: both commands finish with `BUILD SUCCESSFUL`; all existing replacement, radio, reorder, and normal-capacity tests remain green.

- [ ] **Step 5: Commit the state change**

```bash
git add src/main/java/com/horizonradio/core/server/PlaylistState.java src/test/java/com/horizonradio/core/server/PlaylistStateTest.java
git commit -m "fix: evict queue front for direct selections"
```

---

### Task 2: Allow manager direct-play paths to use the state policy

**Files:**
- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java:133-218`
- Test: `src/test/java/com/horizonradio/server/PlaylistManagerTest.java`

**Interfaces:**
- Consumes: the capacity-aware `PlaylistState` methods from Task 1 and existing `PlaylistManager` transport/broadcast seams.
- Produces: direct song and radio handlers that do not emit the full-queue rejection for force selection, while ordinary add handlers retain their existing rejection.

- [ ] **Step 1: Write manager-level failing regressions**

Add this direct-song-at-capacity test using the existing manager/test-player helpers. The current manager full-queue guard makes this test fail before the state mutation:

```java
@Test
public void directPlayAtFullQueueEvictsTheFirstEntry() throws Exception {
    PlaylistManager manager = manager();
    try {
        EntityPlayerMP player = testPlayer();
        manager.handleAddToPlaylist(player, VIDEO_ID, 60_000L);
        for (int index = 1; index < 50; index++) {
            manager.handleAddToPlaylist(player, String.format("%011d", index), 60_000L);
        }

        manager.handlePlayNow(player, SECOND_VIDEO_ID, 60_000L);

        assertEquals(50, playlist(manager).size());
        assertEquals(SECOND_VIDEO_ID, current(manager).getSourceId());
        assertEquals(SECOND_VIDEO_ID, playlist(manager).get(0).getSourceId());
        assertEquals("00000000001", playlist(manager).get(1).getSourceId());
        assertEquals(-1, state(manager).findIndex(MediaSourceType.YOUTUBE, VIDEO_ID));
    } finally {
        manager.shutdown();
    }
}
```

Replace the existing `rejectingRadioAtFullQueuePreservesFiniteTrackAdvancement` test with this expectation. It keeps the configured queue size, removes the old first song, and cancels the old finite-track advancement:

```java
@Test
public void selectingRadioAtFullQueueEvictsTheFirstEntry() throws Exception {
    PlaylistManager manager = manager();
    try {
        EntityPlayerMP player = testPlayer();
        manager.handleAddToPlaylist(player, VIDEO_ID, 60_000L);
        ScheduledFuture<?> scheduledAdvance = advanceFuture(manager);
        for (int index = 1; index < 50; index++) {
            manager.handleAddToPlaylist(player, String.format("%011d", index), 60_000L);
        }

        manager.handleSelectRadio(player, "station-id");

        assertEquals(50, playlist(manager).size());
        assertEquals(MediaSourceType.RADIO, current(manager).getSourceType());
        assertEquals("station-id", current(manager).getSourceId());
        assertEquals("station-id", playlist(manager).get(0).getSourceId());
        assertEquals("00000000001", playlist(manager).get(1).getSourceId());
        assertEquals(-1, state(manager).findIndex(MediaSourceType.YOUTUBE, VIDEO_ID));
        assertTrue(scheduledAdvance.isCancelled());
    } finally {
        manager.shutdown();
    }
}
```

- [ ] **Step 2: Run the manager regressions before production changes**

Run:

```bash
./gradlew test --tests com.horizonradio.server.PlaylistManagerTest
```

Expected: the new direct-song test fails with the current `The queue is full.` guard, and the updated full-radio test fails because `handleSelectRadio(...)` still rejects the full queue through `canSelectRadioAtFront(...)`. Existing manager tests should compile and run around those expected failures.

- [ ] **Step 3: Remove only the direct-selection capacity guards**

In `handlePlayNow(...)`, remove the `state.size() >= maxPlaylistSize` rejection block and its `replacesCurrent` local variable. Keep validation, duplicate lookup, `cancelAdvancement()`, `prepareImmediatePlayback(...)`, `broadcastReplace()`, and `startFiniteTrack(...)` unchanged.

In `handleSelectRadio(...)`, stop treating a full queue as an error. Keep station validation, `cancelAdvancement()`, `selectRadioAtFront(...)`, `broadcastReplace()`, and the radio `TrackSyncPacket` path unchanged. The state method from Task 1 remains the source of truth for whether the station can be selected.

Do not change `handleAddToPlaylist(...)`, chart/bulk-add capacity handling, or the full-queue user message used by ordinary additions.

- [ ] **Step 4: Run manager and full verification**

Run:

```bash
./gradlew test --tests com.horizonradio.server.PlaylistManagerTest
./gradlew test --tests com.horizonradio.core.server.PlaylistStateTest
./gradlew test
./gradlew spotlessApply
./gradlew build
git diff --check origin/main...HEAD
```

Expected: all Gradle commands finish with `BUILD SUCCESSFUL`, Spotless makes no uncommitted source changes, and the diff check produces no output.

- [ ] **Step 5: Inspect scope and commit the manager change**

Confirm the diff contains only the approved spec/plan and the two production/test areas listed in this plan; there must be no client, packet, or protocol changes. Then commit:

```bash
git add src/main/java/com/horizonradio/server/PlaylistManager.java src/test/java/com/horizonradio/server/PlaylistManagerTest.java
git commit -m "fix: allow direct playback on full queue"
```
