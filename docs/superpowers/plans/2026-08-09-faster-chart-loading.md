# Faster Chart Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show country chart results immediately after the YouTube charts response instead of waiting for duration metadata for all 50 videos.

**Architecture:** The chart refresh path will publish the raw weekly chart entries immediately. Empty durations will be treated as unresolved chart metadata: they remain visible, are resolved when a chart song is added in bulk or when a user directly plays one, and no longer block chart discovery. The chart progress estimate will be reduced to match the faster first response.

**Tech Stack:** Java 8-compatible Forge mod code, CompletableFuture, JUnit 4, Gradle.

## Global Constraints

- Keep the server authoritative for maximum track duration validation.
- Preserve the existing seven-day per-region chart cache.
- Keep the existing chart packet format; unresolved duration is represented by the existing empty duration string.
- Preserve all unrelated uncommitted work in the shared workspace.

---

### Task 1: Prove immediate chart publication and lazy playback validation

**Files:**
- Modify: `src/test/java/com/horizonradio/server/PlaylistManagerTest.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- The chart refresh test will invoke the existing refresh boundary with a fake YouTube response and a duration future that never completes.
- The playback test will verify that an empty chart duration triggers one metadata lookup and only starts playback after a valid duration is returned.

- [ ] **Step 1: Write failing tests** for chart cache publication without duration lookup, lazy chart playback duration resolution, and the shorter chart progress estimate.
- [ ] **Step 2: Run the focused tests** and verify they fail because the current implementation blocks on `extractVideoDurationOutput` and rejects empty play-now durations.
- [ ] **Step 3: Keep the test fixtures deterministic** by making the fake duration lookup count calls and expose a completable future.

### Task 2: Publish charts without blocking on all durations

**Files:**
- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java`

**Interfaces:**
- `refreshChartsIfNeeded(ChartRegion)` will complete the chart refresh as soon as `YouTubeService.fetchTopCharts` returns.
- Chart packet construction will keep entries whose duration is empty, while still filtering known durations that exceed the configured server limit.

- [ ] **Step 1: Remove the blocking duration future from the initial chart refresh path.**
- [ ] **Step 2: Centralize chart packet construction so known over-limit entries are filtered and unknown durations are retained.**
- [ ] **Step 3: Add a lazy single-video duration lookup for direct chart play-now requests, then re-enter the existing validated play-now path with the resolved duration.**
- [ ] **Step 4: Keep bulk chart additions on their existing duration lookup path so the server validates every entry before adding it.**

### Task 3: Match the UI progress estimate to the fast path

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- The chart progress estimate will be reduced from the former 36-second visual estimate to a short estimate for the initial chart API request.

- [ ] **Step 1: Set the chart estimate to 4 seconds.**
- [ ] **Step 2: Keep the existing 100% completion flash and delayed result reveal behavior unchanged.**

### Task 4: Verify the complete change

**Files:**
- Inspect all modified files and the resulting diff.

- [ ] **Step 1: Run the focused server and client tests.**
- [ ] **Step 2: Run the complete Gradle build with the project Java 25/Gradle cache settings.**
- [ ] **Step 3: Run `git diff --check` and review that only the chart-loading changes and their tests are included.**
