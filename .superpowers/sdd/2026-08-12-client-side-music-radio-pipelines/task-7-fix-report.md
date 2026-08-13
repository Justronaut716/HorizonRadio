# Task 7 Fix Report — remove remaining server traffic paths

## Result

- Removed the active clientbound `ChartAddCompletionPacket` registration,
  handler, proxy callback, and `PlaylistManager` send path. The packet class
  remains only for its compatibility serializer round-trip test.
- Chart additions now complete their temporary client UI state locally after
  sending the existing queue mutation. Queue snapshot/delta synchronization,
  local chart discovery, and local chart metadata remain unchanged.
- `HorizonRadioConfig.load` now writes a missing `horizonradio.json` with all
  documented defaults, including the exact `"serverDebugChat":false` key.
  `save(File)` persists the complete configuration and preserves explicit
  `serverDebugChat` `true` and `false` values across reloads.
- Updated the README and compatibility JSON examples with `serverDebugChat`.
- Strengthened Task 7 source audits so no production manager, proxy, client
  lifecycle, or registry reference can reactivate chart-completion traffic.

## Verification

Passed focused Task 7 tests:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test \
  --tests com.horizonradio.server.PlaylistManagerTest \
  --tests com.horizonradio.network.PacketRoundTripTest \
  --tests com.horizonradio.core.server.PlaylistStateTest \
  --tests com.horizonradio.client.GuiLayoutTest \
  --tests com.horizonradio.HorizonRadioConfigTest
```

Passed the required full suite:

```bash
GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test
```

`git diff --check` passed. Pre-existing untracked plan and specification
documents were left untouched.
