# Task 4 Report: Revisioned Client Queue and Local Discovery

## Delivered

- Added `ClientQueueState`, which atomically applies only contiguous playlist deltas and requires a fresh snapshot after a gap or malformed operation.
- Wired client playlist snapshots/deltas through `ClientProxy`, sends one resync request per missing snapshot, and keeps GUI queue state server-authoritative.
- Initialized Task 3's `ClientMediaService` and `ClientMetadataCache` in client pre-initialization.
- Moved production search, charts, imports, and radio search to local futures with client-thread callbacks and generation checks.
- Changed the production add/play-now/chart packet path to use IDs and finite durations, retaining legacy transport overloads only for existing tests.
- Added source-aware GUI queue entries with lazy local metadata and a bounded source-ID fallback.

## Files

- Created: `src/main/java/com/horizonradio/core/client/ClientQueueState.java`
- Created: `src/test/java/com/horizonradio/core/client/ClientQueueStateTest.java`
- Modified: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modified: `src/main/java/com/horizonradio/client/ClientProxy.java`
- Modified: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`

## Verification

1. `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.client.ClientQueueStateTest --tests com.horizonradio.client.GuiLayoutTest`
   - Initial red run: failed as expected because `ClientQueueState` was absent.
   - Green run: passed.
2. `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.client.ClientQueueStateTest --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.HorizonRadioClientConfigTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest --tests com.horizonradio.client.media.ClientMediaServiceTest --tests com.horizonradio.client.media.ClientMetadataCacheTest`
   - Final run: passed, 94 tests.
3. `git diff --check`
   - Passed with no output.

## Commit

- `feat: apply revisioned client-side queue state`

## Concerns

- One broader run transiently failed `RadioClientStateTest.liveRadioPreservesPartialFramesAcrossPacketBoundaries`; the isolated rerun and final 94-test run passed. Task 4 does not modify that audio-frame path.
- Legacy string-based transport methods remain as test seams. Production `ForgeClientTransport` converts them to ID/duration packets; local discovery operations do not use network transport after client initialization.

## Fix Round 1

### Review findings addressed

- Removed all discovery transport fallbacks from public search, chart, import, and radio-search entrypoints. When local discovery is unavailable, those entrypoints update only local empty/error UI state.
- Replaced separate search/import counters with one search-tab discovery generation, including imports and cache reset invalidation.
- Removed legacy title/duration calls from every production `HorizonRadioScreen` add, play-now, and chart action. GUI duration strings are parsed before ID/duration selection transport.
- Added `HorizonRadioClientDiscoveryTest` for no-discovery-transport behavior and deterministic stale search-after-import completion.
- Updated client transport tests to assert only server-bound playback/queue controls use the transport.

### Fix verification

`GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.client.ClientQueueStateTest --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.HorizonRadioClientConfigTest --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.HorizonRadioClientTrackSyncTest --tests com.horizonradio.client.media.ClientMediaServiceTest --tests com.horizonradio.client.media.ClientMetadataCacheTest`

- Passed, 97 tests.
- `git diff --check` passed with no output.

### Fix commit

- `fix: keep client discovery local`
