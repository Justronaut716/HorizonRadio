# HorizonRadio Playback Modes Design

**Date:** 2026-08-14
**Branch:** `UI-Improvement`

## Goal

Add three client-visible playback mode buttons:

- `Privat`: playback and playlist are local to this client; HorizonRadio Minecraft server packets and synchronization are disabled for this client.
- `Server`: preserve the current server-authoritative playlist and playback synchronization.
- `Group`: show the future mode in the UI, but do not implement its behavior yet.

## Current context

The client currently has one server-authoritative `ClientQueueState`. GUI actions are routed through `HorizonRadioClient.ClientTransport`, whose Forge implementation sends playlist, playback, radio, and clock-sync packets. Incoming server packets are forwarded by `ClientProxy` into `HorizonRadioClient`. Audio bytes and YouTube/radio discovery already run client-side.

The existing local audio path can prepare and play a downloaded WAV through `AudioPlayer.beginLocalTrack` and `AudioPlayer.loadLocalTrack`. `ClientRadioPlayback` already provides direct client-side radio playback. The new mode behavior should reuse these paths instead of introducing another audio backend.

## Decisions

### 1. One mode gateway, not duplicated client implementations

Add a `PlaybackMode` value with `PRIVATE`, `SERVER`, and `GROUP`. `HorizonRadioClient` remains the single client state boundary and routes behavior based on the active mode.

`SERVER` is the default for old or missing configuration. The selected `PRIVATE`/`SERVER` mode is persisted in `horizonradio-client.json`; the private playlist itself is not persisted.

`GROUP` is represented by a visible, disabled button. It cannot be selected and does not change client state.

### 2. Separate server and private queue state

Keep the existing `ClientQueueState` as the server queue view. Add an independent local queue state for private playback. The local state owns:

- private playlist entries and order;
- current entry and playback generation;
- pause and seek position;
- loop and shuffle flags;
- local previous-track bookkeeping.

The GUI continues to consume `HorizonRadioClient.getCachedPlaylist()`, while the client refreshes that cache from the active queue state.

### 3. Private actions never use the Forge transport

In `PRIVATE`, add, play-now, remove, clear, reorder, seek, play/pause, previous, next, loop, shuffle, radio select, and radio stop execute against the private state and local audio services only. Search, metadata resolution, radio lookup, and HTTP audio downloads remain allowed because they are already client-side operations.

Private finite tracks use the existing local download service and `AudioPlayer`. A client tick advances to the next private entry when its local duration expires. Loop repeats the current entry; shuffle changes only queued private entries.

Private radio selection uses `ClientRadioPlayback` directly and does not send a server packet.

### 4. Server mode remains the existing behavior

In `SERVER`, all existing `ClientTransport` calls, server queue updates, track synchronization, pause/resume packets, loop/shuffle updates, and clock synchronization remain active.

When switching from `PRIVATE` to `SERVER`:

1. stop local audio and cancel the active private download;
2. clear the private queue and private presentation;
3. reset the local server queue view to remove stale private/server display state;
4. request a complete server playlist/track snapshot;
5. send a fresh clock-sync request.

### 5. Private mode ignores synchronization

When switching to `PRIVATE`:

1. stop the current server-driven local audio and cancel its download;
2. clear the private queue and start with an empty local playlist;
3. stop applying incoming playlist, track, pause, resume, loop, shuffle, and clock-sync packets;
4. do not emit playlist, playback, radio, resync, or clock-sync packets from local actions.

The Minecraft server may continue its own shared playlist for other players; this feature only removes this client from HorizonRadio synchronization and does not add per-player server mode state.

Every asynchronous local download/audio callback must verify the current mode and playback generation before changing playback state, so a late callback cannot revive a track after a mode switch.

## UI

`HorizonRadioScreen` adds three text buttons above the existing panel. The active `Privat` or `Server` button uses the existing active-button styling. `Group` is visible but disabled and has no action.

The current panel, tabs, search controls, queue controls, and audio controls remain in place.

## Files and responsibilities

- Create `src/main/java/com/horizonradio/client/PlaybackMode.java`: mode values and persisted-name conversion.
- Create `src/main/java/com/horizonradio/core/client/ClientLocalPlaylistState.java`: local queue/playback state and deterministic operations needed by private mode.
- Modify `src/main/java/com/horizonradio/client/HorizonRadioClientConfig.java`: load/save the selected mode with `SERVER` fallback.
- Modify `src/main/java/com/horizonradio/client/HorizonRadioClient.java`: mode state, transitions, queue routing, local playback, local tick advancement, and synchronization guards.
- Modify `src/main/java/com/horizonradio/client/ClientProxy.java`: avoid clock-sync startup work outside `SERVER` and guard scheduled incoming sync work.
- Modify `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`: create, display, and handle the three mode buttons.
- Add focused tests for mode persistence, local state, client routing/sync guards, and button behavior.

No server-side classes, protocol packet formats, or new network packets are required.

## Error handling

- Invalid persisted mode values load as `SERVER`.
- If a private download fails, the local state remains consistent and no Forge packet is sent; the UI reports the existing local failure behavior.
- Mode switches cancel/stop old local work before publishing the new active mode to the GUI.
- A stale server packet or asynchronous download callback is ignored when its mode/generation no longer matches.
- `GROUP` cannot trigger any network or playback action.

## Verification plan

Run focused tests after each state boundary, then the full project checks:

```text
./gradlew test --tests com.horizonradio.client.HorizonRadioClientModeTest
./gradlew test --tests com.horizonradio.core.client.ClientLocalPlaylistStateTest
./gradlew spotlessCheck checkstyleMain checkstyleTest test
```

The final test suite must prove:

1. private UI actions do not call the transport;
2. private incoming synchronization is ignored;
3. returning to server mode requests a fresh snapshot and clock sync;
4. private local queue behavior is independent from `ClientQueueState`;
5. the Group button is present and disabled;
6. existing server-mode tests continue to pass.
