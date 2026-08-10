# Chart Region Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a server-authoritative, multilingual search for weekly charts by all ISO countries and Global, with Global shown by default and one seven-day cache per region.

**Architecture:** Add a dependency-free common `ChartRegion`/`ChartRegionCatalog` that converts ISO codes, locale country names, and explicit aliases into canonical regions. Extend the existing chart request/result payloads without adding packet IDs, key the server cache and refresh waiters by canonical region, and let the existing Charts UI send the selected region through the server. The YouTube chart parser remains the same weekly Top-50 parser; only its country parameter becomes region-aware.

**Tech Stack:** Java 8-compatible production code, Forge 1.7.10 `SimpleNetworkWrapper`, Gson, Java `Locale`/`Normalizer`, existing `CompletableFuture` services, JUnit 4, Gradle.

## Global Constraints

- Support `GLOBAL` and every ISO-3166-1 country code.
- Resolve country codes, locale-derived names, explicit aliases, case differences, accents, spaces, and hyphens.
- Reject unknown or ambiguous names without making a YouTube request.
- Keep the chart type `TRACKS` and period `WEEKLY`; do not add daily, monthly, genre, or artist charts.
- Load charts only on the server; clients never call YouTube directly.
- Cache results and timestamps independently per canonical region for seven days.
- Treat the legacy single-region German cache as `DE`, never as `GLOBAL`.
- Keep existing Queue, PlayNow, Refresh, duration filtering, and operator authorization behavior.
- Add no new packet IDs, dependencies, or protocol message types.
- Production code remains Java 8-compatible and existing search, radio, playlist, and chart tests must remain green.

## File and Responsibility Map

- `src/main/java/com/horizonradio/core/server/ChartRegion.java`: immutable canonical region data used on both sides.
- `src/main/java/com/horizonradio/core/server/ChartRegionCatalog.java`: ISO catalog, locale aliases, explicit aliases, normalization, and ambiguity handling.
- `src/main/java/com/horizonradio/network/packets/RequestChartsPacket.java`: bounded region code plus force-refresh flag for C2S chart requests.
- `src/main/java/com/horizonradio/network/packets/SearchResultsPacket.java`: optional chart region metadata for stale-response protection and title state.
- `src/main/java/com/horizonradio/client/HorizonRadioClient.java`: chart-region request state and cached result region.
- `src/main/java/com/horizonradio/client/ClientProxy.java`: forwards the chart region from the result packet to client state.
- `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`: Charts-tab search field, Global initialization, region title, and invalid-input message.
- `src/main/java/com/horizonradio/CommonProxy.java`: binds the region-aware server hook to `PlaylistManager`.
- `src/main/java/com/horizonradio/network/ServerMessageHandlers.java`: forwards the new request region to the server hook.
- `src/main/java/com/horizonradio/server/YouTubeService.java`: generic region-aware chart request and compatibility wrappers for German charts.
- `src/main/java/com/horizonradio/core/server/ChartCache.java`: per-region persistence, TTL, and legacy migration.
- `src/main/java/com/horizonradio/server/PlaylistManager.java`: per-region request/cache/refresh coordination and result delivery.
- `src/test/java/com/horizonradio/core/server/ChartRegionCatalogTest.java`: catalog and normalization behavior.
- `src/test/java/com/horizonradio/core/server/ChartCacheTest.java`: region isolation and legacy migration.
- `src/test/java/com/horizonradio/network/PacketRoundTripTest.java`: request/result packet payloads.
- `src/test/java/com/horizonradio/server/YouTubeServiceTest.java` and `src/test/java/com/horizonradio/core/server/YouTubeParserTest.java`: request-body and parser regressions.
- `src/test/java/com/horizonradio/server/PlaylistManagerTest.java`: per-region refresh/cache behavior.
- `src/test/java/com/horizonradio/client/GuiLayoutTest.java` and `src/test/java/com/horizonradio/client/RadioClientStateTest.java`: UI/transport/state regressions.
- `README.md`, `docs/ARCHITECTURE.md`, `docs/COMPATIBILITY.md`: user-facing and protocol documentation updates.

---

### Task 1: Build the shared multilingual chart-region catalog

**Files:**
- Create: `src/main/java/com/horizonradio/core/server/ChartRegion.java`
- Create: `src/main/java/com/horizonradio/core/server/ChartRegionCatalog.java`
- Create: `src/test/java/com/horizonradio/core/server/ChartRegionCatalogTest.java`

**Interfaces:**
- Produces `ChartRegion(String code, String apiCountryCode, String displayName)` with `getCode()`, `getApiCountryCode()`, and `getDisplayName()`.
- Produces `ChartRegionCatalog.GLOBAL_CODE`, `ChartRegionCatalog.global()`, `ChartRegionCatalog.byCode(String)`, `ChartRegionCatalog.resolve(String)`, and `ChartRegionCatalog.isAmbiguous(String)`.
- `resolve` returns a `ChartRegion` only when exactly one canonical region matches; it returns `null` for unknown or ambiguous input.
- `byCode` accepts only canonical `GLOBAL` or two-letter ISO codes and returns `null` for invalid codes.

- [ ] **Step 1: Write failing catalog tests.**

Add tests with these exact expectations:

```java
@Test
public void resolvesGlobalAndCommonAliases() {
    assertEquals("GLOBAL", ChartRegionCatalog.resolve("Weltweit").getCode());
    assertEquals("GLOBAL", ChartRegionCatalog.resolve(" worldwide ").getCode());
    assertEquals("DE", ChartRegionCatalog.resolve("Deutschland").getCode());
    assertEquals("DE", ChartRegionCatalog.resolve("gErMaNy").getCode());
    assertEquals("US", ChartRegionCatalog.resolve("Amerika").getCode());
    assertEquals("US", ChartRegionCatalog.resolve("United States of America").getCode());
}

@Test
public void resolvesIsoCodesAndLocaleNames() {
    assertEquals("FR", ChartRegionCatalog.resolve("fr").getCode());
    assertEquals("DE", ChartRegionCatalog.resolve("Allemagne").getCode());
    assertEquals("JP", ChartRegionCatalog.resolve("日本").getCode());
}

@Test
public void rejectsUnknownAndAmbiguousNames() {
    assertNull(ChartRegionCatalog.resolve("Atlantis"));
    assertTrue(ChartRegionCatalog.isAmbiguous("Congo"));
    assertNull(ChartRegionCatalog.resolve("Congo"));
}

@Test
public void normalizesAccentsSeparatorsAndCase() {
    assertEquals("CI", ChartRegionCatalog.resolve("CÔTE-D’IVOIRE").getCode());
    assertEquals("DE", ChartRegionCatalog.resolve("  deutsch-land ").getCode());
}
```

The catalog test may use `assertNotNull` before dereferencing locale-dependent
names. Register the explicit `Congo` aliases for both `CG` and `CD` so the
ambiguity assertion is deterministic even when the JDK locale data changes.

- [ ] **Step 2: Run the focused catalog test and verify the expected RED failure.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.core.server.ChartRegionCatalogTest
```

Expected: test compilation fails because `ChartRegion`, `ChartRegionCatalog`, and their methods do not exist.

- [ ] **Step 3: Implement the immutable region and catalog.**

Create `ChartRegion` with final fields and defensive validation for nonempty
code, API code, and display name. In `ChartRegionCatalog`:

1. Create one region for `GLOBAL` and every value from
   `Locale.getISOCountries()`.
2. Use the uppercase ISO code as the canonical code and the lower-case ISO
   code as the YouTube query code; store the endpoint's `GLOBAL` query value
   on the `GLOBAL` entry instead of treating `GLOBAL` as an ISO country.
3. Use the German display country as the UI display name when available, then
   English, then the ISO code.
4. Register each country's display name from every
   `Locale.getAvailableLocales()` value, plus the canonical code.
5. Add explicit aliases for `GLOBAL`, `DE`, `US`, and the deterministic
   `Congo` ambiguity.
6. Normalize with `Normalizer.normalize(value, Normalizer.Form.NFD)`, remove
   combining marks, retain Unicode letters/digits, lowercase with
   `Locale.ROOT`, and remove separators before lookup.
7. Store alias matches as sets of canonical codes. `resolve` returns a region
   only for a one-code set; `isAmbiguous` returns true for a set containing
   multiple codes.

Do not make network calls or use a third-party country database.

- [ ] **Step 4: Run catalog tests and the existing parser tests.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.core.server.ChartRegionCatalogTest --tests com.horizonradio.core.server.YouTubeParserTest
```

Expected: the new catalog tests and all existing parser tests pass.

- [ ] **Step 5: Commit the catalog task if Git permits.**

```bash
git add src/main/java/com/horizonradio/core/server/ChartRegion.java src/main/java/com/horizonradio/core/server/ChartRegionCatalog.java src/test/java/com/horizonradio/core/server/ChartRegionCatalogTest.java
git commit -m "feat: add multilingual chart region catalog"
```

If the known checkout still lacks Git author identity, do not change Git configuration; preserve the files and record that the commit was not created.

### Task 2: Extend chart request/result payloads and server hooks

**Files:**
- Modify: `src/main/java/com/horizonradio/network/packets/RequestChartsPacket.java`
- Modify: `src/main/java/com/horizonradio/network/packets/SearchResultsPacket.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/main/java/com/horizonradio/client/ClientProxy.java`
- Modify: `src/main/java/com/horizonradio/CommonProxy.java`
- Modify: `src/main/java/com/horizonradio/network/ServerMessageHandlers.java`
- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java` (compatibility bridge; region coordination is completed in Task 5)
- Modify: `src/test/java/com/horizonradio/network/PacketRoundTripTest.java`
- Modify: `src/test/java/com/horizonradio/client/RadioClientStateTest.java`

**Interfaces:**
- `RequestChartsPacket` exposes `getRegionCode()` and `isForceRefresh()` and accepts `RequestChartsPacket(String regionCode, boolean forceRefresh)`.
- Existing `RequestChartsPacket()` and `RequestChartsPacket(boolean)` constructors remain and default to `ChartRegionCatalog.GLOBAL_CODE`.
- `SearchResultsPacket` exposes `getChartRegionCode()` and adds `SearchResultsPacket(List<Entry>, boolean, String chartRegionCode)`; existing constructors use an empty chart region code.
- `ClientTransport` adds `sendChartsRequest(String regionCode, boolean forceRefresh)`; the existing boolean-only call delegates to Global.
- `ServerPacketHook.handleRequestCharts` accepts `(EntityPlayerMP player, String regionCode, boolean forceRefresh)`.
- `HorizonRadioClient.updateChartResults` accepts `(List<SearchResult> results, String regionCode)` and stores the currently cached region; the existing list-only helper remains as a Global compatibility wrapper.

- [ ] **Step 1: Add failing packet/state tests.**

Extend `PacketRoundTripTest` with:

```java
@Test
public void chartRequestRoundTripsRegionAndForceFlag() {
    RequestChartsPacket packet = roundTrip(
        new RequestChartsPacket("US", true),
        new RequestChartsPacket());

    assertEquals("US", packet.getRegionCode());
    assertTrue(packet.isForceRefresh());
}

@Test
public void chartResultsRoundTripRegionMetadata() {
    SearchResultsPacket packet = roundTrip(
        new SearchResultsPacket(Collections.<SearchResultsPacket.Entry>emptyList(), true, "GLOBAL"),
        new SearchResultsPacket());

    assertTrue(packet.isCharts());
    assertEquals("GLOBAL", packet.getChartRegionCode());
}
```

Add a client-state test that calls `HorizonRadioClient.updateChartResults(results, "US")` and asserts `getCachedChartRegionCode()` returns `US`, then calls `clearCache()` and asserts it returns `GLOBAL`.

- [ ] **Step 2: Run the packet/state tests and verify the expected RED failure.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.client.RadioClientStateTest
```

Expected: compilation fails because the region fields, constructors, and methods do not exist.

- [ ] **Step 3: Implement bounded packet and transport plumbing.**

Validate `regionCode` against a maximum of 16 characters and non-null input in
`RequestChartsPacket`; write/read it with `PacketBufferUtil` before the existing
force flag. Keep the packet registration ID unchanged. In
`SearchResultsPacket`, write/read a bounded chart region string after the
charts boolean and before the result count, and force non-chart packets to use
an empty region code. Update the Forge transport, no-op transport, client
static wrappers, server hook, and both handlers to pass the code through.

Keep the old source-level constructors and boolean-only methods delegating to
Global so existing call sites remain valid. In `ClientProxy.handleChartResults`,
pass `packet.getChartRegionCode()` to the client state. Track the requested
region in `HorizonRadioClient`; ignore a chart response whose region differs
from the currently pending region so an older async response cannot replace a
newer search.

- [ ] **Step 4: Run packet/state tests and the existing GUI transport tests.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.client.RadioClientStateTest --tests com.horizonradio.client.GuiLayoutTest
```

Expected: all pass, including the existing boolean-only chart request tests.

- [ ] **Step 5: Commit the packet task if Git permits.**

```bash
git add src/main/java/com/horizonradio/network/packets/RequestChartsPacket.java src/main/java/com/horizonradio/network/packets/SearchResultsPacket.java src/main/java/com/horizonradio/client/HorizonRadioClient.java src/main/java/com/horizonradio/client/ClientProxy.java src/main/java/com/horizonradio/network/ServerMessageHandlers.java src/test/java/com/horizonradio/network/PacketRoundTripTest.java src/test/java/com/horizonradio/client/RadioClientStateTest.java
git commit -m "feat: route chart region through network state"
```

Do not change Git configuration if the known author restriction remains.

### Task 3: Generalize the YouTube chart service by region

**Files:**
- Modify: `src/main/java/com/horizonradio/server/YouTubeService.java`
- Modify: `src/test/java/com/horizonradio/server/YouTubeServiceTest.java`
- Modify: `src/test/java/com/horizonradio/core/server/YouTubeParserTest.java`

**Interfaces:**
- Add `YouTubeService.fetchTopCharts(ChartRegion region)` returning `CompletableFuture<List<SearchResult>>`.
- Keep `fetchGermanTopCharts()` as a wrapper calling `fetchTopCharts(ChartRegionCatalog.byCode("DE"))`.
- Add package-visible `static String buildChartsRequestBody(ChartRegion region)` for deterministic body tests.
- Add `parseTopCharts(String responseBody)`; keep `parseGermanTopCharts(String)` delegating to it.

- [ ] **Step 1: Write failing generic chart-service tests.**

Add a body test in `YouTubeServiceTest` that parses the generated JSON with
Gson and checks the region-specific query values:

```java
@Test
public void buildsCountrySpecificChartRequestBody() {
    JsonObject body = new Gson().fromJson(
        YouTubeService.buildChartsRequestBody(ChartRegionCatalog.byCode("US")),
        JsonObject.class);

    assertEquals("FEmusic_analytics_charts_home", body.get("browseId").getAsString());
    assertTrue(body.get("query").getAsString().contains("chart_params_country_code=us"));
    assertTrue(body.getAsJsonObject("context").getAsJsonObject("client").get("gl").getAsString().equals("US"));
}

@Test
public void genericParserKeepsGermanCompatibilityWrapper() throws IOException {
    String json = readResource("/com/horizonradio/server/youtube-search-response.json");

    assertEquals(videoIds(YouTubeService.parseTopCharts(json)), videoIds(YouTubeService.parseGermanTopCharts(json)));
}
```

Add the same body assertion for `ChartRegionCatalog.global()` using the
catalog's explicit global API value. This pins the global request separately
from country requests.

- [ ] **Step 2: Run the service tests and verify the expected RED failure.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.server.YouTubeServiceTest --tests com.horizonradio.core.server.YouTubeParserTest
```

Expected: compilation fails because the generic service and body-builder methods do not exist.

- [ ] **Step 3: Implement the region-aware HTTP request.**

Refactor the current German-only request into `requestTopCharts(ChartRegion
region)`. Set the `WEB_MUSIC_ANALYTICS` client context, use the uppercase
country code for the `gl` client field, and use the catalog's lower-case
country code (or the explicit `GLOBAL` value) in the
`chart_params_country_code` query. Preserve the current POST URL, timeouts,
headers, `TRACKS`, `WEEKLY`, 50-entry rank limit, and empty-result exception
behavior. Make `parseTopCharts` contain the existing parser and delegate the
German method to it. Reject a null region before opening a connection.

- [ ] **Step 4: Run parser/service tests and verify GREEN.**

Run the command from Step 2. Expected: all generic body, compatibility, and existing parser tests pass without a network call.

- [ ] **Step 5: Commit the service task if Git permits.**

```bash
git add src/main/java/com/horizonradio/server/YouTubeService.java src/test/java/com/horizonradio/server/YouTubeServiceTest.java src/test/java/com/horizonradio/core/server/YouTubeParserTest.java
git commit -m "feat: fetch weekly charts by region"
```

### Task 4: Make the persistent chart cache region-aware

**Files:**
- Modify: `src/main/java/com/horizonradio/core/server/ChartCache.java`
- Modify: `src/test/java/com/horizonradio/core/server/ChartCacheTest.java`

**Interfaces:**
- Add `getResults(String regionCode)`, `hasResults(String regionCode)`, `isFresh(String regionCode)`, `store(String regionCode, List<SearchResult>)`, and `invalidate(String regionCode)`.
- Keep the existing no-region methods as `DE` compatibility wrappers for current callers/tests.

- [ ] **Step 1: Write failing region-cache and migration tests.**

Add a region-isolation test:

```java
@Test
public void storesRegionsIndependently() throws IOException {
    File directory = Files.createTempDirectory("horizonradio-chart-cache-regions").toFile();
    try {
        ChartCache cache = new ChartCache(directory);
        List<SearchResult> german = Arrays.asList(new SearchResult("de-id", "DE", "", "2:00", ""));
        List<SearchResult> global = Arrays.asList(new SearchResult("global-id", "Global", "", "2:00", ""));

        cache.store("DE", german);
        cache.store("GLOBAL", global);

        assertEquals(german, cache.getResults("DE"));
        assertEquals(global, cache.getResults("GLOBAL"));
    } finally {
        deleteRecursively(directory);
    }
}
```

Add a migration test that writes this exact old JSON shape to
`horizonradio-charts.json`, reloads `ChartCache`, and asserts the result is
available from `getResults("DE")` and absent from `getResults("GLOBAL")`:

```json
{"fetchedAt":1,"results":[{"videoId":"legacy","title":"Legacy","channel":"Channel","duration":"2:00","thumbnail":""}]}
```

- [ ] **Step 2: Run the cache tests and verify the expected RED failure.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.core.server.ChartCacheTest
```

Expected: compilation fails because region-aware cache methods do not exist.

- [ ] **Step 3: Implement per-region persistence and legacy loading.**

Replace the single `results`/`fetchedAt` storage with a map of region code to
an entry containing both fields. On load, read the new map when present; when
only the legacy fields exist, insert them under `DE`. Return defensive copies,
keep the seven-day freshness calculation per entry, and never store an empty
refresh result. The compatibility no-argument methods delegate to `DE`.

- [ ] **Step 4: Run cache tests and the existing playlist-state tests.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.core.server.ChartCacheTest --tests com.horizonradio.core.server.PlaylistStateTest
```

Expected: all cache persistence, migration, and existing state tests pass.

- [ ] **Step 5: Commit the cache task if Git permits.**

```bash
git add src/main/java/com/horizonradio/core/server/ChartCache.java src/test/java/com/horizonradio/core/server/ChartCacheTest.java
git commit -m "feat: cache charts per region"
```

### Task 5: Coordinate per-region refreshes in `PlaylistManager`

**Files:**
- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java`
- Modify: `src/test/java/com/horizonradio/server/PlaylistManagerTest.java`

**Interfaces:**
- Add `handleRequestCharts(EntityPlayerMP player, String regionCode, boolean forceRefresh)` and keep `handleRequestCharts(EntityPlayerMP player, boolean forceRefresh)` delegating to Global.
- Change `refreshChartsIfNeeded` to accept `ChartRegion` and call `YouTubeService.fetchTopCharts(region)`.
- Change `sendChartResults` and `finishChartRefresh` to accept the selected `ChartRegion`.
- Keep `processChartRequest` as a testable static helper, adding a region-aware overload while the old overload delegates to Global.

- [ ] **Step 1: Write failing manager tests for region isolation and messages.**

Extend `PlaylistManagerTest` so the region-aware request decision preserves the
existing cache/authorization rules and uses the selected display name:

```java
@Test
public void chartRequestMessagesUseSelectedRegion() {
    RecordingChartActions actions = new RecordingChartActions();

    PlaylistManager.processChartRequest(
        ChartRegionCatalog.byCode("US"), false, false, false, false, actions);

    assertEquals(
        list("chat:YELLOW:Loading Vereinigte Staaten YouTube Music Top 50...", "waiter", "refresh"),
        actions.events);
}

@Test
public void chartFetcherReceivesCanonicalRegions() throws Exception {
    RecordingChartYouTube service = new RecordingChartYouTube();
    service.fetchTopCharts(ChartRegionCatalog.global()).get();
    service.fetchTopCharts(ChartRegionCatalog.byCode("DE")).get();

    assertEquals(Arrays.asList("GLOBAL", "DE"), service.regionCodes());
}

private static final class RecordingChartYouTube extends YouTubeService {

    private final List<String> regionCodes = new ArrayList<String>();

    @Override
    public CompletableFuture<List<SearchResult>> fetchTopCharts(ChartRegion region) {
        regionCodes.add(region.getCode());
        return CompletableFuture.completedFuture(Collections.<SearchResult>emptyList());
    }

    private List<String> regionCodes() {
        return regionCodes;
    }
}
```

The recording service above is also the test double for manager integration;
it records every `fetchTopCharts` region and returns an already completed
future, so two distinct region requests can be asserted without network access.

- [ ] **Step 2: Run the manager tests and verify the expected RED failure.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.server.PlaylistManagerTest
```

Expected: compilation fails because the region-aware overload and per-region flow do not exist.

- [ ] **Step 3: Implement per-region request, waiter, and refresh state.**

Replace the single `chartRefreshWaiters` list and
`chartRefreshInProgress` boolean with a map of region code to waiters and a
set of region codes currently refreshing. In `handleRequestCharts`, resolve
the canonical code with `ChartRegionCatalog.byCode`; reject invalid codes with
a server chat message before touching the cache. Read the selected region's
cache, apply the existing `processChartRequest` logic, and bind its refresh
callback to that region.

On refresh, call `fetchTopCharts(region)`, extract durations exactly as before,
and enqueue `finishChartRefresh(region, completedResults)` on the server
thread. Store only nonempty results under that region, then send that region's
cache to all of its waiters and clear only that region's waiter/state entries.
Use the region display name in loading, refreshing, loaded, and failure chat
messages. A failed refresh with an existing cache sends that cache; a failed
refresh without one sends an empty chart response.

- [ ] **Step 4: Run manager, packet, and authorization regressions.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.server.PlaylistManagerTest --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.server.ChartRefreshAuthorizationTest
```

Expected: all per-region manager tests and existing chart authorization, queue, and packet tests pass.

- [ ] **Step 5: Commit the manager task if Git permits.**

```bash
git add src/main/java/com/horizonradio/server/PlaylistManager.java src/test/java/com/horizonradio/server/PlaylistManagerTest.java
git commit -m "feat: coordinate chart refreshes per region"
```

### Task 6: Add Charts-tab search and Global client behavior

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`
- Modify: `src/test/java/com/horizonradio/client/RadioClientStateTest.java`

**Interfaces:**
- The screen keeps `ChartRegion chartRegion` and a chart error/message string.
- Add package-visible screen accessors `getChartRegionCode()` and `getChartRegionDisplayName()` for deterministic tests.
- `HorizonRadioClient` exposes `getCachedChartRegionCode()` and region-aware `updateChartResults`.

- [ ] **Step 1: Write failing GUI tests for Global, search, empty reset, and invalid input.**

Extend `GuiLayoutTest` with these behavioral checks:

```java
@Test
public void chartsStartWithGlobalAndSearchUsesCanonicalRegion() {
    TestScreen screen = new TestScreen();
    screen.setScreenSize(300, 285);
    screen.initialize();

    assertEquals("GLOBAL", transport.chartRegionCode);
    screen.setSearchText("Germany");
    screen.clickSearchButton();

    assertEquals("DE", transport.chartRegionCode);
    assertEquals("DE", screen.getChartRegionCode());
}

@Test
public void emptyChartSearchReturnsToGlobal() {
    TestScreen screen = initializedScreen();
    screen.setSearchText("");
    screen.clickSearchButton();

    assertEquals("GLOBAL", transport.chartRegionCode);
}

@Test
public void unknownChartSearchKeepsCurrentResults() {
    TestScreen screen = initializedScreen();
    screen.updateChartResults(singleResult());
    screen.setSearchText("Atlantis");
    screen.clickSearchButton();

    assertEquals(1, screen.chartResultCount());
    assertEquals("GLOBAL", screen.getChartRegionCode());
}
```

Add `chartRegionCode` recording to the existing `RecordingTransport` and
package-visible test helpers for the screen's current region and result count.

- [ ] **Step 2: Run the GUI tests and verify the expected RED failure.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.client.GuiLayoutTest
```

Expected: compilation or assertion failures because Charts does not yet expose the field, region state, or canonical request.

- [ ] **Step 3: Implement the Charts-tab interaction.**

Make the existing search button and text field visible on `CHARTS_TAB`.
Initialize a new screen's region to Global and make `openCharts()` request
Global once per screen when no current Global result is cached; later tab
switches preserve the selected region. In `performSearch`, resolve the text
with `ChartRegionCatalog.resolve`. For a valid region, clear chart results and
scroll offset, set the current region/title, begin chart loading, and call
`HorizonRadioClient.sendChartsRequest(region.getCode(), false)`. For an empty
query, use Global. For unknown or ambiguous input, keep the current list and
region, stop no active request, and show a chart error message.

Change refresh to send the current region code. Draw the dynamic region title,
the search field on Charts, the chart error/empty state, and keep all existing
queue, bulk-add, PlayNow, and scrollbar hit testing unchanged. Apply incoming
chart results only when their region matches the pending region and update the
client cache with that region code.

- [ ] **Step 4: Run GUI/client tests and verify GREEN.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.RadioClientStateTest
```

Expected: all new Global/search/error tests and the existing Search, Playlist, Radio, and control-center tests pass.

- [ ] **Step 5: Commit the GUI task if Git permits.**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/main/java/com/horizonradio/client/HorizonRadioClient.java src/test/java/com/horizonradio/client/GuiLayoutTest.java src/test/java/com/horizonradio/client/RadioClientStateTest.java
git commit -m "feat: search charts by country in the GUI"
```

### Task 7: Update user and protocol documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/COMPATIBILITY.md`

- [ ] **Step 1: Update the documented chart behavior.**

Change references to a German-only chart tab to state that Global Weekly Top
50 is the default, all ISO countries can be searched with localized names and
aliases, and the server caches each region for seven days. Document that the
existing request/result packet IDs remain unchanged while the chart request
payload carries a canonical region code and chart results carry the selected
region metadata.

- [ ] **Step 2: Check documentation consistency.**

Run:

```bash
rg -n -i "German weekly|German YouTube|Top 50|RequestChartsPacket|SearchResultsPacket|chart" README.md docs/ARCHITECTURE.md docs/COMPATIBILITY.md
```

Verify that no documentation still claims the Charts tab can only load Germany and that the packet ID table remains unchanged.

- [ ] **Step 3: Commit documentation if Git permits.**

```bash
git add README.md docs/ARCHITECTURE.md docs/COMPATIBILITY.md
git commit -m "docs: describe regional chart search"
```

### Task 8: Full verification and handoff

**Files:**
- Modify: `docs/superpowers/plans/2026-08-09-chart-region-search.md` to mark completed steps and record verification.

- [ ] **Step 1: Run focused feature regressions.**

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.core.server.ChartRegionCatalogTest --tests com.horizonradio.core.server.ChartCacheTest --tests com.horizonradio.server.YouTubeServiceTest --tests com.horizonradio.server.PlaylistManagerTest --tests com.horizonradio.network.PacketRoundTripTest --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.RadioClientStateTest
```

Expected: `BUILD SUCCESSFUL` with zero failures and zero errors.

- [ ] **Step 2: Run the complete suite from the final tree.**

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, zero skipped tests, zero failures, and zero errors.

- [ ] **Step 3: Run final static and scope checks.**

```bash
git diff --check
git status --short
```

Confirm that no new packet registration or dependency file was added, the
chart request remains server-side, the legacy cache migration is present, and
pre-existing Radio/Queue changes remain untouched.

- [ ] **Step 4: Update the plan ledger and hand off.**

Mark the completed steps, record the exact test result, link the changed files,
and state whether commits were created. Do not alter Git author configuration;
if identity is still unavailable, preserve the working-tree changes without
committing them.
