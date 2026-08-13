# Chart Duration Prefetch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve missing chart durations on the client before publishing the chart list to the GUI, while keeping the server completely uninvolved.

**Architecture:** `HorizonRadioClient.sendChartsRequest` will keep the fetched chart list private while it resolves only entries without a valid duration through the existing `ClientMetadataCache`. Each resolution returns the original chart metadata with either the resolved duration or `--:--`; the final list is published once, in its original order, only if its chart generation is still current. Chart actions then reuse the populated cache and duration field.

**Tech Stack:** Java 8, `CompletableFuture`, existing `ClientMetadataCache`, JUnit 4, Gradle, Spotless.

## Global Constraints

- The server receives no additional chart-metadata packets or requests.
- Entries with an existing valid duration are not resolved again.
- The chart list is published only after all missing-duration resolutions complete.
- A failed individual lookup produces `--:--` and does not remove the chart.
- Existing chart order and count are preserved.
- Existing chart-generation guards prevent stale requests from updating the GUI.
- The normal YouTube search path and server playlist protocol remain unchanged.

## File Map

- Modify `src/main/java/com/horizonradio/client/HorizonRadioClient.java`: orchestrate chart-duration prefetching, merge metadata results, and publish the final chart list.
- Modify `src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java`: add deferred chart metadata fixtures and regression tests for waiting, failures, cache reuse, ordering, and stale generations.

### Task 1: Add failing client chart-prefetch tests

**Files:**
- Modify: `src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java`

**Interfaces:**
- Consumes the existing `HorizonRadioClient.sendChartsRequest(String, boolean)` entry point and `HorizonRadioScreen` chart state.
- Produces executable regression tests that define the expected prefetch behavior before production code changes.

- [ ] **Step 1: Extend the deferred provider with chart results and metadata futures**

Add a configurable `chartResults` field to `DeferredProvider`, return it from `fetchCharts`, and add a reflection helper for the screen's private `chartResults` list. Keep the existing search/import behavior unchanged.

- [ ] **Step 2: Write the failing test for waiting and ordering**

Add a test with one chart missing a duration and one chart already containing `2:00`. Defer the missing video's metadata and assert that the active screen still has no published chart results until the metadata future completes; then assert both entries, their original order, and the resolved duration.

The test shape should be:

```java
@Test
public void chartsPublishOnlyAfterMissingDurationsAreResolved() {
    DeferredProvider provider = new DeferredProvider();
    provider.chartResults = Arrays.asList(
        new SearchResult("missing-duration", "First", "", "", ""),
        new SearchResult("known-duration", "Second", "", "2:00", ""));
    CompletableFuture<String> metadata = provider.deferVideo("missing-duration");
    HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
    HorizonRadioScreen screen = new HorizonRadioScreen();
    HorizonRadioScreen.setActiveScreen(screen);
    try {
        HorizonRadioClient.sendChartsRequest("DE", false);

        assertTrue(chartResults(screen).isEmpty());

        metadata.complete("{\"id\":\"missing-duration\",\"title\":\"First\",\"duration\":90}");

        assertEquals(Arrays.asList("missing-duration", "known-duration"), chartVideoIds(screen));
        assertEquals("1:30", chartResults(screen).get(0).duration);
        assertEquals("2:00", chartResults(screen).get(1).duration);
    } finally {
        HorizonRadioScreen.clearActiveScreen(screen);
    }
}
```

- [ ] **Step 3: Run the focused test and verify it fails**

Run:

```bash
./gradlew test --no-daemon --offline --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest.chartsPublishOnlyAfterMissingDurationsAreResolved
```

Expected: FAIL because the current chart-fetch callback publishes the raw list immediately and leaves the missing duration empty.

- [ ] **Step 4: Add failure, cache-reuse, and stale-generation tests**

Add tests that:

1. Complete one missing metadata future exceptionally and assert the chart remains with `--:--` while other charts remain present.
2. Load a missing-duration chart, complete its metadata, then call `sendAddChartsToPlaylist` with the published screen result and assert the transport receives the resolved duration without increasing the provider lookup count.
3. Start an older deferred chart request, start a newer request, complete the older request last, and assert the newer chart list remains displayed.

- [ ] **Step 5: Run all focused discovery tests and verify the new tests still fail for the intended reason**

Run:

```bash
./gradlew test --no-daemon --offline --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
```

Expected: the new prefetch assertions fail, while unrelated existing failures are investigated rather than hidden.

### Task 2: Implement client-side duration prefetch

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java:394-432`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java:1414-1430`

**Interfaces:**
- Consumes the chart `List<SearchResult>` from `ClientMediaService.fetchCharts` and the existing `ClientMetadataCache.video(String)` future.
- Produces a `CompletableFuture<List<SearchResult>>`-based enrichment path used only by chart loading.

- [ ] **Step 1: Add a helper that resolves one chart without failing the batch**

Implement a private helper with this behavior:

```java
private static CompletableFuture<SearchResult> resolveChartDuration(SearchResult chart)
```

Return the original chart immediately when it is null or has a valid parsed duration. For a missing duration, call `clientMetadataCache.video(chart.getVideoId())` when the cache is available; otherwise return a copy with `--:--`. Convert a valid metadata duration into a new `SearchResult` that preserves the original ID/title/channel/thumbnail, and use `--:--` when the lookup fails or returns no valid duration. Catch synchronous cache/provider failures and return the same placeholder result.

- [ ] **Step 2: Add a helper that resolves the full list while preserving order**

Implement:

```java
private static CompletableFuture<List<SearchResult>> resolveChartDurations(List<SearchResult> charts)
```

Create one per-entry future, use `CompletableFuture.allOf`, then collect each future with its original index. All per-entry futures must complete normally because failures are represented by the placeholder result. Return an empty list for null input and do not mutate the input list.

- [ ] **Step 3: Route chart fetch completion through the enrichment helper**

In the existing `sendChartsRequest(String, boolean)` completion callback, replace the direct `updateChartResults(toScreenResults(results), canonicalRegionCode)` call with the enrichment future. Schedule the final publication on the client thread and re-check `generation == chartGeneration` immediately before updating the cache and screen. Keep `chartRequestPending` true until final publication, so refresh controls remain disabled while metadata is loading.

- [ ] **Step 4: Preserve the existing lazy action path as a cache fallback**

Do not remove `resolveChartSelection` or `updateCachedChartDuration`. Charts loaded through tests, cached legacy data, or other callers may still lack a duration, so the add/play path must retain its current fallback behavior. When normal chart loading succeeds, its published results will already carry durations and its metadata futures will already be cached.

- [ ] **Step 5: Run the focused discovery tests and verify they pass**

Run:

```bash
./gradlew test --no-daemon --offline --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
```

Expected: all discovery tests pass, including the new waiting, failure, cache-reuse, ordering, and stale-generation cases.

### Task 3: Full verification and handoff

**Files:**
- Inspect: `src/main/java/com/horizonradio/client/HorizonRadioClient.java`
- Inspect: `src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java`

- [ ] **Step 1: Run formatting and focused source checks**

Run:

```bash
./gradlew spotlessApply --no-daemon --offline
./gradlew spotlessCheck --no-daemon --offline
git diff --check
```

Expected: formatting and whitespace checks pass without modifying unrelated files.

- [ ] **Step 2: Run the complete test suite**

Run:

```bash
./gradlew test --no-daemon --offline
```

Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 3: Build the packaged client**

Run:

```bash
./gradlew build --no-daemon --offline
```

Expected: BUILD SUCCESSFUL, including reobfuscation and the clientside-loading jar.

- [ ] **Step 4: Review the final diff and commit only the implementation files**

Run:

```bash
git status --short
git diff -- src/main/java/com/horizonradio/client/HorizonRadioClient.java src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java
```

Confirm the existing unrelated dirty files remain untouched, then commit the implementation and tests with:

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioClient.java src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java
git commit -m "fix: prefetch chart durations on client"
```
