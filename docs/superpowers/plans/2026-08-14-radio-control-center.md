# Radio Control Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make radio playback behave correctly in the Control Center and Queue tab: active radio rows are green, pause stops only the stream, play reloads the same station, Previous restores the interrupted song, and Next/Skip starts the next queued song.

**Architecture:** Keep the server authoritative. Extend `PlaylistState`/`PlaylistManager` with a radio pause state and a saved interrupted finite track; reuse the existing select/stop/skip/previous packets. Preserve a resumable radio presentation on the client after the stop synchronization, and make the existing screen controls source-aware.

**Tech Stack:** Java 8-compatible Forge 1.7.10 mod, JUnit 4, Gradle, Spotless.

## Global Constraints

- Radio and playlist media remain client-local; the server receives only station IDs or queue song IDs and durations.
- No server-side YouTube or radio directory/stream lookup is added.
- Existing direct song/radio force-play behavior and full-queue front eviction remain unchanged.
- Normal queue additions remain capacity-limited.
- Use existing packet types and wire formats; `StopRadioPacket` continues to stop the local stream while the server keeps the paused radio queue entry.
- Follow TDD: add each regression test first, observe failure, then implement the smallest change.
- Run focused tests, `./gradlew test`, `./gradlew build`, `./gradlew spotlessApply spotlessCheck`, and `git diff --check` before completion.

---

### Task 1: Server radio lifecycle and navigation

**Files:**
- Modify: `src/main/java/com/horizonradio/core/server/PlaylistState.java`
- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java`
- Test: `src/test/java/com/horizonradio/core/server/PlaylistStateTest.java`
- Test: `src/test/java/com/horizonradio/server/PlaylistManagerTest.java`

**Interfaces:**
- Existing `PlaylistState.selectRadioAtFront(PlaylistEntry)` remains the atomic radio-selection entry point.
- Add `PlaylistState.pauseRadioPlayback()` returning `boolean`; it returns `true` only for a currently selected radio and leaves the radio index/source in place while setting playback inactive.
- Existing `PlaylistState.takeLastTrack()` supplies the finite song interrupted by radio.
- Existing manager methods `handleSelectRadio`, `handleStopRadio`, `handleSkipTrack`, and `handlePreviousTrack` retain their packet-facing signatures.

- [ ] **Step 1: Write failing state tests**

  Add tests that establish these exact state transitions:

  ```java
  @Test
  public void radioSelectionStoresInterruptedFiniteTrackAndKeepsSuccessors() {
      PlaylistState state = new PlaylistState(5);
      state.add(PlaylistEntry.youtube("current", 60_000L, "Alice"));
      state.add(PlaylistEntry.youtube("next", 60_000L, "Bob"));
      state.startFiniteTrack(0, "current", 60_000L, 0L);

      assertTrue(state.selectRadioAtFront(PlaylistEntry.radio("station", "Carol")));

      assertEquals("station", state.get(0).getSourceId());
      assertEquals("next", state.get(1).getSourceId());
      assertEquals("current", state.peekLastTrack().getSourceId());
      assertEquals(MediaSourceType.RADIO, state.getCurrentSourceType());
  }

  @Test
  public void pausingRadioKeepsQueueAndRadioSelection() {
      PlaylistState state = new PlaylistState(5);
      state.selectRadioAtFront(PlaylistEntry.radio("station", "Carol"));

      assertTrue(state.pauseRadioPlayback());

      assertFalse(state.isPlaying());
      assertEquals(0, state.getCurrentIndex());
      assertEquals(MediaSourceType.RADIO, state.getCurrentSourceType());
      assertEquals("station", state.get(0).getSourceId());
  }
  ```

- [ ] **Step 2: Run the state tests and verify they fail**

  Run:

  ```bash
  ./gradlew test --tests com.horizonradio.core.server.PlaylistStateTest
  ```

  Expected: FAIL because radio selection currently leaves the interrupted finite track in the queue and no radio pause method exists.

- [ ] **Step 3: Implement the state transitions**

  Update `selectRadioAtFront` so that an active finite current entry is removed once and saved in `lastTrack` before the radio is placed at index 0. Preserve the existing replacement behavior when index 0 is already radio and preserve front eviction when a full queue has no current finite entry. Add `pauseRadioPlayback` without clearing the radio source/index. Do not add a second queue revision for a single radio selection.

- [ ] **Step 4: Add failing manager tests for pause, resume, previous, and skip**

  Extend `PlaylistManagerTest` with tests that call the existing manager methods and assert:

  - `handleStopRadio` leaves the radio entry in the queue, does not start the queued song, and broadcasts a stop synchronization.
  - Calling `handleSelectRadio` with the same station after `handleStopRadio` starts that station again.
  - `handlePreviousTrack` while radio is active restores the finite song that was current before radio selection.
  - `handleSkipTrack` while radio is active removes only the radio entry and starts the first successor song.
  - The same Previous/Skip operations work while the radio is paused, so the UI can leave a paused radio.

  Use the existing `RecordingPacketBroadcaster`, `state(manager)`, `playlist(manager)`, `current(manager)`, and `advanceFuture(manager)` helpers. Keep assertions source-aware with `MediaSourceType`.

- [ ] **Step 5: Run manager tests and verify the new tests fail**

  Run:

  ```bash
  ./gradlew test --tests com.horizonradio.server.PlaylistManagerTest
  ```

  Expected: FAIL because stop currently removes radio and advances, radio previous/skip paths are locked to finite playback, and radio selection does not save the interrupted entry.

- [ ] **Step 6: Implement manager radio behavior**

  Change only the server control flow required by the tests:

  - `handleStopRadio` calls `pauseRadioPlayback`, cancels finite advancement, and broadcasts `TrackSyncPacket.stop` without removing queue index 0.
  - `handleSelectRadio` continues to use `selectRadioAtFront` and emits the existing radio `TrackSyncPacket`.
  - `handleSkipTrack` accepts a selected radio even when paused, removes the radio entry, discards its saved radio return context, broadcasts the queue removal, and calls `startNextFinite`.
  - `handlePreviousTrack` accepts active/paused radio, consumes the saved finite return track, removes the radio entry, broadcasts the replacement queue, and starts the saved finite entry. If no saved finite entry exists, it is a no-op.
  - Keep the existing finite-song Previous restart/queue behavior unchanged.

- [ ] **Step 7: Run the server-focused tests and commit**

  Run:

  ```bash
  ./gradlew test --tests com.horizonradio.core.server.PlaylistStateTest --tests com.horizonradio.server.PlaylistManagerTest
  ```

  Expected: PASS. Then commit:

  ```bash
  git add src/main/java/com/horizonradio/core/server/PlaylistState.java src/main/java/com/horizonradio/server/PlaylistManager.java src/test/java/com/horizonradio/core/server/PlaylistStateTest.java src/test/java/com/horizonradio/server/PlaylistManagerTest.java
  git commit -m "fix: make radio controls queue-aware"
  ```

### Task 2: Client radio pause/resume presentation

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Test: `src/test/java/com/horizonradio/client/HorizonRadioClientTrackSyncTest.java`
- Test: `src/test/java/com/horizonradio/client/HorizonRadioClientFavoritesTest.java` only if the paused-radio source expectation changes

**Interfaces:**
- Existing `HorizonRadioClient.handleTrackSync(TrackSyncPacket)` remains the client sync entry point.
- Existing `HorizonRadioClient.sendSelectRadio(String)` remains the resume request.
- Existing `ClientRadioPresentation.inactive(...)` represents a resumable but not active station.

- [ ] **Step 1: Write failing client sync tests**

  Add a test that starts a radio with `TrackSyncPacket.radio`, sends `TrackSyncPacket.stop`, and asserts the local player is inactive but the cached presentation still contains the station UUID/name and can be used as a resumable source. Add a test that the stopped radio does not remain active/green.

- [ ] **Step 2: Run the client sync tests and verify failure**

  Run:

  ```bash
  ./gradlew test --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest
  ```

  Expected: FAIL because `stopLocalPlayback` currently clears the radio presentation entirely.

- [ ] **Step 3: Preserve radio context on a stop synchronization**

  In `stopLocalPlayback`, capture the active radio source UUID and station name before clearing the active playback source. Stop the local stream, then publish `ClientRadioPresentation.inactive(generation, stationUuid, stationName, "", false)` for a radio stop. Keep the existing full-clear behavior for finite tracks and keep authoritative queue removal able to discard a radio presentation.

  Remove the tab restriction from `HorizonRadioScreen.canResumeRadio` in the later UI task so the cached station can be reselected from the Queue tab as well.

- [ ] **Step 4: Run client sync tests and commit**

  Run:

  ```bash
  ./gradlew test --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest --tests com.horizonradio.client.HorizonRadioClientFavoritesTest
  ```

  Expected: PASS, with any favorite assertion updated only if it specifically conflicts with the new paused-radio source semantics. Commit:

  ```bash
  git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/test/java/com/horizonradio/client/HorizonRadioClientTrackSyncTest.java src/test/java/com/horizonradio/client/HorizonRadioClientFavoritesTest.java
  git commit -m "fix: retain paused radio context"
  ```

### Task 3: Queue row highlighting and Control Center behavior

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Test: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- Existing control IDs remain: Previous `5`, Playback `6`, Next `7`, Loop `8`, Shuffle `4`.
- Existing transport methods remain: `sendStopRadio`, `sendSelectRadio`, `sendPreviousTrack`, and `sendSkipTrack`.
- Existing `ClientRadioPresentation` provides active station UUID and paused station UUID.

- [ ] **Step 1: Write failing GUI tests**

  Update/add tests for these exact UI results:

  - `isPlaylistRowPlaying(0, true, true)` is true for an active radio row, while a non-first row remains false.
  - Active radio keeps buttons 5 and 7 enabled; buttons 4 and 8 remain disabled; button 6 is enabled and sends `sendStopRadio`.
  - Paused radio keeps buttons 5 and 7 enabled; button 6 is enabled and sends `sendSelectRadio` with the cached station UUID, regardless of whether the current tab is Queue or Radio.

- [ ] **Step 2: Run GUI tests and verify failure**

  Run:

  ```bash
  ./gradlew test --tests com.horizonradio.client.GuiLayoutTest
  ```

  Expected: FAIL because active radio rows are explicitly excluded and Previous/Next are disabled in radio mode.

- [ ] **Step 3: Implement source-aware row and control behavior**

  In `drawPlaylistTab`, treat a queue entry as playing when it is a radio entry matching the active station UUID; retain the existing finite-song highlight logic. Update the helper test expectations accordingly.

  Make `canResumeRadio` depend only on a valid cached station, not `currentTab == RADIO_TAB`. Keep the middle button's active-radio action as `sendStopRadio` and its paused-radio action as `sendSelectRadio`. Enable Previous and Next while radio is active or resumable, while keeping Shuffle and Loop disabled. Do not alter normal music controls.

- [ ] **Step 4: Run GUI and combined focused tests**

  Run:

  ```bash
  ./gradlew test --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest --tests com.horizonradio.server.PlaylistManagerTest
  ```

  Expected: PASS.

- [ ] **Step 5: Format, run the complete verification suite, and commit**

  Run:

  ```bash
  ./gradlew spotlessApply spotlessCheck
  ./gradlew test
  ./gradlew build
  git diff --check
  git status --short --branch
  ```

  Expected: all Gradle tasks succeed, no whitespace errors, and only the intended files are modified. Commit:

  ```bash
  git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
  git commit -m "fix: expose radio navigation controls"
  ```
