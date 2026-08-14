# Client-Local Track Synchronization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan.

**Goal:** Move finite server-mode music delivery to client-local downloads while keeping the server responsible only for playlist control and shared playback timing.

**Architecture:** The server publishes one small `TrackSyncPacket` per track. It contains the YouTube video ID, a monotonically increasing track generation, an absolute server start timestamp three seconds in the future, and the current position/paused state for late joiners. Each client downloads and decodes its own WAV cache entry through the existing Java media backend, then starts at the shared timestamp or seeks forward if the download finishes late. The existing packet relay remains available only for the serverless test harness; radio streaming is unchanged.

**Tech Stack:** Java 8-compatible Forge 1.7.10 networking, Netty `ByteBuf`, existing `AudioDownloadService`/Java media backend, Java Sound `Clip`, JUnit 4, Gradle/Spotless.

## Global Constraints

- The production Minecraft server must not call `AudioDownloadService.download(...)` for finite music playback.
- The playback control packet must contain no title, WAV, PCM, or encoded audio bytes.
- The server must schedule the start after exactly three seconds without waiting for clients.
- Clients that finish after the target time must start at the elapsed server position.
- Add bounded debug chat messages on both sides for prepare, download, start, and catch-up events.
- Preserve radio relay behavior and existing serverless playlist tests.
- Do not alter the user’s existing untracked documentation files.

## Task 1: Define and test the minimal sync protocol

1. Add a failing `TrackSyncPacketTest` covering round-trip fields and the paused/late-join payload semantics.
2. Add `TrackSyncPacket` with bounded video ID encoding and non-negative timing validation.
3. Register a new clientbound packet ID and route it through `ClientboundMessageHandlers`, `CommonProxy`, and `ClientProxy`.
4. Run the focused packet test and confirm it fails before implementation, then passes after implementation.

## Task 2: Add client-local download and playback handoff

1. Add failing tests for accepting a client-local track sync as a shared timestamp/position request without using audio chunks.
2. Add a client audio cache service instance in `ClientProxy` using the existing Java media backend and a client-local cache directory.
3. Extend `HorizonRadioClient` with generation filtering, active-download cancellation, client-local download callbacks, and client debug chat messages.
4. Extend `AudioPlayer` with a local-track preparation/load path that reuses Java Sound clip creation, honors pause/resume packets, and uses the existing clock offset to catch up late downloads.
5. Clear/cancel pending client downloads on disconnect and ignore stale completion callbacks.
6. Run focused client/audio tests.

## Task 3: Switch the production server finite-music path to control-only sync

1. Add a failing server-side test or source-level assertion that the production branch publishes a `TrackSyncPacket` and does not start a finite audio download.
2. In `PlaylistManager`, add the three-second start schedule and track-start future cancellation.
3. Mark the selected track’s server playback start time immediately, broadcast the ID-only sync packet, and schedule the start independently of client readiness.
4. Replace production late-join file reads and readiness pauses with a direct absolute-time sync packet; retain the old relay branch for serverless tests.
5. Guard server-side finite-audio preloading/cancellation so it is not used by the production Minecraft server path.
6. Add server debug chat messages for prepare/start and log the same events to the server logger.
7. Verify pause, seek, skip, previous, chart-duration, and radio transitions still schedule against the server clock.

## Task 4: Verify and document the handoff

1. Run Spotless checks and apply formatting only to changed Java files if needed.
2. Run focused protocol, client audio, and playlist tests.
3. Run the complete test suite and the production build/reobfuscation task.
4. Review the diff for accidental audio payloads, stale server downloads, and user-file changes.
5. Report the exact verification results and the remaining operational limitation: every client still needs network access to YouTube (or the configured media source).

## Self-review

- The plan keeps server bandwidth low by moving only finite audio delivery; the radio mode remains intentionally server-relayed.
- A future timestamp avoids waiting for the slowest client, while the absolute timestamp gives late clients a deterministic catch-up point.
- Existing serverless tests are isolated from the production branch rather than being silently rewritten around new network behavior.
- The main risk is Java Sound load time and clock offset availability; both are handled by asynchronous loading, stale-generation checks, and the existing clock-sync path.
