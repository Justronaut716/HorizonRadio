# Chart Action Metadata Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve missing chart durations on the client before sending compact add/play requests, so chart items can enter the shared playlist or start immediately.

**Architecture:** Keep chart discovery unchanged and resolve only the selected chart items through the existing `ClientMetadataCache`. `HorizonRadioClient` owns asynchronous resolution, transport calls, cache updates, and debug messages; `HorizonRadioScreen` forwards raw chart results and clears pending UI state through the existing screen helpers. Resolved requests still contain only `videoId + durationMs` on the server boundary.

**Tech Stack:** Java 8-compatible `CompletableFuture`, Forge client transport, JUnit 4, Gradle, Spotless.

## Global Constraints

- Do not add server-side YouTube or audio traffic.
- Do not send chart titles, artists, or audio bytes in the playlist action packet.
- Reject missing, non-positive, and server-over-limit durations locally.
- Preserve chart request order for bulk additions.
- Preserve existing behavior for chart results that already have a valid duration.

---

### Task 1: Add failing tests for lazy chart action resolution

**Files:**
- Modify: `src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- Consumes: `HorizonRadioClient.sendAddChartsToPlaylist`, a new raw-result direct-play entry point, `ClientMediaService`, and the existing transport seam.
- Produces: Regression coverage proving missing-duration chart actions resolve metadata, preserve bulk order, and clear pending state on failure.

- [x] **Step 1: Add a deferred metadata provider and direct client scheduler to the discovery test fixture.**

  Extend the test provider with `resolveVideo(String)` support through `ClientMediaService`'s production constructor or a test `AudioDownloadService` fixture, and make the provider return controllable futures for chart video IDs. Keep the scheduler synchronous so completion callbacks can be asserted deterministically.

- [x] **Step 2: Write a failing test for a chart add with an empty duration.**

  Set an active `HorizonRadioScreen`, configure a metadata provider that resolves `chart-1` to a `SearchResult` with duration `2:03`, call `HorizonRadioClient.sendAddChartsToPlaylist(Collections.singletonList(new HorizonRadioScreen.SearchResult("chart-1", "Chart", "", "", "")))`, complete the future, and assert the recording transport receives one chart selection for `chart-1` with `123000L`.

- [x] **Step 3: Write a failing test for bulk order and a failed resolution.**

  Submit `[chart-a, chart-b, chart-c]` with missing durations, resolve `chart-a` and `chart-c`, fail `chart-b`, and assert the transport receives only `chart-a` then `chart-c`. Assert the screen no longer reports `chart-b` as pending.

- [x] **Step 4: Write a failing test for direct playback with an empty duration.**

  Configure metadata for `chart-play` as `4:00`, invoke the new chart-result play method, complete the future, and assert the transport receives `chart-play|240000` through the long-duration overload.

- [x] **Step 5: Write a failing test for the already-resolved fast path.**

  Submit a chart result with duration `1:30`, assert the transport is called immediately, and assert the metadata provider was not queried.

- [x] **Step 6: Run only the new tests and confirm they fail for the missing action-resolution behavior.**

  Run:

  ```bash
  ./gradlew test --no-daemon --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest --tests com.horizonradio.client.GuiLayoutTest
  ```

  Expected: compilation or assertion failures because raw chart actions are currently discarded when `duration` is empty and direct play is not exposed through a resolving entry point.

### Task 2: Implement client-side chart resolution and action dispatch

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java:487-537, 701-726, 774-786, 1418-1483`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java:706-760, 999-1023, 1226-1251, 1779-1818`

**Interfaces:**
- Consumes: raw `HorizonRadioScreen.SearchResult` values, cached client metadata, and existing `ClientTransport` long-duration overloads.
- Produces: `sendAddChartsToPlaylist(List<?>)` that resolves missing durations asynchronously, `sendPlayNow(HorizonRadioScreen.SearchResult)` for chart direct play, and screen methods to update resolved chart durations and clear failed pending IDs.

- [x] **Step 1: Add the minimal resolution result type and duration validation helpers.**

  Add private Java 8-compatible helper types/methods in `HorizonRadioClient` that retain the original chart ID, either a valid `PlaylistSelection` or a failure message, and validate `0 < durationMs < maxTrackDurationMs()` before creating a selection.

- [x] **Step 2: Implement one-item metadata resolution through `clientMetadataCache.video(videoId)`.**

  If the chart result already has a valid duration, return a completed resolution without touching the cache. Otherwise, use the existing cache, extract `metadata.getDuration()`, and return a failure resolution when the metadata is null, malformed, or over the configured server limit. Do not block on the future.

- [x] **Step 3: Replace the add path's immediate mapping with ordered asynchronous resolution.**

  For non-remove chart additions, resolve every raw chart result concurrently, collect the already-normalized results in input order, send one `sendAddChartSelections` call when all futures complete, update cached/screen durations for successful metadata, clear pending IDs for both successful and failed results, and emit client debug messages for resolution start/failure/success. Keep the existing immediate path for `PlaylistSelection` values and empty compatibility calls.

- [x] **Step 4: Add the raw chart direct-play method.**

  Add `sendPlayNow(HorizonRadioScreen.SearchResult result)`. It must dispatch immediately for a valid duration; otherwise resolve metadata using the same helper and call the existing `sendPlayNow(String,long)` only after success. Emit a client debug message when resolution starts or fails.

- [x] **Step 5: Update the GUI to pass raw chart results and expose resolved durations.**

  Change chart add calls at the bulk and individual click sites to pass `request` directly instead of first filtering through `toPlaylistSelections`. Change `playResultNow` to call the raw-result client method. Add a screen helper that replaces a chart row's duration by video ID without resetting the whole chart loading state, and add a client-cache helper that updates the matching cached chart row.

- [x] **Step 6: Clear pending entries on all failed asynchronous paths.**

  Ensure a null media service, thrown lookup, null metadata result, invalid duration, or failed future removes the original video ID from `pendingChartAdds`; successful entries remain compatible with the existing playlist update cleanup.

### Task 3: Verify, format, and document the completed fix

**Files:**
- Modify: `docs/superpowers/specs/2026-08-13-chart-action-metadata-resolution-design.md` only if implementation details require clarification.

**Interfaces:**
- Consumes: completed client resolution implementation and regression tests.
- Produces: formatted, tested code ready for the existing `clientside-loading` PR.

- [x] **Step 1: Run focused client tests.**

  ```bash
  ./gradlew test --no-daemon --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest --tests com.horizonradio.client.GuiLayoutTest
  ```

- [x] **Step 2: Run the complete test suite.**

  ```bash
  ./gradlew test --no-daemon
  ```

- [x] **Step 3: Run formatting verification.**

  ```bash
  ./gradlew spotlessCheck --no-daemon
  ```

- [x] **Step 4: Run the build/package verification used by this project.**

  ```bash
  ./gradlew build --no-daemon
  ```

- [x] **Step 5: Inspect the final diff and confirm only the intended tracked files changed.**

  ```bash
  git diff --check
  git status --short
  ```

- [x] **Step 6: Commit the implementation and tests to the existing feature branch.**

  ```bash
  git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
  git commit -m "fix: resolve chart metadata before actions"
  ```
