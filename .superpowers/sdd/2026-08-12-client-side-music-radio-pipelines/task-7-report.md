# Task 7 Report — Remove server media traffic paths

## Result

Task 7 verified that the active network registry already retained only queue
mutation, queue snapshot/delta/resync, clock sync, finite/radio `TrackSync`,
and finite control traffic. No obsolete packet registration was reintroduced.

- Removed the remaining common/client proxy callbacks for search/chart results,
  finite audio chunks, NowPlaying progress, and radio-search results.
- Replaced the client radio-search cache's legacy packet DTO with local
  `RadioStation` records, so the active client lifecycle no longer depends on
  `RadioSearchResultsPacket`.
- Removed `RadioBrowserService`'s dependency on retired radio-packet limits;
  its equivalent publication bounds are now service-local.
- Retained obsolete packet classes and their `PacketRoundTripTest` serializers
  for compatibility coverage only; they remain unregistered and unreachable
  from the active client/server transport path.
- Added `serverDebugChat` to `horizonradio.json`, defaulting to `false`.
  `PlaylistManager` now logs its server diagnostics and only mirrors them to
  Minecraft chat when that explicit option is enabled.

## Source audits

- `PlaylistManagerTest` verifies `PlaylistManager`, `CommonProxy`, and
  `HorizonRadioNetwork` contain no server media-service ownership or retired
  search/import/result/audio/Ready relay registrations.
- `PacketRoundTripTest` verifies common handlers have no client-only Minecraft
  dependencies and that the active client lifecycle/proxy retains no obsolete
  media packet paths.

## Verification

- Focused Task 7 suite — PASS:

  `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.server.PlaylistManagerTest --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.core.server.PlaylistStateTest --tests com.horizonradio.client.GuiLayoutTest`

- Full suite — PASS:

  `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test`

- `git diff --check` — PASS.

Pre-existing untracked plan/spec documents remain untouched.
