# Song Search More Results Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Song Search return at least 10 valid playable songs when YouTube has enough matching videos, while keeping the existing duration limit and bounded server work.

**Architecture:** Extend the existing `YouTubeService` InnerTube client with a small page parser and bounded continuation pagination. Keep filtering in `PlaylistManager`, where the configured duration limit is available, and cap the server-to-client result list at the first 10 valid entries. The existing `SearchResultsPacket`, GUI list, scrollbar, playlist, queue, and radio paths remain unchanged.

**Tech Stack:** Java 8-compatible production code, Java `CompletableFuture`, Gson 2.x, existing Forge networking, JUnit 4, Gradle.

## Global Constraints

- Search may inspect at most 3 InnerTube pages and 150 raw video candidates.
- The client receives at most 10 valid song results per search response.
- The configured duration predicate remains unchanged; the default maximum is 15 minutes.
- Playlist renderers, missing-duration results, duplicate video IDs, and over-limit videos are not sent as playable search results.
- Initial request/invalid JSON failures retain the existing empty-result behavior; a later-page failure preserves valid candidates already collected.
- No new dependency, codec, packet type, or GUI layout change is allowed.
- Java 8 production compatibility and existing Queue, playlist, chart, and Radio behavior must remain intact.

---

### Task 1: Parse InnerTube search pages and continuation tokens

**Files:**
- Modify: `src/main/java/com/horizonradio/server/YouTubeService.java`
- Create: `src/test/java/com/horizonradio/server/YouTubeServiceTest.java`
- Create: `src/test/resources/com/horizonradio/server/youtube-search-initial-with-continuation.json`
- Create: `src/test/resources/com/horizonradio/server/youtube-search-continuation.json`

**Interfaces:**
- Produces package-visible `YouTubeService.SearchPage` with `getResults()` and `getContinuation()`.
- Produces `YouTubeService.parseSearchPage(String)` for deterministic parser tests.
- Keeps `YouTubeService.parseResults(String)` as a compatibility wrapper returning the page's result list.
- A page contains only `SearchResult` video renderers and at most 50 results; playlist renderers and unrelated sections are ignored.

- [x] **Step 1: Write the failing parser tests.**

Add JUnit tests that load the two JSON resources and assert the exact parsed
video IDs, durations, and continuation token. Include a playlist renderer in
the initial fixture and assert it is absent. Also assert that
`parseResults(initialJson)` still returns the same video list as the page
parser.

```java
@Test
public void parsesVideosAndContinuationFromInitialSearchPage() throws IOException {
    String json = readResource("/com/horizonradio/server/youtube-search-initial-with-continuation.json");

    YouTubeService.SearchPage page = YouTubeService.parseSearchPage(json);

    assertEquals(Arrays.asList("video-1", "video-2"), videoIds(page.getResults()));
    assertEquals("token-page-2", page.getContinuation());
    assertEquals("2:30", page.getResults().get(0).getDuration());
}

@Test
public void parsesContinuationItemsAndIgnoresPlaylistItems() throws IOException {
    String json = readResource("/com/horizonradio/server/youtube-search-continuation.json");

    YouTubeService.SearchPage page = YouTubeService.parseSearchPage(json);

    assertEquals(Arrays.asList("video-3", "video-4"), videoIds(page.getResults()));
    assertEquals("token-page-3", page.getContinuation());
}
```

The fixtures must use the actual renderer shapes consumed by the production
parser: an initial `itemSectionRenderer` under
`contents.twoColumnSearchResultsRenderer...sectionListRenderer.contents`, and
continuation `itemSectionRenderer`/`continuationItemRenderer` entries under
`onResponseReceivedCommands[].appendContinuationItemsAction.continuationItems`.

Use these package-local test helpers for the snippets above; they keep the
tests deterministic and avoid any network call:

```java
private static String readResource(String path) throws IOException {
    InputStream stream = YouTubeServiceTest.class.getResourceAsStream(path);
    assertNotNull(stream);
    Reader reader = new InputStreamReader(stream, Charset.forName("UTF-8"));
    StringBuilder result = new StringBuilder();
    char[] buffer = new char[1024];
    int count;
    try {
        while ((count = reader.read(buffer)) != -1) {
            result.append(buffer, 0, count);
        }
    } finally {
        reader.close();
    }
    return result.toString();
}

private static List<String> videoIds(List<SearchResult> values) {
    List<String> ids = new ArrayList<String>();
    for (SearchResult value : values) {
        ids.add(value.getVideoId());
    }
    return ids;
}
```

Use `java.io.InputStream`, `java.io.InputStreamReader`, `java.io.Reader`, and
`java.nio.charset.Charset` imports for this helper; no new dependency may be
added.

- [x] **Step 2: Run the parser tests and verify the expected RED failure.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.server.YouTubeServiceTest
```

Expected: compilation or assertion failure because `SearchPage` and
`parseSearchPage` do not exist yet.

- [x] **Step 3: Implement the page parser.**

Refactor the current `parseResults` traversal into `parseSearchPage` without
changing the existing field extraction helpers. Read video sections from both
the initial response and continuation response shapes, extract the first
usable continuation token, deduplicate video IDs within a page, and stop at
50 page results. Add a package-visible immutable nested `SearchPage` whose
constructor defensively copies its list. `parseResults` delegates to
`parseSearchPage(responseBody).getResults()` so existing callers retain their
behavior. Malformed JSON returns an empty page and logs the existing warning.

- [x] **Step 4: Run the parser tests and verify GREEN.**

Run the same focused Gradle command. Expected: all parser tests pass, with no
change to existing `YouTubeParserTest` or full-suite behavior.

- [x] **Step 5: Commit the self-contained parser task if Git permits.** (Not performed: known Git identity/staging restriction.)

```bash
git add src/main/java/com/horizonradio/server/YouTubeService.java src/test/java/com/horizonradio/server/YouTubeServiceTest.java src/test/resources/com/horizonradio/server/youtube-search-initial-with-continuation.json src/test/resources/com/horizonradio/server/youtube-search-continuation.json
git commit -m "test: parse YouTube search continuation pages"
```

If the existing checkout still rejects staging or commits because of its
known Git index/author restrictions, preserve the files and record that fact
instead of changing Git configuration.

### Task 2: Add bounded continuation pagination to `YouTubeService`

**Files:**
- Modify: `src/main/java/com/horizonradio/server/YouTubeService.java`
- Modify: `src/test/java/com/horizonradio/server/YouTubeServiceTest.java`

**Interfaces:**
- `YouTubeService.search(String query)` remains the public asynchronous API.
- Add a package-visible `SearchPageRequester` seam and a package-visible
  constructor accepting it so tests can provide deterministic pages without
  network access. The public no-argument constructor keeps the existing HTTP
  requester.
- The requester accepts `(String query, String continuation)`; the initial
  call receives `null`, and later calls receive the parsed token.
- The package-visible `SearchPage` constructor accepts `(List<SearchResult>,
  String continuation)` so the fake requester can build deterministic pages.

- [x] **Step 1: Write failing pagination tests.**

Add a fake requester that records continuation arguments and returns
`SearchPage` objects. Cover:

```java
@Test
public void searchFollowsContinuationsDeduplicatesAndStopsAtThreePages() throws Exception {
    RecordingRequester requester = new RecordingRequester(
        page(results("duplicate", "valid-1"), "page-2"),
        page(results("duplicate", "valid-2"), "page-3"),
        page(results("valid-3"), "page-4"));

    List<SearchResult> results = new YouTubeService(requester).search("funk").get();

    assertEquals(Arrays.<String>asList(null, "page-2", "page-3"), requester.continuations());
    assertEquals(Arrays.asList("duplicate", "valid-1", "valid-2", "valid-3"), videoIds(results));
}

@Test
public void continuationFailureReturnsCandidatesFromEarlierPages() throws Exception {
    RecordingRequester requester = new RecordingRequester(
        page(results("valid-1"), "page-2"),
        new IOException("page unavailable"));

    List<SearchResult> results = new YouTubeService(requester).search("funk").get();

    assertEquals(Arrays.asList("valid-1"), videoIds(results));
}
```

The first test must also assert that no fourth page is requested and that the
raw candidate list is capped at 150 when the fake pages contain more entries.

The fake requester used by these tests has the following exact contract:

```java
private static YouTubeService.SearchPage page(List<SearchResult> results, String continuation) {
    return new YouTubeService.SearchPage(results, continuation);
}

private static List<SearchResult> results(String... ids) {
    List<SearchResult> values = new ArrayList<SearchResult>();
    for (String id : ids) {
        values.add(new SearchResult(id, id, "channel", "2:30", ""));
    }
    return values;
}

private static final class RecordingRequester implements YouTubeService.SearchPageRequester {

    private final List<Object> responses;
    private final List<String> continuations = new ArrayList<String>();

    private RecordingRequester(Object... responses) {
        this.responses = Arrays.asList(responses);
    }

    @Override
    public YouTubeService.SearchPage request(String query, String continuation) throws IOException {
        continuations.add(continuation);
        Object response = responses.get(continuations.size() - 1);
        if (response instanceof IOException) {
            throw (IOException) response;
        }
        return (YouTubeService.SearchPage) response;
    }

    private List<String> continuations() {
        return continuations;
    }
}
```

- [x] **Step 2: Run the pagination tests and verify RED.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.server.YouTubeServiceTest
```

Expected: compilation failure because the requester seam and paginated search
logic do not exist yet.

- [x] **Step 3: Implement the bounded pagination loop and HTTP continuation request.**

Add constants `MAX_SEARCH_PAGES = 3`, `MAX_RAW_SEARCH_RESULTS = 150`, and
retain 50 as the per-page parser limit. Refactor the current HTTP request into
`requestPage(query, continuation)`. The initial body contains the current
`query`; a continuation body contains the current client context plus the
`continuation` property. `search` loops at most three times, deduplicates by
video ID, stops at 150 raw candidates or a missing token, and returns the
collected candidates.

Catch an exception from the initial request using the existing empty-result
behavior. If a later request fails, log it and return the candidates already
collected. Never issue a fourth request. Keep `CompletableFuture` execution
off the server thread as it is today.

- [x] **Step 4: Run focused pagination tests and verify GREEN.**

Run `YouTubeServiceTest` and the existing parser tests. Confirm the fake
requester sees `null`, then the exact continuation tokens, and never sees a
fourth page.

- [x] **Step 5: Commit the pagination task if Git permits.** (Not performed: known Git identity/staging restriction.)

```bash
git add src/main/java/com/horizonradio/server/YouTubeService.java src/test/java/com/horizonradio/server/YouTubeServiceTest.java
git commit -m "feat: paginate YouTube song search"
```

Preserve the changes without Git configuration changes if the known checkout
restriction remains.

### Task 3: Filter and cap the server response at 10 valid songs

**Files:**
- Modify: `src/main/java/com/horizonradio/server/PlaylistManager.java`
- Modify: `src/test/java/com/horizonradio/server/PlaylistManagerTest.java`

**Interfaces:**
- Add package-visible static `PlaylistManager.buildSearchEntries(List<SearchResult>, long)` for deterministic filtering tests.
- `handleSearch` uses that helper and sends the resulting entries in the existing `SearchResultsPacket`.

- [x] **Step 1: Write failing filtering tests.**

Build a list with valid songs interleaved with unknown duration, exactly
15:00, and over-limit durations, plus more than 10 valid songs. Assert that
the helper preserves input order, excludes every invalid item, returns exactly
10 valid entries, and returns all valid entries when fewer than 10 exist.

```java
@Test
public void searchEntriesReturnFirstTenPlayableResults() {
    List<SearchResult> candidates = Arrays.asList(
        new SearchResult("valid-1", "Valid 1", "channel", "2:00", ""),
        new SearchResult("unknown", "Unknown", "channel", "", ""),
        new SearchResult("too-long", "Too long", "channel", "15:00", ""),
        new SearchResult("valid-2", "Valid 2", "channel", "3:00", ""),
        new SearchResult("valid-3", "Valid 3", "channel", "3:00", ""),
        new SearchResult("valid-4", "Valid 4", "channel", "3:00", ""),
        new SearchResult("valid-5", "Valid 5", "channel", "3:00", ""),
        new SearchResult("valid-6", "Valid 6", "channel", "3:00", ""),
        new SearchResult("valid-7", "Valid 7", "channel", "3:00", ""),
        new SearchResult("valid-8", "Valid 8", "channel", "3:00", ""),
        new SearchResult("valid-9", "Valid 9", "channel", "3:00", ""),
        new SearchResult("valid-10", "Valid 10", "channel", "3:00", ""),
        new SearchResult("valid-11", "Valid 11", "channel", "3:00", ""));

    List<SearchResultsPacket.Entry> entries = PlaylistManager.buildSearchEntries(candidates, 15L * 60L * 1000L);

    assertEquals(10, entries.size());
    assertEquals("valid-10", entries.get(9).getVideoId());
    assertFalse(containsVideoId(entries, "unknown"));
    assertFalse(containsVideoId(entries, "too-long"));
}
```

Use this test-only helper for the packet assertions; the companion test
constructs three valid candidates and asserts that all three are returned:

```java
private static boolean containsVideoId(List<SearchResultsPacket.Entry> entries, String videoId) {
    for (SearchResultsPacket.Entry entry : entries) {
        if (videoId.equals(entry.getVideoId())) {
            return true;
        }
    }
    return false;
}
```

- [x] **Step 2: Run the filtering tests and verify RED.**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.server.PlaylistManagerTest
```

Expected: compilation failure because `buildSearchEntries` does not exist.

- [x] **Step 3: Implement the minimal helper and wire `handleSearch` to it.**

Add a `MAX_SEARCH_RESULTS = 10` constant. The helper must use
`isSearchDurationAllowed` and return immediately when 10 entries have been
added. Replace the duplicated filtering loop in `handleSearch` with the helper;
do not alter chart filtering, import validation, direct PlayNow, or radio
behavior.

- [x] **Step 4: Run focused filtering and search tests and verify GREEN.**

Run `PlaylistManagerTest`, `YouTubeServiceTest`, and packet round-trip tests.
Expected: all pass; the existing GUI scroll behavior remains unchanged because
the client already scrolls any result list larger than six visible rows.

- [x] **Step 5: Commit the manager task if Git permits.** (Not performed: known Git identity/staging restriction.)

```bash
git add src/main/java/com/horizonradio/server/PlaylistManager.java src/test/java/com/horizonradio/server/PlaylistManagerTest.java
git commit -m "feat: return ten valid song search results"
```

Preserve the files and report the known Git restriction if staging/commit is
still unavailable.

### Task 4: Full verification and handoff

**Files:**
- Modify: `docs/superpowers/specs/2026-08-09-song-search-more-results-design.md` only if implementation evidence requires a precise correction.
- Modify: `docs/superpowers/plans/2026-08-09-song-search-more-results.md` to mark completed steps and record verification.

- [x] **Step 1: Run the focused regression groups.**

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --tests com.horizonradio.server.YouTubeServiceTest --tests com.horizonradio.server.PlaylistManagerTest --tests com.horizonradio.network.PacketRoundTripTest
```

- [x] **Step 2: Run the complete suite.**

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon
```

Expected: `BUILD SUCCESSFUL`, with zero failures, errors, or skipped tests.

- [x] **Step 3: Run final static checks.**

```bash
git diff --check
```

Confirm no build/dependency changes, no new packet IDs, no Queue/radio GUI changes,
and that the server still caps the response at 10 valid entries.

- [x] **Step 4: Update the plan ledger and hand off the result.**

Record the exact test command/results, preserve any pre-existing dirty changes,
and report the implementation files plus the Git staging/identity limitation.

#### Verification record

- Focused: `YouTubeServiceTest`, `PlaylistManagerTest`, and
  `PacketRoundTripTest` — `BUILD SUCCESSFUL`.
- Full: `env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --no-daemon --rerun-tasks` — 197 tests, 0 skipped, 0 failures, 0 errors.
- Static: `git diff --check` — clean.
