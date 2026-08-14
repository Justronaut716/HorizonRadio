# Playlist discovery tab design

## Goal

Add a dedicated client-side `Playlists` tab to the HorizonRadio screen. A player can paste a YouTube playlist URL, load up to the first 50 songs locally, inspect them like chart results, add individual songs or the complete imported set to the server queue, and play an individual song immediately.

The imported playlist is discovery state, not queue state. Loading, replacing, scrolling, and inspecting a playlist must not mutate the server queue. Only an explicit song or bulk add action sends song IDs and durations to the server.

## Existing context

The `UI-Improvement` branch already has two relevant pieces:

- `ClientMediaService.importPlaylist(...)` fetches playlist metadata through the client discovery path and parses it with the shared `PlaylistImportService`. The parser already de-duplicates entries and limits imports to 50 entries.
- The existing tab labelled `Playlist` renders the synchronized server queue. Chart results already provide the desired row layout, duration resolution, queue button, bulk action, scrolling, and direct-play behavior.

The new feature must reuse those pieces without adding a server playlist-import request or moving the imported playlist into the synchronized queue model.

## User-facing design

The top navigation contains five compact tabs in this order:

`Charts` · `Search` · `Queue` · `Playlists` · `Radio`

The current `Playlist` label is renamed to `Queue` so its purpose is unambiguous. The new `Playlists` tab gets its own URL text field and search/import button. The existing Search/Charts/Radio field remains separate, so changing a search query cannot overwrite a playlist link and vice versa.

The Playlists tab has these states:

- Empty: show a short prompt to paste a YouTube playlist URL.
- Loading: clear the previous result list, show the existing progress bar, and disable repeated import submission until the current request finishes.
- Loaded: show up to 50 songs in the existing chart-style rows with title, duration, channel when available, scrolling, and queue state.
- Failed or invalid: show a local error message and keep the queue untouched.

Each result row behaves like a chart row:

- `+` adds the song to the server queue; `-` removes it when it is already queued.
- Clicking outside the queue button requests immediate playback and returns to the Queue tab, matching current Search/Charts behavior.
- A bulk queue button adds all imported songs that are not already queued. If every imported song is already queued or waiting for an authoritative queue update, the same button can remove the complete imported selection, matching the existing chart bulk interaction.

The loaded discovery list is retained in memory while the client session is active. Closing and reopening the screen restores the local playlist results, but no playlist is written to the client configuration file and no server-side playlist object is created.

## Architecture and data flow

### Screen state

`HorizonRadioScreen` gains a separate playlist-discovery state in addition to the existing queue state:

- `playlistResults`: the visible list of imported `SearchResult` values.
- `playlistScrollOffset`: the list scroll position.
- `playlistLoading`, progress timing, reveal timing, and `playlistError`.
- A dedicated `playlistUrlField`.
- A separate set of pending playlist adds, so pending chart and playlist actions cannot interfere with one another.

The current queue list, drag state, reorder controls, and `PlaylistEntry` type remain unchanged. The internal queue tab constants and helper names may be renamed for clarity, while the existing test-facing queue behavior remains compatible.

The result-row renderer and queue button geometry are generalized only as far as needed to render both chart and imported playlist results. Chart-specific labels and chart-region controls remain on the Charts tab.

### Client cache and import lifecycle

`HorizonRadioClient` gains a client-memory cache and accessors for imported playlist results. The cache is initialized into a newly opened screen and refreshed whenever an active import completes.

The Playlists tab calls a dedicated local import entry point. That entry point:

1. Validates that the value is a supported YouTube playlist URL.
2. Increments a playlist discovery generation and invokes `ClientMediaService.importPlaylist(...)`.
3. Publishes only the first 50 valid, de-duplicated results to the client cache and active screen.
4. Ignores completions from older generations or a screen that has already closed.
5. Publishes a local error/empty state on failure without invoking any queue transport method.

The existing Search-tab playlist URL behavior stays available for compatibility, but it continues to publish into Search only. The new Playlists tab uses its own field, generation, cache, and rendering path.

### Queue handoff

Imported rows use the existing local duration-resolution and compact queue-selection flow. Before a queue mutation, the client resolves missing or invalid durations locally and validates the configured finite-track limit. The server receives only the selected video IDs and finite durations through the existing multi-add packet path; it never receives the playlist URL or an import request.

The client does not optimistically insert imported rows into the queue. Queue button state changes only after the existing authoritative queue snapshot/delta updates arrive.

### Server scope

No server manager, server handler, packet registration, or server playlist-import behavior is added or changed for this feature. Existing server queue validation, capacity, duplicate, and removal semantics continue to apply when songs are explicitly handed off.

## Error handling and edge cases

- Invalid/non-YouTube links are rejected locally with an actionable message.
- Empty or malformed provider responses produce an empty/error state, never a queue mutation.
- Entries without a usable video ID or title are skipped by the existing parser; the client defensively caps the displayed result list at 50.
- A second import supersedes the first. The first completion cannot replace the newer list.
- Closing the screen invalidates the active playlist import for that screen. Reopening uses the last completed client cache, not an old in-flight result.
- Songs whose duration cannot be resolved locally remain non-addable and are reported through the existing local failure/debug path.
- A playlist may contain songs already in the queue; those rows show `-` and are not duplicated by a bulk add.

## Testing strategy

Add focused tests for:

- local playlist import never calling the client transport or any server-bound operation;
- cache publication and screen refresh after a successful import;
- the 50-result limit, duplicate removal, invalid-entry filtering, and empty/failure states;
- stale import completion being ignored after a newer import or screen close;
- queue and imported-playlist lists remaining independent;
- individual and bulk playlist handoff preserving source order and resolving durations through the existing local path;
- the five-tab layout, separate playlist URL field, Queue/Playlists distinction, scroll behavior, and direct-play navigation.

Run the focused client/media/UI tests and the complete Gradle build before declaring the feature complete.

## Scope boundaries

This first implementation does not persist playlist history, expose playlist names or thumbnails beyond metadata already returned by the existing provider, support editing an imported playlist, or add server-side playlist management. Those can be separate features once the local discovery-to-queue flow is proven.
