# Shared Radio Tab Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

Goal: Add a server-authoritative Radio tab that searches Radio Browser stations, relays one validated live stream through FFmpeg to every client, and switches cleanly with the existing YouTube player.

Architecture: Keep the finite YouTube Clip/WAV path separate from a continuous PCM radio path. A server-side RadioBrowserService resolves station UUIDs and a RadioStreamService manages one published FFmpeg process plus one unpublished handover candidate. PlaylistManager remains the shared playback authority; clients use a bounded live buffer and SourceDataLine playback.

Tech Stack: Forge 1.7.10, Java-8-compatible production code, Gson, HttpURLConnection, ProcessBuilder/FFmpeg, Forge SimpleNetworkWrapper, Java Sound SourceDataLine, JUnit 4.13.2.

## Global Constraints

- Preserve the existing 300x285 Forge GUI geometry and six-row scrolling model.
- Display only the radio station name in Radio-tab result rows.
- Do not display or send country, language, codec, bitrate, tags, favicon, or stream URLs as GUI fields.
- Use Radio Browser station UUIDs as the only client station identifier; never accept a client-supplied stream URL.
- Limit radio search results to 50 and query input to 100 characters.
- Use hidebroken=true and Radio Browser mirror failover for directory requests.
- Use a 15-second radio candidate startup timeout.
- FFmpeg output is signed little-endian 16-bit PCM, 44,100 Hz, stereo.
- Keep every network byte array at or below the existing 30 KiB packet limit.
- Use a generation and sequence number for every radio stream; stale generations must be discarded.
- Add no client audio codec dependency; Java Sound receives PCM from the server relay.
- Radio playback has no pause, seek, previous, next, shuffle, or loop behavior.
- Stop Radio leaves the YouTube playlist unchanged and does not auto-resume a song.
- Any direct PlayNow action stops radio first, including search, chart, and Queue/Playlist row clicks.
- While radio is active, playlist add/import/remove/reorder/clear operations do not start YouTube playback or stop radio.
- Preserve the current working-tree Queue click implementation and its tests; do not reset unrelated user changes.
- Production source remains Java-8-runtime compatible for Forge 1.7.10.

---

### Task 1: Add the Radio Browser station model and directory service

Files:
- Create: src/main/java/com/horizonradio/core/model/RadioStation.java
- Create: src/main/java/com/horizonradio/server/RadioBrowserService.java
- Create: src/test/java/com/horizonradio/server/RadioBrowserServiceTest.java
- Create: src/test/resources/com/horizonradio/server/radio-browser-search-response.json

Interfaces:

- RadioStation is immutable and contains stationUuid, name, streamUrl, lastCheckOk, and hls.
- RadioBrowserService exposes:

    CompletableFuture<List<RadioStation>> search(String query);
    CompletableFuture<RadioStation> lookup(String stationUuid);
    CompletableFuture<Void> countClick(String stationUuid);
    static List<RadioStation> parseStations(String json);
    static URI buildSearchUri(URI base, String query, boolean popular);

- parseStations filters missing UUIDs/names/URLs, non-working entries, duplicate UUIDs, and entries after the 50-result limit.
- buildSearchUri uses json/stations/search, hidebroken=true, limit=50, and either name=query or order=votes&reverse=true for the initial popular request.

- [ ] Step 1: Add fixture data with valid, duplicate, broken, missing-field, and over-limit station records.
- [ ] Step 2: Write failing parser tests. Add readFixture to load the named classpath resource and containsUuid to scan stationUuid values; these helpers remain test-only.

    @Test
    public void parseStationsKeepsUniqueWorkingStationsWithResolvedUrls() throws IOException {
        List<RadioStation> stations = RadioBrowserService.parseStations(readFixture());
        assertEquals(50, stations.size());
        assertEquals("station-1", stations.get(0).getStationUuid());
        assertEquals("Example Radio", stations.get(0).getName());
        assertEquals("https://stream.example/radio", stations.get(0).getStreamUrl());
    }

    @Test
    public void parseStationsRejectsBrokenOrIncompleteEntries() throws IOException {
        List<RadioStation> stations = RadioBrowserService.parseStations(readFixture());
        assertFalse(containsUuid(stations, "broken"));
        assertFalse(containsUuid(stations, "missing-name"));
        assertFalse(containsUuid(stations, "missing-url"));
    }

- [ ] Step 3: Run the focused tests and verify the expected RED failure.

    env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.server.RadioBrowserServiceTest --no-daemon

Expected: compilation/test failure because the station model, parser, and service do not exist.

- [ ] Step 4: Implement bounded Radio Browser parsing and URI construction.

Use Gson and the existing HttpURLConnection pattern from YouTubeService, with 10-second connect and 15-second read timeouts. Use a descriptive User-Agent. Resolve all.api.radio-browser.info with InetAddress, reverse-resolve the returned addresses to API host names, randomize the resulting HTTPS base URLs, retry failed requests against the next mirror, and return an empty list after all mirrors fail. lookup queries the UUID endpoint and returns the first usable station or null. countClick calls the documented station click endpoint and never blocks the server thread.

- [ ] Step 5: Run focused tests and verify GREEN. Add tests for URL encoding, popular ordering, hidebroken, and limit parameters.
- [ ] Step 6: Commit the task.

    env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.server.RadioBrowserServiceTest --no-daemon
    git add src/main/java/com/horizonradio/core/model/RadioStation.java src/main/java/com/horizonradio/server/RadioBrowserService.java src/test/java/com/horizonradio/server/RadioBrowserServiceTest.java src/test/resources/com/horizonradio/server/radio-browser-search-response.json
    git commit -m "feat: add radio browser station service"

### Task 2: Add bounded radio network packets and Forge routing

Files:
- Create: src/main/java/com/horizonradio/network/packets/RadioSearchRequestPacket.java
- Create: src/main/java/com/horizonradio/network/packets/SelectRadioStationPacket.java
- Create: src/main/java/com/horizonradio/network/packets/StopRadioPacket.java
- Create: src/main/java/com/horizonradio/network/packets/RadioSearchResultsPacket.java
- Create: src/main/java/com/horizonradio/network/packets/RadioStatePacket.java
- Create: src/main/java/com/horizonradio/network/packets/RadioAudioStartPacket.java
- Create: src/main/java/com/horizonradio/network/packets/RadioAudioChunkPacket.java
- Modify: src/main/java/com/horizonradio/network/HorizonRadioNetwork.java
- Modify: src/main/java/com/horizonradio/network/ServerMessageHandlers.java
- Modify: src/main/java/com/horizonradio/network/ClientboundMessageHandlers.java
- Modify: src/test/java/com/horizonradio/network/PacketRoundTripTest.java

Interfaces:

- IDs 25, 26, and 27 are C2S RadioSearchRequestPacket(query), SelectRadioStationPacket(stationUuid), and StopRadioPacket.
- IDs 28, 29, 30, and 31 are S2C RadioSearchResultsPacket(entries), RadioStatePacket(active, generation, stationUuid, stationName, status), RadioAudioStartPacket(generation, firstSequence, sampleRate, channels, sampleSizeInBits, bigEndian), and RadioAudioChunkPacket(generation, sequence, data).
- Search result entries contain only stationUuid and name.
- Enforce bounds in constructors and fromBytes: query 100 characters, UUID 64 bytes, name 200 bytes, status 160 bytes, 50 result entries, and audio data PacketBufferUtil.MAX_BYTE_ARRAY_BYTES.
- Extend ServerPacketHook with handleRadioSearch, handleSelectRadio, and handleStopRadio.
- Add matching CommonProxy and ClientProxy message-forwarding methods.

- [ ] Step 1: Write round-trip tests for all seven packets, including generation/sequence and bounded byte arrays.
- [ ] Step 2: Run PacketRoundTripTest and verify RED because the packet classes are absent.
- [ ] Step 3: Implement codecs with PacketBufferUtil, copying mutable lists/arrays and rejecting invalid values before allocation.
- [ ] Step 4: Register IDs 25–31 exactly once. Server handlers authenticate the player and schedule through ServerThreadExecutor; client handlers call only HorizonRadio.proxy.
- [ ] Step 5: Add side-boundary and oversized-field assertions to PacketRoundTripTest.
- [ ] Step 6: Run the focused test and commit.

    env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.network.PacketRoundTripTest --no-daemon
    git add src/main/java/com/horizonradio/network/packets src/main/java/com/horizonradio/network/HorizonRadioNetwork.java src/main/java/com/horizonradio/network/ServerMessageHandlers.java src/main/java/com/horizonradio/network/ClientboundMessageHandlers.java src/test/java/com/horizonradio/network/PacketRoundTripTest.java
    git commit -m "feat: add radio network protocol"

### Task 3: Add the portable live-stream buffer and FFmpeg relay

Files:
- Create: src/main/java/com/horizonradio/core/audio/RadioStreamBuffer.java
- Create: src/main/java/com/horizonradio/server/RadioStreamService.java
- Create: src/test/java/com/horizonradio/core/audio/RadioStreamBufferTest.java
- Create: src/test/java/com/horizonradio/server/RadioStreamServiceTest.java

Interfaces:

- RadioStreamBuffer exposes:

    boolean begin(long generation, long firstSequence, int sampleRate, int channels, int sampleSizeInBits, boolean bigEndian);
    boolean accept(long generation, long sequence, byte[] data);
    byte[] poll();
    boolean isReady();
    void clear();

- It accepts only the current generation, rejects stale/duplicate sequences, caps pending data at three packets, and becomes ready after three packets.
- RadioStreamService exposes startCandidate(RadioStation, long, RadioStreamListener), promoteCandidate(long), stopGeneration(long), stopAll(), shutdown(), and static buildFfmpegCommand(String).
- RadioStreamListener callbacks are onReady(generation, station, firstSequence, data), onChunk(generation, sequence, data), and onFailure(generation, message).
- The service allows one published session plus one unpublished candidate during handover, reads at most 30 KiB per chunk, emits sequences from zero, and closes stale sessions.

- [ ] Step 1: Write buffer tests for generation, sequence, capacity, readiness, and the fixed 44100/2/16/little-endian format.
- [ ] Step 2: Run RadioStreamBufferTest and verify RED.
- [ ] Step 3: Implement the bounded buffer with copied byte arrays and complete reset on a new generation.
- [ ] Step 4: Write fake-process tests for the command, first-data callback, ordered chunks, process failure, and stale-session stop.
- [ ] Step 5: Run RadioStreamServiceTest and verify RED for the missing service.
- [ ] Step 6: Implement a daemon relay executor, stderr draining, 15-second first-data deadline, one published plus one candidate session, promoteCandidate handover, and complete process/stream cleanup on replacement, failure, explicit stop, and shutdown.
- [ ] Step 7: Run both focused tests and commit.

    env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.audio.RadioStreamBufferTest --tests com.horizonradio.server.RadioStreamServiceTest --no-daemon
    git add src/main/java/com/horizonradio/core/audio/RadioStreamBuffer.java src/main/java/com/horizonradio/server/RadioStreamService.java src/test/java/com/horizonradio/core/audio/RadioStreamBufferTest.java src/test/java/com/horizonradio/server/RadioStreamServiceTest.java
    git commit -m "feat: add bounded radio stream relay"

### Task 4: Integrate shared radio state with PlaylistManager

Files:
- Create: src/main/java/com/horizonradio/core/server/RadioPlaybackState.java
- Create: src/test/java/com/horizonradio/core/server/RadioPlaybackStateTest.java
- Modify: src/main/java/com/horizonradio/server/PlaylistManager.java
- Modify: src/main/java/com/horizonradio/CommonProxy.java
- Modify: src/main/java/com/horizonradio/server/ServerEvents.java
- Modify: src/test/java/com/horizonradio/server/PlaylistManagerTest.java

Interfaces:

- RadioPlaybackState is Java-only with IDLE, MUSIC, and RADIO modes, active station UUID/name, generation, and status.
- PlaylistManager adds handleRadioSearch(EntityPlayerMP, String), handleSelectRadio(EntityPlayerMP, String), handleStopRadio(EntityPlayerMP), syncRadioToPlayer(EntityPlayerMP), and isRadioActive().
- Add an injectable constructor accepting RadioBrowserService and RadioStreamService while retaining the existing constructor signature.
- CommonProxy constructs both services at server start, wires the new hooks, and shuts down the relay before the existing download service.

- [ ] Step 1: Write state tests for radio promotion, generation, station identity, and stop/reset.
- [ ] Step 2: Run RadioPlaybackStateTest and verify RED.
- [ ] Step 3: Implement radio search and selection. Search is asynchronous and sends only UUID/name entries to the requester. Selection looks up the UUID, starts a candidate, keeps the current source published until onReady, click-counts the station, calls promoteCandidate, then stops the old source, promotes radio state, broadcasts state/start, and relays the ready chunk.
- [ ] Step 4: Update PlayNow to stop/invalidate radio first. Guard seek, toggle, skip, previous, loop, and shuffle while radio is active. Ensure add/import/remove/reorder/clear do not call playNext, broadcast music stop packets, or delete the radio source while radio is active.
- [ ] Step 5: Broadcast radio state/chunks to every player, synchronize late joiners at the next live sequence, handle disconnects, and stop relay sessions during shutdown.
- [ ] Step 6: Add fake-service manager tests for ready-only promotion, failed-candidate preservation, PlayNow source switching, playlist edits during radio, and explicit radio stop.
- [ ] Step 7: Run focused server tests and commit.

    env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.server.RadioPlaybackStateTest --tests com.horizonradio.server.PlaylistManagerTest --no-daemon
    git add src/main/java/com/horizonradio/core/server/RadioPlaybackState.java src/main/java/com/horizonradio/server/PlaylistManager.java src/main/java/com/horizonradio/CommonProxy.java src/main/java/com/horizonradio/server/ServerEvents.java src/test/java/com/horizonradio/core/server/RadioPlaybackStateTest.java src/test/java/com/horizonradio/server/PlaylistManagerTest.java
    git commit -m "feat: integrate shared radio playback state"

### Task 5: Add client transport, state, and live Java Sound playback

Files:
- Modify: src/main/java/com/horizonradio/client/HorizonRadioClient.java
- Modify: src/main/java/com/horizonradio/client/ClientProxy.java
- Modify: src/main/java/com/horizonradio/client/audio/AudioPlayer.java
- Modify: src/main/java/com/horizonradio/CommonProxy.java
- Modify: src/test/java/com/horizonradio/client/GuiLayoutTest.java
- Create: src/test/java/com/horizonradio/client/RadioClientStateTest.java

Interfaces:

- Extend ClientTransport with sendRadioSearch(String), sendSelectRadio(String), and sendStopRadio().
- Add cached-radio methods updateRadioSearchResults, updateRadioState, handleRadioAudioStart, handleRadioAudioChunk, getCachedRadioResults, and isRadioActive.
- Add AudioPlayer methods startRadio(RadioAudioStartPacket), receiveRadioChunk(RadioAudioChunkPacket), and stopRadio().
- ClientProxy schedules cache/audio mutations on the Minecraft thread; AudioPlayer performs SourceDataLine work on its existing daemon audio executor.

- [ ] Step 1: Write failing state tests for radio reset and transport delegation.

    @Test
    public void radioStateIsClearedOnDisconnectCacheReset() {
        HorizonRadioClient.updateRadioState(new RadioStatePacket(true, 3L, "uuid", "Station", "LIVE"));
        HorizonRadioClient.clearCache();
        assertFalse(HorizonRadioClient.isRadioActive());
        assertTrue(HorizonRadioClient.getCachedRadioResults().isEmpty());
    }

- [ ] Step 2: Run RadioClientStateTest and GuiLayoutTest and verify RED.
- [ ] Step 3: Implement Forge/no-op transports, cached results/state, client-thread packet handlers, and cache reset without changing volume.
- [ ] Step 4: Add live AudioPlayer mode. Close finite Clip before a new generation; use RadioStreamBuffer, three-packet startup buffering, 44100/16-bit/stereo/little-endian SourceDataLine, volume control, and executor cleanup.
- [ ] Step 5: Run focused client tests and commit.

    env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.GuiLayoutTest --no-daemon
    git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/main/java/com/horizonradio/client/ClientProxy.java src/main/java/com/horizonradio/client/audio/AudioPlayer.java src/main/java/com/horizonradio/CommonProxy.java src/test/java/com/horizonradio/client/GuiLayoutTest.java src/test/java/com/horizonradio/client/RadioClientStateTest.java
    git commit -m "feat: add client radio stream playback"

### Task 6: Add the Radio tab and radio-specific control center

Files:
- Modify: src/main/java/com/horizonradio/client/HorizonRadioScreen.java
- Modify: src/test/java/com/horizonradio/client/GuiLayoutTest.java

Interfaces:

- Add HorizonRadioScreen.RadioStationResult with stationUuid and name.
- Add RADIO_TAB = 3, a Radio tab button at the next available tab position, radio scroll state, and radio loading/state fields.
- Add updateRadioResults(List<RadioStationResult>), openRadio(), and drawRadioTab(left, top, mouseX, mouseY).
- Add Stop Radio and Change Station actions; hide the five music control buttons during radio and keep the volume slider visible.

- [ ] Step 1: Write failing GUI tests for tab selection, search dispatch, station-row UUID selection, active-row rendering, scrolling, empty state, and radio controls. Add a singleRadioStation helper returning a list containing RadioStationResult("radio-uuid", "Station").

    @Test
    public void radioRowSelectionSendsUuidOnly() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.selectRadioTab();
        screen.updateRadioResults(singleRadioStation());
        screen.click(50, 75);
        assertEquals("radio-uuid", transport.selectedRadioUuid);
        assertNull(transport.playNowRequest);
    }

    @Test
    public void radioModeHidesMusicControlsAndStopSendsRadioStop() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        HorizonRadioClient.updateRadioState(new RadioStatePacket(true, 1L, "radio-uuid", "Station", "LIVE"));
        screen.initialize();
        assertFalse(screen.musicControlsVisible());
        screen.invokeStopRadioAction();
        assertTrue(transport.stopRadio);
    }

- [ ] Step 2: Run GuiLayoutTest and verify RED because the Radio tab does not exist.
- [ ] Step 3: Implement the tab, reuse the existing search field/button, dispatch radio searches, load popular stations on first open, draw only names, support six-row scrolling, mark the active station with green/LIVE, show loading/empty states, and send station UUID on any row click.
- [ ] Step 4: Render station/status in now-playing, replace progress with LIVE, hide music controls, draw Stop Radio and Change Station, route both actions, and restore music controls when source returns to music.
- [ ] Step 5: Extend direct search/chart/queue PlayNow tests so radio-active GUI still sends normal PlayNow and does not issue an extra client-side radio stop. Assert no queue controls appear in Radio.
- [ ] Step 6: Run GUI tests and commit.

    env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.client.GuiLayoutTest --no-daemon
    git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
    git commit -m "feat: add shared radio tab UI"

### Task 7: Update architecture documentation and run complete verification

Files:
- Modify: README.md
- Modify: docs/ARCHITECTURE.md
- Modify: docs/COMPATIBILITY.md if the verified test count, dependency audit, or runtime notes change; otherwise leave it untouched
- Test: all existing src/test/java tests

- [ ] Step 1: Document the Radio feature, server FFmpeg relay, Radio Browser dependency, shared source switching, live/non-seekable controls, packet IDs 25–31, and client PCM adapter.
- [ ] Step 2: Run focused new test groups.

    env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.server.RadioBrowserServiceTest --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.core.audio.RadioStreamBufferTest --tests com.horizonradio.server.RadioStreamServiceTest --tests com.horizonradio.core.server.RadioPlaybackStateTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.GuiLayoutTest --no-daemon

Expected: BUILD SUCCESSFUL and all focused tests passing.

- [ ] Step 3: Run the complete suite and formatting check.

    env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon
    git diff --check

Expected: BUILD SUCCESSFUL, zero test failures/errors, and no git diff --check output.

- [ ] Step 4: Inspect the final diff. Confirm common/server classes have no client-only imports, no new dependency was added, packet registrations are unique, existing Queue click behavior remains, and no raw station URL is sent to clients.
- [ ] Step 5: Commit documentation and verification changes.

    git add README.md docs/ARCHITECTURE.md docs/COMPATIBILITY.md
    git commit -m "docs: describe shared radio playback"

If Git author identity is still unset, leave changes staged and report the exact blocker without changing Git configuration.

## Plan self-review

- Spec coverage: Radio Browser search/filtering is Task 1; protocol/bounds Task 2; PCM relay/handover Task 3; shared switching/playlist preservation Task 4; client live audio Task 5; Radio tab/control center Task 6; documentation/full verification Task 7.
- Placeholder scan: No TODO, TBD, vague “appropriate handling”, or unspecified follow-up steps are used. Every task names files, interfaces, tests, commands, and expected results.
- Type consistency: RadioStation from Task 1 is passed to RadioStreamService in Task 3 and PlaylistManager in Task 4. RadioStreamBuffer from Task 3 is consumed by AudioPlayer in Task 5. Packet types from Task 2 are the types consumed by Tasks 4–6.
- Working-tree safety: Existing Queue implementation/spec/plan changes remain preserved; no reset or broad refactor is planned.
