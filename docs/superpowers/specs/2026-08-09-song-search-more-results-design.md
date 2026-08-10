# Song search: more valid results

## Goal

When a player searches for a term such as `funk`, the Song Search tab should
show at least 10 playable song results whenever YouTube can provide that many.
The existing server-side duration limit remains authoritative, so results that
cannot be downloaded or played under the configured limit are not presented as
playable songs.

## Current cause

`YouTubeService` currently parses only the first InnerTube search response and
returns up to 50 raw video candidates. `PlaylistManager` then removes entries
with no usable duration and entries at or above the configured 15-minute limit.
Playlist renderers are already ignored by the parser. A single search page can
therefore leave only three or four valid entries even though more matching
videos are available on later pages.

## Approved design

The server will use bounded InnerTube continuation pagination:

1. Request the initial search page using the existing YouTube client context.
2. Parse video renderers and the continuation token from that page.
3. Request subsequent pages with the continuation token while a token exists
   and at most three pages (150 raw video candidates) have been inspected.
4. Deduplicate candidates by video ID across all pages.
5. In `PlaylistManager`, keep the existing duration predicate, filter the
   combined candidate list, and send the first 10 valid entries to the
   requesting client. If fewer than 10 valid entries are available within the
   bounded search, send all valid entries.

The search response packet and client UI do not need a new protocol or layout:
the existing result list and scrollbar display the larger result set. Playlist
imports, chart results, direct PlayNow, Queue behavior, and the configured
duration limit are unchanged.

## Boundaries and failure behavior

- The page count and raw candidate count are hard limits to prevent a slow or
  malformed YouTube response from creating unbounded work.
- A missing or unusable continuation token ends pagination and returns the
  valid results already collected.
- A failed continuation request returns the valid results from earlier pages;
  it does not replace them with an empty result set.
- Invalid JSON or an initial request failure preserves the existing behavior of
  returning an empty search result list.
- Search results remain server-filtered before they are sent to clients.
- No playlist renderer is converted into a song result.

## Testing

Add deterministic parser/service tests for:

- extracting a continuation token from an initial response;
- parsing a continuation response containing more video renderers;
- deduplicating a video repeated across pages;
- collecting candidates across pages and capping the filtered response at 10
  valid songs while respecting the three-page/150-candidate bound;
- returning fewer than 10 when no more valid pages exist;
- retaining earlier valid results when a later page fails;
- filtering long or unknown-duration results before packet construction.

Keep the existing packet round-trip and GUI search/scroll tests green. The
full Gradle test suite must pass after implementation.

## Acceptance criteria

- A search normally returns at least 10 valid songs when YouTube has at least
  10 matching songs below the configured duration limit.
- No playlist result, unknown-duration result, or over-limit result is shown
  as a playable song.
- Search is bounded to three pages / 150 raw video candidates.
- Existing search, chart, playlist, queue, and radio behavior remains intact.
