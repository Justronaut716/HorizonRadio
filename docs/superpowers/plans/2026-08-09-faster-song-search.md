# Faster Song Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce song search latency while continuing to return up to ten playable music results.

**Architecture:** Pass the server's maximum track duration into the YouTube search service so it can recognize the same valid music candidates used by the final server filter. Stop following continuation pages as soon as ten valid candidates are collected; continue only when filtering leaves fewer than ten. Track the newest search request per player so late responses cannot replace newer results.

**Tech Stack:** Java 8-compatible Forge mod code, CompletableFuture, JUnit 4, Gradle.

## Global Constraints

- Preserve the existing three-page fallback when fewer than ten playable songs are found.
- Preserve the existing non-music and maximum-duration filters.
- Do not change the network packet format.
- Preserve unrelated uncommitted work in the shared workspace.

---

### Task 1: Add failing search optimization tests

**Files:**
- Modify: `src/test/java/com/horizonradio/server/YouTubeServiceTest.java`
- Modify: `src/test/java/com/horizonradio/server/PlaylistManagerTest.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

- [ ] **Step 1: Test that ten valid music results stop continuation requests.**
- [ ] **Step 2: Test that invalid or too-long candidates still cause continuation loading.**
- [ ] **Step 3: Test that an older request token is rejected after a newer request exists.**
- [ ] **Step 4: Update the UI estimate expectation to the shorter search request.**
- [ ] **Step 5: Run the focused tests and confirm they fail against the current implementation.**

### Task 2: Stop search pagination as soon as enough playable results exist

**Files:**
- Modify: `src/main/java/com/horizonradio/server/YouTubeService.java`
- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java`

- [ ] **Step 1: Add a duration-aware `YouTubeService.search(String, long)` overload while retaining the existing compatibility overload.**
- [ ] **Step 2: Count candidates using `MusicSearchFilter` and strict duration parsing, matching the server's existing rules.**
- [ ] **Step 3: Stop continuation requests at ten valid candidates and retain the three-page fallback when fewer are available.**
- [ ] **Step 4: Call the new overload from `PlaylistManager.handleSearch`.**

### Task 3: Ignore stale responses and shorten the visual estimate

**Files:**
- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`

- [ ] **Step 1: Assign each player a monotonically increasing search request token.**
- [ ] **Step 2: Check the token before enqueueing and before sending search results.**
- [ ] **Step 3: Remove the token on disconnect.**
- [ ] **Step 4: Reduce the search progress estimate to 1.5 seconds.**

### Task 4: Verify the complete change

- [ ] **Step 1: Run focused YouTube, playlist, and GUI tests.**
- [ ] **Step 2: Run the complete Gradle build with the project Java 25/Gradle cache settings.**
- [ ] **Step 3: Run `git diff --check` and review the changed files.**
