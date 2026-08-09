# Shared Radio Tab Design

**Date:** 2026-08-09

**Status:** Approved design

## Goal

Add a Radio tab to HorizonRadio where players can search internet radio
stations and start one shared live station for everyone connected to the
server. Switching between radio and the existing YouTube player is explicit,
server-authoritative, and leaves the playlist intact.

## Scope

The feature includes:

- A Radio tab with station search and a list of station names.
- Server-side Radio Browser API access and station validation.
- One server-owned FFmpeg relay for the active radio stream.
- Continuous audio packets and client-side live playback through Java Sound.
- Shared source switching between YouTube music and radio.
- A radio-specific control center and connection/error states.
- Unit, packet, server-state, and GUI regression tests.

The feature does not include station favorites, adding stations to the
YouTube playlist, station metadata display beyond the station name, a second
radio source, or client-specific radio playback.

## Existing constraints

- The project targets Forge 1.7.10 and must remain Java-8-runtime compatible.
- The server is already required to have FFmpeg for YouTube audio conversion.
- The server owns shared playback state; clients receive intent results and
  audio data through `SimpleNetworkWrapper`.
- The existing finite YouTube path uses WAV bytes, `AudioChunkPacket`, and a
  Java Sound `Clip`. Live radio must use a separate streaming path.
- Existing playlist behavior, including immediate `PlayNow`, remains intact.
- No new client codec dependency is added; FFmpeg produces PCM for the live
  radio path.

## Radio Browser integration

The server uses the Radio Browser advanced station search API. The service
uses the documented server-mirror discovery/failover behavior and sends a
descriptive User-Agent.

Search requests:

- Trim and bound the GUI query to the existing 100-character input limit.
- Use `name=<query>` for a non-empty search.
- Use `hidebroken=true` and `limit=50` for every request.
- The initial Radio tab request loads popular working stations with
  `order=votes&reverse=true`.
- Accept only station records with a UUID, a station name, and an HTTP(S)
  stream URL usable by FFmpeg.
- Keep station UUIDs as the stable identifier; never use legacy numeric IDs.
- Use the Radio Browser click-count endpoint when a player starts a station.

Search result packets contain the station UUID and station name. Country,
language, codec, bitrate, tags, favicon, and stream URLs are server-side
metadata and are not rendered or sent as GUI display fields.

When a station is selected, the client sends only its station UUID. The server
performs a fresh UUID lookup, validates the returned URL and metadata, and
does not trust a client-supplied stream URL.

## Shared playback state

The server tracks one active source mode:

- `MUSIC`: the existing `PlaylistManager`/YouTube flow.
- `RADIO`: one active station and one live relay generation.
- `IDLE`: no active audio source.

The YouTube playlist remains stored while radio is active. Starting or
stopping radio does not clear, reorder, or remove playlist entries.

Adding, importing, removing, reordering, or clearing playlist entries while
radio is active does not start YouTube playback or stop the radio relay. Only
an explicit direct `PlayNow` action switches the shared source back to music.

### Radio selection

1. Validate the station UUID and fetch fresh station data from Radio Browser.
2. Start a candidate FFmpeg process for the resolved URL.
3. Wait up to 15 seconds for the first valid PCM frame.
4. Only after the candidate is usable, stop the previous source, increment the
   stream generation, and publish the new radio state to every player.
5. Broadcast the station state and PCM frames to all connected clients.

If candidate startup fails, the old source remains active. If an already active
radio process fails unexpectedly, the server stops the radio source, reports
the error to all clients, and leaves the playlist idle rather than silently
starting a queued song.

### Direct music selection

Every existing direct music `PlayNow` path stops radio before starting music.
This includes search results, chart results, and Queue/Playlist row clicks.

The server-side sequence is:

1. Stop and invalidate the active radio relay.
2. Broadcast the radio stop/inactive state and discard old radio generations.
3. Run the existing server-authoritative `PlayNow` behavior.
4. Broadcast and stream the selected YouTube track through the existing path.

The radio stop is immediate; the existing YouTube download/readiness behavior
then determines when the selected song becomes audible.

### Explicit radio stop

`Stop Radio` stops the relay, clears the active radio state, and leaves the
playlist unchanged. It does not automatically resume the previous YouTube
track.

## Server-side live relay

`RadioStreamService` owns one published active FFmpeg process. During the
maximum 15-second handover it may additionally own one unpublished candidate
process so the previous source can continue if the new station fails. FFmpeg
reads the validated station URL and writes signed, little-endian, 16-bit PCM
to stdout at a fixed 44,100 Hz stereo format. The radio start packet carries
that format explicitly so the client can reject incompatible data.

The service:

- Drains FFmpeg stderr so the process cannot block on diagnostics.
- Uses bounded connect/startup/read operations and a bounded in-memory relay
  buffer.
- Assigns a monotonically increasing generation and sequence number.
- Stops the process on station replacement, explicit stop, server shutdown, or
  any stale generation request.
- Never broadcasts bytes from an old generation after a source switch.

The relay splits PCM into packets no larger than the existing 30 KiB network
limit. A packet contains the radio generation, sequence number, and bounded
PCM bytes. A separate start/state packet contains the station UUID, station
name, PCM format, and the first sequence number. A stop/state packet marks the
radio source inactive and carries an optional user-facing error status.

Radio packets are separate from `AudioChunkPacket`; the finite-track assembler
must never receive live-stream data.

## Client live playback

`AudioPlayer` gains a live-stream mode in addition to the existing finite
`Clip` mode. The live mode:

- Validates the generation, sequence, PCM format, and packet size.
- Uses a bounded three-packet jitter buffer before starting the line.
- Starts a `SourceDataLine` after the startup buffer is full.
- Writes PCM on the audio executor, never on a Forge packet or GUI thread.
- Drops stale generations and old sequence gaps instead of replaying old data.
- Stops and closes the line on radio stop, music `PlayNow`, disconnect, or
  shutdown.
- Applies the existing client-local volume setting to the live line.

Late-joining players receive the current radio state and start with the next
available live buffer. They hear the same station and stream content; small
network-dependent start offsets are acceptable and are not treated as a
sample-accurate synchronization guarantee.

## Radio tab and GUI behavior

The existing 300x285 GUI receives a fourth tab named `Radio`.

The Radio tab contains:

- The existing search field and Search button layout.
- A loading/progress state while the server searches.
- Up to six visible station rows with scrolling using the existing list
  geometry.
- Only the station name in each row.
- A highlighted row and `LIVE` marker for the active station.
- An empty state for no query results or no available stations.
- No queue add/remove controls.

Clicking anywhere on a station row sends a station-selection request. The
selection is not optimistic: the server broadcasts the active row only after
the relay has produced valid audio.

## Radio control center

When the active source is `MUSIC`, the existing controls are unchanged.

When the active source is `RADIO`:

- The finite progress bar is replaced by a `LIVE` indicator.
- The five music controls (pause, seek, previous, next, shuffle, and loop) are
  hidden because they have no meaningful live-radio semantics.
- The station name and connection status are shown in the now-playing area.
- The volume slider remains available.
- The music control row is replaced by two centered text actions: `Stop Radio`
  and `Change Station`. The latter opens the Radio tab.

The server rejects or ignores stale/unsupported music-control requests while
radio is active, except for direct `PlayNow`, which is the explicit source
switch back to music.

## Error handling

- Radio Browser search failures return an empty result state to the requesting
  player and log the server-side cause.
- A station without a valid UUID, name, or HTTP(S) stream URL is excluded.
- FFmpeg startup timeout or invalid PCM output leaves the previous source
  active when switching to a candidate station.
- An active relay failure stops radio for everyone and displays a concise
  unavailable/error status; the playlist is preserved and not auto-started.
- Client packet gaps, stale generations, malformed formats, and oversized
  chunks are discarded without affecting the server or other clients.
- Disconnect and server shutdown always terminate the relay process and clear
  live buffers.

## Testing requirements

Tests must cover:

- Radio Browser JSON parsing, missing fields, duplicate UUIDs, result limits,
  and working-stream filtering.
- Search and station-selection packet round trips and field bounds.
- Radio stream generation/sequence acceptance and stale-packet rejection.
- Radio source replacement, explicit stop, process failure, and shutdown.
- `PlayNow` stopping radio before the existing YouTube playback flow.
- Playlist preservation when entering or leaving radio mode.
- Radio-tab search, row selection, active-row rendering, scrolling, and empty
  states.
- Radio-control visibility and the `Stop Radio`/`Change Station` actions.
- Full existing test suite and `git diff --check`.

## Acceptance criteria

The feature is complete when:

1. A player can open the Radio tab and search for stations.
2. Only station names are shown in the result rows.
3. Clicking a station eventually starts that same station for all connected
   players through the server relay.
4. Radio playback has no misleading pause, seek, next, previous, shuffle, or
   loop actions.
5. `Stop Radio` stops the live stream without changing the playlist.
6. Clicking any song through an existing direct `PlayNow` path stops radio and
   starts the selected song.
7. Stream failures and stale packets do not crash the client or server.
8. Existing YouTube playlist, search, chart, and queue behavior remains
   regression-tested and passing.
