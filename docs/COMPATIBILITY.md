# Compatibility and verification notes

## Target matrix

| Component | Target |
|---|---|
| Minecraft | 1.7.10 |
| Forge | 10.13.4.1614 |
| Mappings | MCP stable 12 |
| Build JDK | Java 25, required by the GTNH convention plugin |
| Ordinary Forge runtime | Java 8-compatible Forge 1.7.10 target; runtime smoke test pending |
| GTNH runtime | Java 17 or newer; pack smoke test pending |
| Gradle wrapper | 9.3.1 |
| Build system | GTNH convention build |
| Artifact | The same reobfuscated `horizonradio-<version>.jar` for both targets |
| Hard GTNH/GregTech dependency | None |

The Forge port has its own packet protocol. Install the same plain
reobfuscated `horizonradio-<version>.jar` on the server and every client. The
artifact has no hard GTNH or GregTech dependency.

## Embedded media backend

The same JAR carries the Java media runtime used for YouTube finite audio,
YouTube metadata, and direct radio decoding. The current decoder registry
supports MP3, ADTS/AAC, WAV/PCM, M4A/MP4 AAC, Ogg Vorbis, Ogg Opus, and
WebM/Opus. The direct radio path is acceptance-targeted for MP3, ADTS/AAC, Ogg
Vorbis, and Ogg Opus. Finite audio is cached as normalized WAV; live radio is
relayed as normalized 44.1 kHz stereo signed 16-bit little-endian PCM.

The server still requires outbound HTTP(S) access to YouTube, Radio Browser,
and selected station streams. Each client still needs a usable Java Sound line
for audible playback. No separately installed media program or native media
library is required. HLS/M3U8 was intentionally not added because the current
acceptance corpus has no concrete required HLS URL.

## Compatibility matrix and verification gates

| Environment | Requirement | Evidence/status |
|---|---|---|
| Standalone Forge 1.7.10 | Java 8-compatible Forge 10.13.4.1614 target, no GTNH mods, same JAR | Dedicated-server/client smoke test — pending/unverified; not run in the migration checkout. |
| GTNH | Java 17+, same JAR, GTNHLib/GregTech optional | Pinned GTNH pack smoke test — pending/unverified; no pack version recorded because the test was not run. |
| Build | Java 25, GTNH convention wrapper | PASS. A clean Java 25 Gradle 9.3.1 build completed Spotless, Forge/MCP setup, compilation, 76 tests, JAR assembly, and reobfuscation. |
| Artifact inspection | Successful Java 25 build output | PASS. The built JAR contains HorizonRadio metadata/classes and GUI assets, with no shaded GTNH, GregTech, or LWJGL classes. |

The Java 25 build and artifact inspection are verified. The standalone Forge
smoke gate and GTNH smoke gate still require runnable game environments. The
build result does not prove Java 8 or Java 17+ game-launch compatibility.

## Measured evidence

- Java 8 executable is present: `openjdk 1.8.0_502`.
- The current wrapper reports Gradle `9.3.1` when run with an isolated,
  writable `GRADLE_USER_HOME`.
- Java 25 wrapper verification: `./gradlew --version` downloaded Gradle
  `9.3.1` successfully with `JAVA_HOME=/home/benjamin/.jdks/ms-25.0.4` and
  `GRADLE_USER_HOME=/tmp/horizonradio-wrapper-gradle`.
- Clean Java 25 build: `VERSION=1.0.0 ./gradlew clean` followed by
  `VERSION=1.0.0 ./gradlew spotlessCheck test build` completed successfully.
- Test result: all 76 JUnit tests passed under Java 25.
- Artifact result: `build/libs/horizonradio-1.0.0.jar` (`SHA-256
  27e4a017f737c05abcd142e8e264b9d8e8ecf28eabb7387a8cf961f0b193104a`) has
  `mcmod.info` identifying `horizonradio`/`HorizonRadio`, targets Minecraft
  `1.7.10`, and declares no runtime dependencies. The JAR contains the
  protocol, optional-integration seam, and six GUI textures; no external
  `org/lwjgl`, `gregtech`, or `gtnhlib` packages are shaded into it.
- Direct Java-8 compilation and JUnit execution for the pure services/state
  suite: `OK (33 tests)`. This includes YouTube parser fixtures, audio process
  behavior, playlist state, chunk assembly, and client audio state without a
  sound device. This direct evidence predates the convention-build migration
  and does not verify the migrated Gradle build.
- Static common/server/network scope scan: PASS. No modern Java HTTP,
  modern text, client GUI, LWJGL, or Java Sound imports appear in common,
  server, or network code.
- Network source audit: the current source registers 36 messages, IDs 0-35,
  once from common code;
  IDs 0-3, 10-15, 17, 19-27, and 33 use `Side.SERVER`, IDs 4-9, 16, 18,
  28-32, 34, and 35 use `Side.CLIENT`, and S2C handlers forward only through
  the sided proxy. Chart request ID 21 carries a canonical region and
  force-refresh flag; chart result ID 4 carries chart-region metadata;
  `TrackSyncPacket` ID 35 carries only finite-track ID/timing, never audio
  bytes.
- `git diff --check`: PASS for the implementation changes.

## Chart region compatibility

The Charts tab starts empty and accepts ISO codes plus normalized country and
region names from the shared multilingual catalog. A successful country search
becomes the selected region for that client. Chart data is loaded only by the
server and cached independently for each canonical region for seven days. The
legacy single-region `horizonradio-charts.json` shape is loaded as `DE`, never
as Global, so an existing German cache cannot be shown as the wrong region.

## Task 6 audit results (2026-08-06)

- Source import audit: PASS. `rg -n 'import (com\.gtnewhorizons|gregtech|gtnhlib)' src/main/java` returned no matches (exit 1), and `rg -n 'import (cpw\.mods\.fml|net\.minecraft|org\.lwjgl|javax\.sound)' src/main/java/com/horizonradio/core` returned no matches (exit 1).
- Dependency declaration audit: PASS for runtime isolation. The exact scan of `gradle.properties`, `repositories.gradle`, `dependencies.gradle`, `build.gradle.kts`, and `settings.gradle.kts` found no GTNHLib or GregTech dependency. `dependencies.gradle` declares JUnit plus a test-runtime-only LWJGL dependency so Forge GUI classes can initialize during tests; no production runtime, shadow, compile-only, or dev-only dependency is declared.
- `gradle.properties` remains unchanged. `dependencies.gradle` adds only the test-runtime LWJGL bridge required by the existing GUI tests; it is not packaged in the mod JAR.
- `.github/workflows/build.yml` checks out the repository, provisions Temurin Java 25, enables Gradle caching, makes `gradlew` executable, runs `./gradlew clean --no-daemon` separately, and then runs `./gradlew test build --no-daemon` to avoid the fresh-cache clean/download race.

## Task 7 audit results (2026-08-09)

- Focused Java 25 verification passed with `RadioBrowserServiceTest`,
  `PacketRoundTripTest`, `RadioStreamBufferTest`, `RadioStreamServiceTest`,
  `RadioPlaybackStateTest`, `RadioClientStateTest`, and `GuiLayoutTest`.
- The complete Java 25 `./gradlew test --no-daemon` suite passed. Its XML
  reports contain 168 tests with 0 failures, 0 errors, and 0 skipped tests;
  this is newer evidence than the historical 76-test build record above.
- Source audit: 32 packet registrations use every discriminator from 0 through
  31 exactly once. IDs 25–27 are C2S and IDs 28–31 are S2C. No client-only
  imports appear in common, core, network, or server code, and radio
  client-facing models carry station UUID/name data rather than stream URLs.
- Dependency audit: no Gradle dependency declaration changed. Radio Browser uses
  server-side JDK HTTP APIs, while the embedded Java media backend handles
  finite conversion and the live PCM relay.
- `git diff --check`: PASS.

## Environment and runtime verification limits

The original Gradle failure was environmental, not a Java 25 or project
compatibility failure. The default `/home/benjamin/.gradle` cache is not
writable in this sandbox, and normal sandbox execution cannot create the
sockets Gradle uses for its lock service. The reliable setup is:

- Select Java 25 explicitly.
- Set `GRADLE_USER_HOME` to a writable directory such as a CI cache or
  `/tmp/horizonradio-wrapper-gradle`.
- Allow network access to `services.gradle.org`, Maven/Forge repositories, and
  Mojang launcher metadata, or pre-cache those dependencies in the CI image.
- Run the wrapper clean step separately from `test build` because this build
  uses parallel task execution and fresh Forge/Mojang downloads write under
  `build/`.

Using that setup, Gradle 9.3.1 downloaded successfully and the full project
build passed. This verifies the development/build path but does not prove Java
8 portable-runtime compatibility or game-launch behavior.

The following remain pending until the required runtime test environments are
available:

- `clean build`, `runServer`, and `runClient` on the Java-8 portable-runtime
  compatibility path;
- the separate Java 8 Forge 10.13.4.1614 dedicated-server/client launch smoke
  gate with no GTNHLib or GregTech installed;
- dedicated-server startup with S2C serialization exercised by a client;
- GUI/audio smoke flow against a loaded client world; and
- an actual GTNH pack test with the built JAR.

## Task 8 final smoke matrix (2026-08-06)

| Criterion | Exact measured environment/command | Result |
|---|---|---|
| Java 25 build | Java `25.0.4` at `/home/benjamin/.jdks/ms-25.0.4`; wrapper `9.3.1` with `GRADLE_USER_HOME=/tmp/horizonradio-wrapper-gradle`; `VERSION=1.0.0 ./gradlew clean` followed by `VERSION=1.0.0 ./gradlew spotlessCheck test build` | PASS. The separate clean completed, Spotless verification passed, and the build finished with 76 passing tests. |
| Artifact inspection/hash | `build/libs/horizonradio-1.0.0.jar` after the successful Java 25 build | PASS. `mcmod.info` identifies HorizonRadio/Minecraft 1.7.10; expected classes/assets are present; no external GTNHLib, GregTech, or LWJGL packages were found. SHA-256: `27e4a017f737c05abcd142e8e264b9d8e8ecf28eabb7387a8cf961f0b193104a`. |
| Standalone Forge Java 8 | Java `1.8.0_502` is installed; scan for Forge `1.7.10`/server artifacts found no local installation or launch harness | Pending/unverified; no Java 8 Forge server/client was launched. |
| GTNH runtime smoke test | Local PrismLauncher instance `GTNH 2.9 Beta-2` (Java 25 environment); `gtnhlib-0.11.24.jar` and `gregtech-5.09.54.20.jar` are present | Pending/unverified; a built HorizonRadio JAR is available, but no GTNH launch was performed. The target remains Java 17+. |
| Optional dependency absence/presence | GTNHLib/GregTech present in the GTNH instance; no standalone Forge installation was available to test physical absence | Pending/unverified for both startup comparisons. |
| Embedded media runtime | Java decoder libraries are packaged in the standalone artifact | Source/package audit is covered by Task 8; functional game audio smoke remains pending/unverified. |
| Functional smoke | No local standalone or project runtime harness; no client/server launch | Pending/unverified. |

The Gradle distribution download blocker is resolved by the writable-cache and
network setup above. Runtime blockers are the missing standalone Forge 1.7.10
installation/harness and the lack of a completed two-environment GTNHLib
absence/presence comparison. Required game-launch release criteria are not yet
evidenced; no release commit is authorized until the required runtime smoke
gates are evidenced.

## 1.0 breaking boundary

Release 1.0 changes the network channel to `horizonradio_1_0`, so pre-1.0
clients cannot connect after the channel change. Old configurations are not
automatically reinterpreted. Before upgrading, back up
`config/horizonradio.json`, `config/horizonradio-charts.json`, and the download
directory.

When the runtime test is performed, open the GUI only after
`Minecraft.getMinecraft().theWorld != null` and
`Minecraft.getMinecraft().thePlayer != null`. A title-screen or player-null
check is invalid evidence.

## YouTube InnerTube search

Search uses the undocumented endpoint
`https://www.youtube.com/youtubei/v1/search` with the WEB client version
`2.20231219.04.00`. The version and request body are retained from the active
source, but YouTube can retire or change them without notice.

The parser depends on nested `videoRenderer` data under
`twoColumnSearchResultsRenderer`, `sectionListRenderer`, and the expected text
and thumbnail fields. A response-shape change can make searches return an
empty list. HTTP and parser failures are contained and do not take down the
server.

## Embedded media and audio

The client-local downloaded WAV cache is named `<videoId>.wav` below
`horizonradio-audio` at the game root. The cache is session-scoped and is
deleted in full when the client starts and when it exits. While playing it is
pruned to the playback window: the current track, the last two finished
tracks, and the next two queued finite tracks. Finite YouTube audio
and metadata are handled by the embedded Java backend, while direct radio uses
the same Java decoder pipeline and a bounded PCM relay. No separate media
installation is part of the runtime.

Java Sound is client-local and depends on an available `Clip`/audio line. A
headless client or an unavailable `MASTER_GAIN` control is handled without
crashing the client, but it cannot produce audible playback. The server starts
finite tracks after the fixed three-second preparation window and does not
wait for client readiness; slow clients catch up locally after their download
finishes.

## Configuration and filesystem

The preserved JSON file is `config/horizonradio.json`:

```json
{
  "maxPlaylistSize": 50,
  "maxTrackDurationMinutes": 15,
  "downloadDir": "./horizonradio-downloads",
  "youtubeCookiesFromBrowser": "",
  "youtubeCookiesFile": "",
  "serverDebugChat": false
}
```

`maxPlaylistSize` is enforced server-side for all playlist additions, including
individual songs, chart batches, and playlist imports.
`maxTrackDurationMinutes` excludes search results that are at or above the
configured limit. The playlist is in memory and has no NBT/database persistence.
OneDrive paths can expose
cloud-sync locks or long-path/process timing issues; a local checkout is the
fallback if those issues appear.

The embedded resolver uses bounded YouTube InnerTube player and browse
requests. YouTube can change those response shapes or make them unreachable.
The legacy `youtubeCookiesFromBrowser` and `youtubeCookiesFile` fields remain
readable for configuration compatibility, but the embedded resolver does not
use them and they can be left empty.

## GTNH status

The source adds no registry entries, GregTech API calls, recipes, machines,
mixins, coremods, or access transformers. It is designed to coexist with GTNH
through that isolation. A versioned GTNH instance test is not claimed yet
because the local pack has not been launched with the built artifact. Record
the exact GTNH pack version and the server/client result here when that smoke
test becomes possible.
