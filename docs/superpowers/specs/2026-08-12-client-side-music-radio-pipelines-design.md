# Client-Side Music and Radio Pipelines Design

**Date:** 2026-08-12  
**Branch:** `clientside-loading`

## Goal

Move both finite YouTube playback and live radio playback to the clients so the
server only coordinates the shared queue and playback controls. Every player
must see the same ordered queue and the same server-authoritative control state,
while the server must not download, decode, or relay media data.

## Scope

This design covers:

- client-side YouTube search, chart lookup, single-video/playlist import,
  metadata lookup, and audio loading;
- client-side Radio Browser search, station lookup, and live-stream loading;
- one server-authoritative queue containing finite YouTube entries and a
  special live-radio entry type;
- low-bandwidth queue snapshots, queue deltas, playback synchronization, and
  control actions;
- late joiners, stale asynchronous work, source failures, and radio stopping.

Radio Browser and YouTube requests made by a client are outside the Minecraft
server connection. The current server-side radio relay and finite audio relay
are no longer used by production playback.

## Non-goals and explicit limitations

- The server does not guarantee sample-accurate synchronization between
  independent radio HTTP connections. A live stream has one source timeline,
  but each client can have a different network and player buffer. Clients join
  the current live edge without catch-up or seeking.
- Client-local title, channel, thumbnail, and station-name resolution may fail
  or return different presentation metadata. Queue identity and order remain
  identical because they are based on server-distributed IDs.
- Radio cannot be paused or seeked like a finite file. Volume remains local to
  each client.
- Peer-to-peer media transfer is not part of this design. Direct client
  connections to YouTube and the radio station are sufficient to remove media
  traffic from the server.

## Invariants

1. The server is authoritative for queue order, accepted mutations, playback
   source, playback generation, and finite-track timing.
2. Clients apply queue state only from server snapshots or accepted server
   deltas; they do not optimistically change the shared queue.
3. A queue revision identifies one exact ordered queue state. A client that
   detects a revision gap requests a snapshot before applying later deltas.
4. A playback generation identifies one concrete playback incarnation. Any
   asynchronous download, stream connection, completion callback, or sync
   packet from an older generation is ignored.
5. Finite music uses an absolute server start time and a finite duration. Radio
   uses a station UUID and live-edge playback without a start position.
6. No normal music or radio audio bytes cross the Minecraft server connection.

## Source model

The shared queue has two entry types:

```text
YOUTUBE(videoId, durationMs, addedBy)
RADIO(stationUuid, addedBy)
```

`videoId` and `stationUuid` are the source identities distributed by the
server. `durationMs` is stored only because the server must schedule the next
finite entry and validate the configured maximum duration. It is supplied by
the client that adds the entry; the server does not retrieve it from YouTube.
Radio has no duration.

Selecting a radio station inserts a `RADIO` entry at the first queue position
and makes it the current entry immediately. Existing finite entries remain
behind it. The radio entry stays active until the user stops radio or selects a
different source. Stopping radio removes the active radio entry and starts the
next finite queue entry through the normal client-side music path.

## End-to-end data flow

### Client-side YouTube discovery

1. The player enters a search query, opens charts, or imports a YouTube URL.
2. The client performs the YouTube request directly using the shared InnerTube
   request/parsing logic.
3. Search results, chart results, imported entries, titles, channels,
   thumbnails, and durations are kept in the client UI/cache only.
4. Adding a result sends only its `videoId` and parsed `durationMs` to the
   server. Bulk additions send the same compact pair for each result.
5. The server validates the ID format, duration, queue limits, permissions, and
   current source state, then commits a queue mutation.

The server never receives the result title, channel, thumbnail, or search
query. A client that cannot resolve metadata displays a loading/error fallback
based on the ID; it does not cause a server-side media fallback.

### Client-side radio discovery

1. The client performs Radio Browser search directly.
2. Search results remain local and contain the station UUID and presentation
   metadata.
3. Selecting a station sends only its `stationUuid` to the server.
4. The server inserts the radio queue entry and broadcasts the queue change.
5. Each client resolves the station UUID directly through Radio Browser,
   obtains its current stream URL, and opens the stream locally.
6. Each client plays the current live edge. There is no server audio stream,
   radio chunk packet, start offset, or late-join catch-up.

Station click counting, if retained, is performed by the client or omitted;
the server does not perform a Radio Browser request merely to start playback.

### Queue synchronization

The server maintains a monotonically increasing `queueRevision`.

On join or resynchronization, the server sends one ID-only snapshot containing:

```text
queueRevision
ordered entries: sourceType, sourceId, addedBy
shuffle state
loop state
```

No title, duration, channel, thumbnail, or stream URL is included. The client
resolves display metadata locally by `sourceType` and `sourceId`.

For normal mutations, the client sends a compact mutation request and the
server increments the revision and broadcasts one delta:

```text
client → server: ADD(sourceType, sourceId, durationMs, requestedIndex)
server → clients: ADD(sourceType, sourceId, index, addedBy)
REMOVE(index)
MOVE(fromIndex, toIndex)
CLEAR
```

The server-bound add request contains `durationMs`; the client-bound add delta
contains only `sourceType`, `sourceId`, `index`, and `addedBy`. Duration is not
returned in client-bound queue snapshots or deltas.

Every client tracks the last applied revision. If a delta is not the immediate
successor, the client pauses queue application and sends a resynchronization
request. The server replies with a fresh snapshot. This keeps the queue
identical without broadcasting the entire queue after every edit.

### Finite-track playback

When the server activates a YouTube entry, it increments the playback
generation and stores the authoritative duration and start time:

```text
playbackGeneration += 1
startAt = serverNow + preparationLeadTime
```

The server sends a compact finite-track sync containing:

```text
sourceType = YOUTUBE
sourceId = videoId
generation
startAt
position = 0
paused = false
```

Each client downloads or reuses the cached WAV locally. A client that is ready
before `startAt` waits. A client that becomes ready later seeks to the elapsed
position derived from the synchronized server clock.

The server schedules the next finite entry from its stored `durationMs`; it does
not poll YouTube or inspect the audio file.

### Radio playback

When the server activates a radio entry, it increments the same playback
generation and sends:

```text
sourceType = RADIO
sourceId = stationUuid
generation
```

There is deliberately no `startAt`, `position`, `paused`, or `duration` field
for radio semantics. Clients stop any previous local source, resolve the
station UUID, connect directly, and start their local live player. A late
joiner receives the current radio source and joins the live edge; it does not
download historical audio.

Changing stations invalidates the old generation. A client must close its old
radio connection before accepting the new one.

### Controls

Finite music controls remain server-authoritative:

```text
client → server: pause, resume, seek, skip, previous
server: update authoritative state and generation/timing as needed
server → clients: compact control or source-sync packet
```

The server does not broadcast periodic progress. Clients derive finite-track
progress locally from the server clock and the current timing state.

Radio controls are limited to selecting a station, stopping radio, and queue
operations. Pause and seek are rejected or disabled while a radio entry is
active. Local volume changes never reach the server.

### Late joiners and reconnects

On join, a client receives the queue snapshot and current playback state.

- For YouTube, the original absolute start time is reused, so the client
  downloads locally and catches up to the current finite position.
- For radio, the client receives only the station UUID and joins the live edge.
- A new clock-sync handshake is performed on connection so future finite-track
  starts remain aligned.

Disconnecting a client cancels its local download/stream work but does not
delete persistent audio cache files.

## Packet and state changes

The implementation will replace metadata-heavy normal-music synchronization
with compact source-aware packets:

- ID-only playlist snapshot with `queueRevision`;
- queue mutation delta packets;
- client resynchronization request;
- source-aware playback synchronization for YouTube and radio;
- duration-bearing client-to-server add requests;
- client-side search and station lookup with no server response packets.

The existing clock-sync packets remain because they are small and necessary for
finite-track absolute start times. Periodic finite-music `NowPlayingPacket`s
are removed from the server path; clients render the current title and progress
from their local metadata and playback state. Radio state packets are reduced
to source/status state if a GUI status update is needed, without audio chunks.

The old finite `AudioChunkPacket` and radio audio-chunk relay classes may remain
temporarily for compatibility tests, but no production server playback path
may invoke them after this change.

## Failure handling

### Client metadata or audio failure

The failure is local. The client shows a bounded error/loading state and
prevents its own player from starting. The server does not download a fallback
or change the shared queue because one client failed.

### Invalid or missing duration

The client must resolve a finite duration before submitting a YouTube queue
mutation. The server rejects a non-positive or malformed duration and reports a
small validation message to the requesting client. This prevents the server
from needing a metadata fallback before it can schedule the queue.

### Stale asynchronous work

Every local download and radio connection captures the playback generation and
source ID. Completion is applied only if both still match the active state.

### Queue revision gap

The client requests a snapshot and does not apply subsequent deltas until the
snapshot arrives. The server never assumes that a client which missed a delta
has a correct local queue.

### Server restart

All clients reconnect and receive a new snapshot and playback state. In-memory
queue and playback generations are recreated according to the existing server
persistence behavior; no client retains authority over them.

## Server traffic target

For normal operation, the server's media traffic is zero:

```text
Server download: no YouTube audio, no radio audio, no client-side metadata
Server upload: no audio bytes; only queue, control, and synchronization packets
```

The remaining traffic is proportional to queue edits, control actions, player
joins, and the number of online players—not to song bitrate or radio duration.
Debug chat sent to every player is disabled by default for this low-traffic
mode; server logs remain available, and chat diagnostics can be enabled
explicitly.

## Testing and acceptance criteria

Protocol tests must cover:

- source-aware YouTube and radio packet round trips;
- ID-only queue snapshots and all queue delta operations;
- revision-gap resynchronization;
- duration validation and compact add requests;
- rejection of radio position/pause semantics;
- generation-based rejection of stale YouTube downloads and radio sources.

Client tests must cover:

- local YouTube search result handling;
- local Radio Browser result and station lookup handling;
- cache-hit and local-download finite playback;
- late finite-track catch-up;
- radio live-edge start and station replacement;
- queue display from IDs plus locally resolved metadata;
- local failure without a server media fallback.

Server tests must verify:

- no YouTube service call for normal search, add, or playback;
- no Radio Browser lookup or `RadioStreamService` use to start client radio;
- no finite audio-chunk or radio-chunk broadcast in production paths;
- server queue order and control state remain authoritative;
- automatic finite-track advancement uses the client-reported duration;
- radio remains active until explicitly stopped or replaced.

The implementation is complete only when `spotlessCheck`, the full test suite,
and the complete build including reobfuscation and packaging pass.
