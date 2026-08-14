# Playlist Discovery Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox ("- [ ]") syntax for tracking.

**Goal:** Add a separate client-local YouTube playlist discovery tab that displays the first 50 songs and hands only explicitly selected songs to the existing server queue.

**Architecture:** Keep the synchronized queue and imported playlist as separate state. Reuse ClientMediaService.importPlaylist(...), the existing chart-style result rows, and the existing compact multi-song queue packet, while adding a dedicated client cache, import generation, URL field, and pending-add tracker for playlist results.

**Tech Stack:** Java 8, Forge 1.7.10 GUI APIs, CompletableFuture, Gson-backed local media discovery, JUnit 4, Gradle, Spotless.

## Global Constraints

- The server must not receive a playlist URL or load playlist metadata.
- Only explicit individual or bulk add actions send video IDs and finite durations to the server.
- The imported playlist is discovery state and must remain separate from the synchronized server queue.
- Display and process at most the first 50 valid, de-duplicated playlist entries.
- The current Playlist queue tab becomes Queue; the new Playlists tab has its own URL field.
- Search-tab playlist URL behavior remains available for compatibility and continues to publish only into Search.
- Playlist discovery results remain in client memory for the active session and are not persisted in the client configuration file.
- Do not add server managers, packet handlers, packet registrations, or server-side playlist-import behavior.
- Run the focused client/media/UI tests and ./gradlew build before claiming completion.

---

## File map

- Modify src/main/java/com/horizonradio/client/HorizonRadioClient.java: own the imported-playlist cache, generation-guarded local import, and a playlist-specific queue handoff API while preserving existing chart APIs.
- Modify src/main/java/com/horizonradio/client/HorizonRadioScreen.java: add the fifth tab, dedicated URL field, playlist discovery rendering/state, queue-vs-playlist navigation, and separate pending-add tracking.
- Modify src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java: cover local playlist cache publication, stale/closed imports, the 50-entry cap, and playlist queue handoff.
- Modify src/test/java/com/horizonradio/client/GuiLayoutTest.java: cover the five-tab layout, separate Queue/Playlists behavior, local playlist actions, and pending state.
- Modify src/test/java/com/horizonradio/client/media/ClientMediaServiceTest.java to cover the first-50/duplicate parser fixture; do not move playlist parsing to the server.
- Create no server production files and do not modify network packet registration.

## Interfaces between tasks

Task 1 produces these client APIs for the screen:

~~~java
public static synchronized List<HorizonRadioScreen.SearchResult> getCachedPlaylistResults();
public static synchronized void sendPlaylistImport(String playlistUrl);
static synchronized void onPlaylistScreenClosed(HorizonRadioScreen screen);
~~~

Task 2 adds the queue handoff API:

~~~java
public static synchronized void sendPlaylistResultsToQueue(List<?> selections);
public static synchronized void sendPlaylistResultsToQueue(List<?> selections, boolean remove);
~~~

Task 2 also produces these screen pending-state methods:

~~~java
List<SearchResult> beginPlaylistAdd(List<SearchResult> results);
boolean isPlaylistAddPending(String videoId);
void completePlaylistAdds(List<String> videoIds);
~~~

Task 3 consumes those interfaces to connect the UI controls and does not introduce a second server transport.

---

### Task 1: Add generation-guarded client-local playlist discovery

**Files:**

- Modify src/main/java/com/horizonradio/client/HorizonRadioClient.java near the existing chart/search caches, discovery entry points, clearCache(), and screen-close hooks.
- Modify src/main/java/com/horizonradio/client/HorizonRadioScreen.java only for the minimal playlist-loading/result/error callbacks required by the client lifecycle; Task 3 supplies their complete rendering and input behavior.
- Test src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java near the existing local discovery and stale-generation tests.
- Test src/test/java/com/horizonradio/client/media/ClientMediaServiceTest.java for the direct 50-entry parser regression fixture.

**Interfaces:**

- Consumes existing ClientMediaService.importPlaylist(String), PlaylistImportService.isPlaylistUrl(String), ClientProxy.scheduleOnClientThread(...), and the active-screen lookup.
- Produces getCachedPlaylistResults(), sendPlaylistImport(String), and onPlaylistScreenClosed(HorizonRadioScreen) as defined above; an active screen receives beginPlaylistLoading(), updatePlaylistResults(...), and showPlaylistError(...) callbacks.

- [ ] Step 1: Write failing tests for cache publication and transport isolation.

Extend the existing HorizonRadioClientDiscoveryTest fake provider with a deferred playlist future and add tests shaped like:

~~~java
@Test
public void playlistImportPublishesLocallyWithoutUsingTransport() throws Exception {
    DeferredProvider provider = new DeferredProvider();
    HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
    HorizonRadioScreen screen = new HorizonRadioScreen();
    HorizonRadioScreen.setActiveScreen(screen);
    try {
        HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLlocal");
        provider.importPlaylist.complete(
            "{\"entries\":[{\"id\":\"song-one\",\"title\":\"One\",\"duration\":60}]}");

        assertEquals("song-one", HorizonRadioClient.getCachedPlaylistResults().get(0).videoId);
        assertEquals(0, transport.discoveryCallCount);
    } finally {
        HorizonRadioScreen.clearActiveScreen(screen);
    }
}

@Test
public void playlistImportDoesNotPublishAnOlderCompletion() {
    DeferredProvider provider = new DeferredProvider();
    HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));

    HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLold");
    HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLnew");
    provider.completePlaylist("new-song", "New");
    provider.completePlaylist("old-song", "Old");

    assertEquals("new-song", HorizonRadioClient.getCachedPlaylistResults().get(0).videoId);
}
~~~

Use the existing direct scheduler setup so completions are deterministic. Add a test that an invalid URL reports a local error and does not invoke the provider or transport.

- [ ] Step 2: Run the focused tests and verify they fail for the missing API/state.

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
~~~

Expected: compilation failure for the missing playlist cache/import entry point or failing assertions showing that no playlist result is published yet.

- [ ] Step 3: Implement the local cache and import lifecycle.

Add a copied list named CACHED_PLAYLIST_RESULTS, a playlistImportGeneration, and a playlistImportScreen to HorizonRadioClient. Implement sendPlaylistImport(String) with this exact behavior:

1. Trim and validate with PlaylistImportService.isPlaylistUrl(...); on failure call the active screen's showPlaylistError("Paste a valid YouTube playlist URL") and return.
2. Increment the generation, capture the current active screen, and call that screen's beginPlaylistLoading().
3. If clientMediaService is unavailable, publish a playlist error and leave all queue transport methods untouched.
4. Call clientMediaService.importPlaylist(url) and schedule completion on the client thread.
5. Under the HorizonRadioClient lock, ignore a completion unless both generation and originating screen still match. On success, copy at most 50 non-null results into the cache and call screen.updatePlaylistResults(...); on failure call screen.showPlaylistError("Playlist konnte nicht geladen werden").

Add getCachedPlaylistResults() returning a defensive copy. Clear this cache and invalidate its generation in clearCache(), but do not change favorite persistence. Add onPlaylistScreenClosed(...) to invalidate only an in-flight request belonging to that screen. Keep sendImportPlaylist(...) unchanged for Search compatibility.

Add the minimal screen callback methods beginPlaylistLoading(), updatePlaylistResults(...), and showPlaylistError(...) in the same slice so the client lifecycle has a concrete target. They may update private fields only; do not add tab navigation or mouse behavior until Task 3.

- [ ] Step 4: Add the first-50 and malformed-result regression test.

Build a provider JSON fixture with 52 entries, one duplicate ID, and one entry missing its ID/title. Complete the import and assert that the published list contains exactly the first 50 valid unique results in source order. This test must also assert that the queue recording transport remains untouched.

- [ ] Step 5: Run the focused tests and commit the independently testable discovery slice.

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest --tests com.horizonradio.client.media.ClientMediaServiceTest
~~~

Expected: PASS. Then commit:

~~~bash
git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java src/test/java/com/horizonradio/client/media/ClientMediaServiceTest.java
git commit -m "feat: add local playlist discovery state"
~~~

---

### Task 2: Reuse local duration resolution for playlist queue handoff

**Files:**

- Modify src/main/java/com/horizonradio/client/HorizonRadioClient.java in the existing chart-add resolution flow and transport-facing methods.
- Modify src/main/java/com/horizonradio/client/HorizonRadioScreen.java near chart pending-add helpers.
- Test src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java for order, duration resolution, and failure cleanup.
- Test src/test/java/com/horizonradio/client/GuiLayoutTest.java for independent chart/playlist pending state.

**Interfaces:**

- Consumes PlaylistSelection, resolveChartSelection(...), ClientMetadataCache.video(...), and the existing ClientTransport.sendAddChartSelections(...) packet seam.
- Produces sendPlaylistResultsToQueue(...) and the playlist pending methods defined above. The existing sendAddChartsToPlaylist(...) behavior and test API must remain unchanged.

- [ ] Step 1: Write failing tests for playlist handoff and pending cleanup.

Add tests with known durations and a deferred metadata lookup:

~~~java
@Test
public void playlistBulkAddPreservesOrderAndUsesOnlyQueueSelectionTransport() {
    HorizonRadioScreen.SearchResult first =
        new HorizonRadioScreen.SearchResult("pl-one", "One", "", "1:00", "");
    HorizonRadioScreen.SearchResult second =
        new HorizonRadioScreen.SearchResult("pl-two", "Two", "", "2:00", "");

    HorizonRadioClient.sendPlaylistResultsToQueue(Arrays.asList(first, second));

    assertEquals(Arrays.asList("pl-one|60000", "pl-two|120000"), transport.chartSelections);
    assertNull(transport.importPlaylistUrl);
}

@Test
public void failedPlaylistMetadataClearsOnlyPlaylistPendingState() {
    DeferredProvider provider = new DeferredProvider();
    HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
    HorizonRadioScreen screen = new HorizonRadioScreen();
    HorizonRadioScreen.setActiveScreen(screen);
    HorizonRadioScreen.SearchResult result =
        new HorizonRadioScreen.SearchResult("pl-missing", "Missing", "", "", "");
    try {
        assertEquals(Collections.singletonList(result), screen.beginPlaylistAdd(Collections.singletonList(result)));
        HorizonRadioClient.sendPlaylistResultsToQueue(Collections.singletonList(result));
        provider.deferVideo("pl-missing")
            .completeExceptionally(new IllegalStateException("missing"));

        assertFalse(screen.isPlaylistAddPending("pl-missing"));
        assertTrue(transport.chartSelections.isEmpty());
    } finally {
        HorizonRadioScreen.clearActiveScreen(screen);
    }
}
~~~

Also assert that a playlist result with a valid duration sends the same compact ID/duration pair as a chart result and that bulk order is unchanged.

- [ ] Step 2: Run the focused tests to confirm the playlist API and tracker are absent.

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest --tests com.horizonradio.client.GuiLayoutTest
~~~

Expected: compilation failure for sendPlaylistResultsToQueue(...) and the new pending methods.

- [ ] Step 3: Add separate playlist pending tracking to the screen.

Add pendingPlaylistAdds and implement:

~~~java
List<SearchResult> beginPlaylistAdd(List<SearchResult> results)
boolean isPlaylistAddPending(String videoId)
void completePlaylistAdds(List<String> videoIds)
~~~

Use the same filtering rules as beginChartAdd(...): skip null IDs, rows already in the queue, and IDs already pending; preserve source order. Do not share or clear pendingChartAdds.

- [ ] Step 4: Factor the client resolver behind two public entry points.

Keep sendAddChartsToPlaylist(...) as a compatibility wrapper. Add sendPlaylistResultsToQueue(...) and route both through one private resolver that receives an origin flag (CHARTS or PLAYLIST). The shared resolver must:

- accept SearchResult or already-resolved PlaylistSelection values;
- call the existing local metadata cache only when duration is absent/invalid;
- preserve the input order when constructing PlaylistSelection values;
- call transport.sendAddChartSelections(mapped, remove) only after local resolution completes;
- clear pending IDs through completeChartAdds(...) for chart-origin actions and completePlaylistAdds(...) for playlist-origin actions;
- never call an import or search transport operation.

When metadata resolves successfully, update the matching cached result duration if it belongs to Charts or imported Playlists. Keep the existing debug messages and chart tests compatible; only generalize the internal names where necessary.

- [ ] Step 5: Run tests and commit the queue-handoff slice.

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest --tests com.horizonradio.client.GuiLayoutTest
~~~

Expected: PASS, including all existing chart pending/add tests. Commit:

~~~bash
git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "feat: hand playlist results to the queue locally"
~~~

---

### Task 3: Add the Queue/Playlists navigation and dedicated local UI

**Files:**

- Modify src/main/java/com/horizonradio/client/HorizonRadioScreen.java across constants, initGui(), drawing, input dispatch, scrolling, progress helpers, and screen-close handling.
- Test src/test/java/com/horizonradio/client/GuiLayoutTest.java alongside existing tab, search, and queue interaction tests.

**Interfaces:**

- Consumes HorizonRadioClient.getCachedPlaylistResults(), sendPlaylistImport(...), onPlaylistScreenClosed(...), sendPlaylistResultsToQueue(...), and the pending methods from Tasks 1–2.
- Produces a visible five-tab UI with an independent playlist input and chart-style imported-result actions.

- [ ] Step 1: Write failing GUI tests for tab separation and local playlist actions.

Extend the existing TestScreen helper with a playlist-tab selector and playlist URL field accessor, then add tests like:

~~~java
@Test
public void queueAndPlaylistsAreDifferentTabsAndFields() {
    TestScreen screen = new TestScreen();
    screen.setScreenSize(300, 285);
    screen.initialize();

    screen.selectPlaylistDiscoveryTab();
    assertTrue(screen.isPlaylistDiscoveryTab());
    assertFalse(screen.isPlaylistTab());
    assertNotSame(screen.searchField(), screen.playlistUrlField());

    screen.selectPlaylistTab();
    assertTrue(screen.isPlaylistTab());
    assertFalse(screen.isPlaylistDiscoveryTab());
}

@Test
public void playlistRowQueueButtonUsesQueueTransportWithoutAddingToQueueLocally() {
    TestScreen screen = initializedPlaylistScreen();
    screen.click(280, 75);

    assertTrue(transport.addChartsRequest);
    assertEquals("playlist-song", screen.getPlaylistResultsSnapshot().get(0).videoId);
    assertTrue(screen.getPlaylistSnapshot().isEmpty());
}

@Test
public void playlistRowClickPlaysNowAndSwitchesToQueue() {
    TestScreen screen = initializedPlaylistScreen();
    screen.click(50, 75);

    assertEquals("playlist-song|120000", transport.playNowRequest);
    assertTrue(screen.isPlaylistTab());
}
~~~

Add a bulk-button test with two imported rows and assert that both IDs reach the existing compact queue recording transport in source order.

- [ ] Step 2: Run GUI tests to verify the new selectors/fields do not compile or pass yet.

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
~~~

Expected: compilation failure for the new helper methods and failing assertions for the missing fifth tab.

- [ ] Step 3: Add the fifth tab and preserve queue compatibility.

Change the top layout to compact five-tab positions that leave the chart refresh button at the right edge. Use labels Charts, Search, Queue, Playlists, and Radio. Introduce a distinct playlist-discovery tab constant and button ID. Keep isPlaylistTab() returning true for the queue tab so current tests and direct-play behavior remain compatible; add an explicit isPlaylistDiscoveryTab() helper for new tests.

Create playlistUrlField in initGui() with the same dimensions as the existing search field. Draw and focus only the field belonging to the active tab. The Search/Charts/Radio field keeps its existing behavior; the Playlists tab's search button invokes playlist import instead of normal Search/Charts/Radio dispatch. Disable that button while playlistLoading is true and re-enable it on success, failure, or invalid input.

- [ ] Step 4: Render and operate the imported playlist list.

Add the screen fields named in the spec (playlistResults, playlistScrollOffset, playlistLoading, progress/reveal timestamps, playlistError, and pendingPlaylistAdds). Initialize playlistResults from HorizonRadioClient.getCachedPlaylistResults().

Implement these screen methods:

~~~java
void beginPlaylistLoading();
void updatePlaylistResults(List<SearchResult> results);
void showPlaylistError(String message);
List<SearchResult> getPlaylistResultsSnapshot();
~~~

Render the imported list with the existing six-row result renderer. On empty state show Paste a YouTube playlist URL; while loading show Loading playlist...; for errors show the supplied local message. The result list must use a separate scroll offset and pending set from Charts and must never call updatePlaylist(...), sendAdd(...), or a queue-sync cache update merely because an import completed.

In actionPerformed, keyTyped, mouseClicked, mouse-wheel scrolling, and scrollbar dragging:

- route Playlists input to performPlaylistImport();
- handle single-row +/- through beginPlaylistAdd(...) and sendPlaylistResultsToQueue(...);
- handle the bulk button with the same all-added/all-pending toggle semantics as Charts;
- direct-play a clicked row through HorizonRadioClient.sendPlayNow(result) and switch to the Queue tab;
- keep Queue row dragging/removal logic on the Queue tab only.

Use PlaylistImportService.isPlaylistUrl(...) through the client entry point rather than a loose string check in the screen. In onGuiClosed(), call onPlaylistScreenClosed(this) so a completion from the closed screen cannot mutate a newly opened screen.

- [ ] Step 5: Add stale-close and cache-refresh UI coverage.

Add tests that update imported results through HorizonRadioClient, open a new screen, and observe the copied list. Add a close/reopen test proving an old completion cannot overwrite the reopened screen. Verify that Queue remains empty after import until a queue button is clicked.

- [ ] Step 6: Run focused UI/discovery tests and commit the complete UI slice.

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
~~~

Expected: PASS, with existing Search/Charts/Radio/Queue tests still green. Commit:

~~~bash
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "feat: add playlist discovery tab"
~~~

---

### Task 4: Integrate formatting and verify the full branch

**Files:**

- Modify only files reported by Spotless if formatting is required; do not modify server files for cleanup.
- Test existing client/media/UI test suites and the full Gradle build.

**Interfaces:**

- Consumes the completed local discovery, queue handoff, and UI slices.
- Produces a clean, fully tested UI-Improvement branch with no playlist import diff outside the client path and tests.

- [ ] Step 1: Run the complete focused client test set.

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.ClientFavoritesTest --tests com.horizonradio.client.FavoriteResultComposerTest --tests com.horizonradio.client.HorizonRadioClientConfigTest --tests com.horizonradio.client.media.ClientMediaServiceTest
~~~

Expected: PASS.

- [ ] Step 2: Inspect the diff for forbidden server/import paths.

Run:

~~~bash
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD
rg -n "<<<<<<<|=======|>>>>>>>" src || true
~~~

Expected: no whitespace errors or conflict markers; production changes are limited to the existing client/client-media paths and tests, with no new server playlist-import operation.

- [ ] Step 3: Run formatting and the full build.

Run:

~~~bash
./gradlew spotlessApply
./gradlew build
~~~

Expected: BUILD SUCCESSFUL, including Spotless checks, compilation, tests, and checkstyle. If spotlessApply changes files, inspect the diff and commit the formatting separately:

~~~bash
git add src/main/java/com/horizonradio/client src/test/java/com/horizonradio/client
git commit -m "style: format playlist discovery feature"
~~~

- [ ] Step 4: Confirm the final workspace and branch state.

Run:

~~~bash
git status --short --branch
git log --oneline --decorate -8
git diff --stat origin/main...HEAD
~~~

Expected: UI-Improvement is clean, all implementation commits are present, and the final diff contains the Favorites plus the client-local Playlist discovery feature only.
