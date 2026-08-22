# Modernization GUI State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove duplicated tab/loading/control state from `HorizonRadioScreen` while preserving the 300x285 immediate-mode UI and every visible interaction.

**Architecture:** Introduce pure Java presentation-state objects with deterministic time input. Migrate one tab pair at a time, then centralize playback-control derivation; leave drawing coordinates and Forge input overrides in the screen.

**Tech Stack:** Java 8-compatible output via Jabel, Forge `GuiScreen`, JUnit 4, existing `GuiLayoutTest` source/geometry assertions.

**Spec:** `docs/superpowers/specs/2026-08-22-project-modernization-design.md`

## Global Constraints

- Execute after the client-controller plan.
- Preserve panel size, tab order, keyboard/mouse behavior, loading timing, reveal delay, scrollbar behavior, control icons, and radio restrictions.
- State classes must not import Minecraft, Forge, LWJGL, or network packets.
- Use explicit `nowMs` in tests; do not sleep.
- Migrate behavior before deleting old fields, then remove duplication in a separate commit.

---

### Task 1: Introduce reusable result-pane state

**Files:**
- Create: `src/main/java/com/horizonradio/client/presentation/ResultPaneState.java`
- Create: `src/test/java/com/horizonradio/client/presentation/ResultPaneStateTest.java`

**Interfaces:**
- Generic result storage, loading/error/progress, reveal scheduling, and scroll offset.
- Constructor receives progress estimate and reveal delay.

- [ ] **Step 1: Write deterministic failing tests**

```java
@Test
public void completionKeepsResultsHiddenUntilRevealDeadline() {
    ResultPaneState<String> state = new ResultPaneState<String>(1000L, 150L);
    state.begin(100L);
    state.complete(Arrays.asList("one", "two"), 600L);
    assertTrue(state.getVisibleResults().isEmpty());
    state.tick(749L);
    assertTrue(state.getVisibleResults().isEmpty());
    state.tick(750L);
    assertEquals(Arrays.asList("one", "two"), state.getVisibleResults());
}
```

Add progress clamping, immediate error, stale error clearing on begin, defensive copies, empty completion, scroll clamp, and reset tests.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.client.presentation.ResultPaneStateTest
```

- [ ] **Step 3: Implement the state object**

Use this surface:

```java
public final class ResultPaneState<T> {
    public ResultPaneState(long progressEstimateMillis, long revealDelayMillis);
    public synchronized void begin(long nowMs);
    public synchronized void complete(List<T> results, long nowMs);
    public synchronized void fail(String message);
    public synchronized void tick(long nowMs);
    public synchronized void reset();
    public synchronized boolean isLoading();
    public synchronized float getProgress();
    public synchronized String getError();
    public synchronized List<T> getVisibleResults();
    public synchronized List<T> getAllResults();
    public synchronized int getScrollOffset();
    public synchronized void setScrollOffset(int value, int visibleRows);
}
```

Progress is `min(0.95f, elapsed / estimate)` while loading and `1.0f` after completion until reveal. Normalize null error to empty and null result list to empty.

- [ ] **Step 4: Verify GREEN and commit**

```bash
./gradlew test --tests com.horizonradio.client.presentation.ResultPaneStateTest
git add src/main/java/com/horizonradio/client/presentation/ResultPaneState.java
git add src/test/java/com/horizonradio/client/presentation/ResultPaneStateTest.java
git commit -m "refactor: add reusable result pane state"
```

### Task 2: Migrate search and charts panes

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`
- Modify: `src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java`

**Interfaces:**
- Search uses `ResultPaneState<SongResultView>(1500L, 150L)`.
- Charts uses `ResultPaneState<SongResultView>(1000L, 150L)` plus chart region and pending-add set.

- [ ] **Step 1: Add characterization assertions**

Extend `GuiLayoutTest` to assert search/chart list-top offsets during loading, progress visibility, 150 ms reveal timing, scroll preservation/clamping, chart refresh enablement, and error placement.

```java
assertEquals(70, HorizonRadioScreen.searchListTopOffset(true));
assertEquals(55, HorizonRadioScreen.searchListTopOffset(false));
assertFalse(HorizonRadioScreen.shouldEnableChartRefreshButton(true, false));
```

- [ ] **Step 2: Run characterization tests**

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
./gradlew test --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
```

- [ ] **Step 3: Replace search state fields**

Replace `searchResults`, `searchError`, `searchProgress`, `searchLoading`, `searchStartedAt`, `searchResultsRevealPending`, `searchResultsRevealAt`, and `searchScrollOffset` with one search pane. Forward `begin`, completion, failure, tick, visible results, and scroll operations.

- [ ] **Step 4: Replace chart state fields**

Replace chart results/error/progress/loading/start/reveal/scroll fields with one chart pane. Keep `chartRegionCode`, `chartSearchMessage`, and `pendingChartAdds` because they are chart-specific domain state.

- [ ] **Step 5: Delete duplicate search/chart helpers**

Delete `scheduleSearchResultsReveal`, `scheduleChartResultsReveal`, `updateSearchProgress`, and `updateChartProgress`. Replace their call sites with pane methods; keep layout-only static helpers used by tests.

- [ ] **Step 6: Test and commit**

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
./gradlew test --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client
git commit -m "refactor: unify search and chart pane state"
```

### Task 3: Migrate playlist discovery and radio panes

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`
- Modify: `src/test/java/com/horizonradio/client/RadioClientStateTest.java`

**Interfaces:**
- Playlist discovery uses `ResultPaneState<SongResultView>(1500L, 150L)`.
- Radio uses `ResultPaneState<RadioStationView>(400L, 150L)` plus popular-request flag and active radio presentation.

- [ ] **Step 1: Add playlist/radio characterization tests**

Cover loading list tops, reveal timing, error reset, popular-radio one-shot request, active station label, and independent scroll state.

```java
assertEquals(70, HorizonRadioScreen.radioListTopOffset(true));
assertEquals(55, HorizonRadioScreen.radioListTopOffset(false));
assertEquals("BigFM - On Air", HorizonRadioScreen.radioNowPlayingDisplayLabel("BigFM", true));
```

- [ ] **Step 2: Run characterization tests**

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
./gradlew test --tests com.horizonradio.client.RadioClientStateTest
```

- [ ] **Step 3: Replace playlist pane fields/helpers**

Replace playlist results/loading/error/progress/start/reveal/scroll fields with one pane. Keep playlist URL field and `pendingPlaylistAdds` outside the generic state.

- [ ] **Step 4: Replace radio pane fields/helpers**

Replace radio results/loading/error/progress/start/reveal/scroll fields with one pane. Keep `radioPopularRequested` and `radioState` as radio-specific state.

- [ ] **Step 5: Delete remaining duplicate reveal/progress methods**

Delete `schedulePlaylistResultsReveal`, `scheduleRadioResultsReveal`, `updatePlaylistProgress`, and `updateRadioProgress`. Collapse `updatePendingResultReveals` into four pane `tick(nowMs)` calls.

- [ ] **Step 6: Test and commit**

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
./gradlew test --tests com.horizonradio.client.RadioClientStateTest
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client
git commit -m "refactor: unify playlist and radio pane state"
```

### Task 4: Derive playback controls from one immutable state

**Files:**
- Create: `src/main/java/com/horizonradio/client/presentation/PlaybackControlState.java`
- Create: `src/test/java/com/horizonradio/client/presentation/PlaybackControlStateTest.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- Input: finite/radio mode, active/paused radio, resumable station, now playing, paused, looping, shuffling.
- Output: visibility/enabled/selected/icon decisions for play, previous, next, loop, shuffle, favorite, and progress bar.

- [ ] **Step 1: Write the control matrix as failing tests**

```java
@Test
public void activeRadioEnablesStopPreviousNextButDisablesLoopShuffleAndProgress() {
    PlaybackControlState state = PlaybackControlState.forRadio(true, false, true, true);
    assertTrue(state.isPlayVisible());
    assertTrue(state.isPreviousEnabled());
    assertTrue(state.isNextEnabled());
    assertFalse(state.isLoopEnabled());
    assertFalse(state.isShuffleEnabled());
    assertFalse(state.isProgressVisible());
    assertEquals(PlaybackControlState.CenterIcon.PAUSE, state.getCenterIcon());
}
```

Add finite playing/paused, stopped resumable radio, no source, favorite availability, loop/shuffle selected, and music-mode cases.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.client.presentation.PlaybackControlStateTest
```

- [ ] **Step 3: Implement pure derivation factories**

```java
public final class PlaybackControlState {
    public enum CenterIcon { PLAY, PAUSE }
    public static PlaybackControlState forFinite(boolean hasSource, boolean paused,
        boolean looping, boolean shuffling, boolean favoriteAvailable);
    public static PlaybackControlState forRadio(boolean active, boolean resumable,
        boolean hasPrevious, boolean favoriteAvailable);
    public static PlaybackControlState empty();
    public boolean isPreviousVisible();
    public boolean isCenterVisible();
    public boolean isNextVisible();
    public boolean isLoopVisible();
    public boolean isShuffleVisible();
    public boolean isFavoriteVisible();
    public boolean isLoopActive();
    public boolean isShuffleActive();
    public CenterIcon getCenterIcon();
}
```

Factories fully initialize immutable final fields; they do not inspect screen widgets.

- [ ] **Step 4: Replace branch-heavy widget mutation**

Have `HorizonRadioScreen.updateControlVisibility()` compute one state and apply it. Remove parameters that inspections proved constant (`visible`, `radioActive`, always-true `hasNowPlaying`) and replace always-inverted helpers with positive names used directly.

- [ ] **Step 5: Test and commit**

```bash
./gradlew test --tests com.horizonradio.client.presentation.PlaybackControlStateTest
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
git add src/main/java/com/horizonradio/client/presentation/PlaybackControlState.java
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java
git add src/test/java/com/horizonradio/client
git commit -m "refactor: centralize playback control state"
```

### Task 5: Remove residual GUI duplication and dead seams

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- Screen remains the only Forge `GuiScreen` implementation.
- Pure state/layout helpers remain package-private only when directly tested or reused.

- [ ] **Step 1: Run semantic usage and IDE inspections**

Verify framework overrides by exact superclass signature before removal. The current string overload `keyTyped(String, int)`, three-argument `mouseDragged(Minecraft, int, int)`, and `getChartSearchMessage` are deletion candidates only if neither Forge nor tests reference their exact signatures.

```bash
rg -n 'keyTyped\(|mouseDragged\(|getChartSearchMessage|chartQueueButtonLabel|setVisible\(' src/main/java src/test/java
```

- [ ] **Step 2: Remove confirmed dead methods and parameters**

Delete declaration-only test seams. Simplify helpers with constant parameters by inlining the constant into the method body and removing the parameter from all callers. Rename always-inverted predicates to the positive condition actually consumed.

- [ ] **Step 3: Consolidate repeated queue/result hit testing**

Keep one inclusive rectangle helper:

```java
private static boolean contains(int x, int y, int width, int height, int mouseX, int mouseY) {
    return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
}
```

Use it for queue buttons, bulk/clear buttons, scrollbars, and controls; preserve existing inclusive-edge behavior.

- [ ] **Step 4: Re-run GUI inspections and tests**

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
./gradlew test --tests 'com.horizonradio.client.*'
```

Expected: no unresolved dead-code, constant-condition, always-inverted, or duplicated progress/reveal warning in `HorizonRadioScreen`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java
git add src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "refactor: simplify gui state and helpers"
```

### Task 6: Verify the GUI phase

**Files:**
- Modify only GUI/client files already touched by this plan if integration verification exposes a defect.

**Interfaces:**
- Produces: identical visible behavior with shared pane/control state.

- [ ] **Step 1: Run focused GUI/client tests**

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
./gradlew test --tests 'com.horizonradio.client.presentation.*'
./gradlew test --tests 'com.horizonradio.client.*'
```

- [ ] **Step 2: Run complete quality gates**

```bash
./gradlew spotlessCheck test packagingTest build
```

Expected: PASS with no unexpected skips.

- [ ] **Step 3: Inspect diff and source size**

```bash
git diff --check
wc -l src/main/java/com/horizonradio/client/HorizonRadioScreen.java
git status --short
```

Expected: no whitespace errors; the screen no longer declares four copies of result/loading/progress/reveal state.
