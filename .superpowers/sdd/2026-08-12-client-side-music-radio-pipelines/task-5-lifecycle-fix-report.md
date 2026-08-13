# Task 5 Lifecycle Fix Report

## Result

The server now sends a compact, source-aware `TrackSyncPacket.stop(generation)`
when authoritative playback ends without a finite successor. The packet uses a
reserved zero source-type marker followed by only the generation; it adds no
relay packet, server media work, or queue source type.

`PlaylistManager` broadcasts that transition after the queue `REMOVE`/`CLEAR`
delta for removal, clear, skip, and automatic completion of the final finite
track. Snapshot/resync also sends the stop state when no playback source is
active. The client stops local finite and radio playback, cancels finite
downloads, clears presentation state, and retains the stop generation so stale
finite sync/download callbacks cannot reactivate playback.

Radio selection now proves the queue mutation can fit before cancelling the
active finite advancement future. A full-queue rejection therefore leaves the
current finite source and its scheduled advancement unchanged.

## Coverage

- `PlaylistManagerTest` captures production broadcast calls through a narrow
  test seam and verifies stop transitions plus generation changes for removing,
  clearing, and skipping the final finite track.
- `PlaylistManagerTest` verifies rejected full-queue radio selection keeps the
  exact advancement future live.
- `TrackSyncPacketTest` verifies the stop wire form is nine bytes and carries
  only the generation.
- `HorizonRadioClientTrackSyncTest` verifies finite stop clears pause state and
  rejects stale/equal-generation sync transitions.
- The local-radio source audit confirms no active path constructs, sends, or
  registers `RadioStatePacket`, `RadioAudioStartPacket`, or
  `RadioAudioChunkPacket`.

## Verification

- `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.server.PlaylistManagerTest --tests com.horizonradio.core.server.PlaylistStateTest --tests com.horizonradio.network.TrackSyncPacketTest --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.core.protocol.HorizonRadioProtocolTest --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest --tests com.horizonradio.client.LocalRadioHandoffSourceAuditTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.audio.ClientRadioPlaybackTest --tests com.horizonradio.client.audio.AudioPlayerTest` — `BUILD SUCCESSFUL`.
- `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test` — `BUILD SUCCESSFUL` (16s).
- `git diff --check` — passed before final staging.

Pre-existing untracked plan/spec documents remain untouched.
