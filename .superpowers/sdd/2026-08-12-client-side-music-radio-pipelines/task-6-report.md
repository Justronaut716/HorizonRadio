# Task 6 Report — Local finite and live-radio playback

## Result

Task 6 was substantially present in the Task 5 review-fix commits. This task
audited that implementation and completed the remaining client-local behavior
without reverting or changing the packet-free radio handoff.

- `ClientRadioPlayback` now rejects unavailable stations before opening a
  session and reports direct stream startup/failure status through a local
  callback. The proxy schedules those callbacks onto the Minecraft client
  thread, where stale generations are ignored and local presentation receives
  the station label or failure status.
- Radio TrackSync retains its source/generation identity through local
  presentation. Radio ignores finite pause/resume and outgoing seek/toggle
  controls.
- `PlaybackClock.finiteTrackPositionMs` provides the source-independent
  server-clock conversion used by a client tick to render finite-track title
  and progress from locally resolved metadata. No progress packet is emitted.
- The established Task 5 packet-free PCM names (`beginLocalRadioPcm` and
  `bufferLocalRadioPcm`) remain intact because their compatibility source audit
  guards that boundary. Legacy packet serializers remain compatibility-only;
  the active client radio path constructs no relay packet.

## Regression coverage

- unavailable station records never open a local stream;
- stale and current session failures are distinguished by generation;
- local station labels and failures update only matching radio presentation;
- finite controls are ignored for radio;
- local metadata plus server timing drive finite title/progress;
- finite position calculation is usable independently of Java Sound.

## Verification

- Focused Task 6/client-media suite — PASS:

  `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.client.audio.ClientRadioPlaybackTest --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.audio.AudioPlayerTest --tests com.horizonradio.client.audio.PlaybackClockTest --tests com.horizonradio.client.LocalRadioHandoffSourceAuditTest --tests com.horizonradio.server.media.RadioInputSessionTest --tests com.horizonradio.server.media.RadioJitterBufferTest`

- Full suite — PASS:

  `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test`

- `git diff --check` — PASS.

Unrelated untracked plan/spec documents were left untouched.
