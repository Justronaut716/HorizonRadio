# HorizonRadio Forge architecture

## Forge lifecycle and runtime boundaries

```text
Forge @Mod lifecycle
        |
  HorizonRadio + IntegrationManager
        |
  Portable core contracts/state  <---  optional GTNH capability
        |
  CommonProxy / ClientProxy / ServerEvents
        |
  SimpleNetworkWrapper + client-local media adapters
```

`HorizonRadio` registers the network, loads configuration, selects the sided
proxy, and drives the pre-init/init/post-init lifecycle. `IntegrationManager`
discovers the optional GTNH capability without making it a runtime dependency.
`HorizonRadioNetwork.registerMessages()` runs from common pre-initialization,
so both sides know the compact coordination protocol without loading client-only
classes on a dedicated server. S2C handlers call only `HorizonRadio.proxy`, and
C2S handlers schedule work through `ServerThreadExecutor` before touching
playlist state.

## Package map

| Package | Boundary and responsibility |
|---|---|
| `com.horizonradio` | Forge mod entry point and common sided proxy boundary. |
| `com.horizonradio.core.config`, `.model`, `.audio`, `.server` | Portable configuration, source-aware queue models, compatibility audio adapters, and Java-only state. |
| `com.horizonradio.core.protocol` | Version and the `horizonradio_1_0` channel contract. |
| `com.horizonradio.core.integration` | Project-owned integration interface and context. |
| `com.horizonradio.integration` | Optional capability detection, manager, and adapter implementation. |
| `com.horizonradio.network` and `.network.packets` | `SimpleNetworkWrapper`, handlers, codecs, and 24 production-registered packet types. Legacy serializers are retained for compatibility tests but are not registered. |
| `com.horizonradio.server` | Forge/server-facing queue manager, events, validation, timing, and server-thread services. It does not perform YouTube, Radio Browser, station-stream, or media-relay work in production. |
| `com.horizonradio.client` and `.client.audio` | Client proxy, GUI, keybinds, direct discovery, local metadata/cache, finite playback, direct radio playback, and Java Sound. |

## Two-source pipeline

The shared queue has two source types:

```text
YOUTUBE(videoId, durationMs, addedBy)
RADIO(stationUuid, addedBy)
```

The server is authoritative for queue order, accepted mutations, source,
generation, finite duration, finite timing, and finite controls. It stores no
title, channel, thumbnail, station name, station URL, or audio bytes. A client
performs YouTube search, chart lookup, imports, metadata lookup, audio download,
and decoding directly. It submits only a video ID plus a positive, finite
duration for a finite add, play-now, or chart add. The server validates the ID,
duration, limits, permissions, and queue state without a YouTube request.

Radio Browser search and station lookup also run directly on every client.
Selecting radio sends only the station UUID to the server. The server inserts or
replaces the radio source, advances generation, and synchronizes that source;
it never looks up the UUID or opens the stream. Each client resolves the UUID,
opens the station stream, and plays its own current live edge.

## Forge message contract

The channel is `horizonradio_1_0`. The current production contract has 24
registrations: 16 C2S and 8 S2C. IDs are not compacted; removed production
messages leave their previous IDs unused. IDs 36 and 37 extend the contract for
revisioned queue state.

| ID | Direction | Packet | Fields |
|---:|:---:|---|---|
| 1 | C2S | `AddToPlaylistPacket` | video ID, positive finite duration |
| 2 | C2S | `RemoveFromPlaylistPacket` | source ID |
| 5 | S2C | `PlaylistSyncPacket` | queue revision, shuffle/loop flags, ordered source type/source ID/adder entries |
| 8 | S2C | `PausePacket` | finite position in milliseconds |
| 9 | S2C | `ResumePacket` | finite position and absolute start timestamp |
| 10 | C2S | `ReorderPlaylistPacket` | source index, target index |
| 11 | C2S | `SeekRequestPacket` | normalized finite-track progress |
| 12 | C2S | `TogglePlaybackPacket` | no fields |
| 13 | C2S | `SkipTrackPacket` | no fields |
| 14 | C2S | `PreviousTrackPacket` | no fields |
| 15 | C2S | `ToggleLoopPacket` | no fields |
| 16 | S2C | `LoopStatePacket` | enabled flag |
| 17 | C2S | `ToggleShufflePacket` | no fields |
| 18 | S2C | `ShuffleStatePacket` | enabled flag |
| 22 | C2S | `AddChartsToPlaylistPacket` | remove flag; video IDs and positive finite durations |
| 23 | C2S | `ClearPlaylistPacket` | no fields |
| 24 | C2S | `PlayNowPacket` | video ID, positive finite duration |
| 26 | C2S | `SelectRadioStationPacket` | station UUID |
| 27 | C2S | `StopRadioPacket` | no fields |
| 33 | C2S | `ClockSyncRequestPacket` | client send timestamp |
| 34 | S2C | `ClockSyncResponsePacket` | client send, server receive, server send timestamps |
| 35 | S2C | `TrackSyncPacket` | source-aware playback state (described below) |
| 36 | S2C | `PlaylistDeltaPacket` | queue revision and one compact add/remove/move/clear/replace operation |
| 37 | C2S | `PlaylistResyncRequestPacket` | client-known queue revision |

`PlaylistSyncPacket` and `PlaylistDeltaPacket` are ID-only queue projections:
they contain source type, source ID, and the queue adder, but no title,
duration, channel, thumbnail, station URL, or audio. An add delta contains one
entry and index; remove and move contain indexes; clear has no operation data;
replace carries a complete ID-only entry list. Every mutation increments
`queueRevision`. A client applies only the immediate next delta and requests one
fresh snapshot after a revision gap.

`TrackSyncPacket` has two wire forms. A YouTube sync contains source type,
video ID, generation, position, absolute `startAt`, and paused state. A radio
sync contains source type, station UUID, and generation only. Radio therefore
has no `startAt`, position, paused field, duration, or catch-up calculation.

### Compatibility-only serializers

The source tree intentionally retains old result and relay serializers so
isolated compatibility tests and old adapter tests can round-trip them. They are
not production registrations and no production server path sends them. This
includes the old search/chart/import result flow, `NowPlayingPacket`, finite
`AudioChunkPacket`, radio result/state packets, radio audio-start/audio-chunk
packets, and chart-add completion packet. Their historical discriminators are
not reused for another message.

All strings, collection counts, indexes, and byte arrays remain bounded before
allocation. Bounds on compatibility relay payloads do not imply a production
relay path.

## Synchronization and playback

For a finite source, the server advances playback generation and sends an
absolute server start time. Clients download or reuse their local WAV cache;
ready clients wait until `startAt`, while late clients derive an elapsed finite
position from the synchronized server clock. Finite pause, seek, skip, and
other controls remain server-authoritative.

For radio, changing station advances generation and invalidates older local
connections. A client closes stale work, resolves the station UUID locally, and
starts the direct stream at its live edge. Late joiners receive the same UUID and
generation but do not seek or catch up. Independent live HTTP connections are
not sample-accurately synchronized. Radio pause and seek are unavailable;
volume remains client-local.

## Client-side media services

`ClientMediaService` and `ClientMetadataCache` keep discovery results and
presentation metadata local. A local lookup failure shows loading/error state
for that client only; it never causes a server metadata lookup or audio relay.
The shared embedded backend supplies YouTube and Radio Browser clients plus the
direct finite/radio decoding support. Its historical server package location is
an implementation detail, not permission for production server media traffic.

`PlaylistManager` receives only validated queue/control requests and broadcasts
snapshots, deltas, and source-aware synchronization. It schedules automatic
finite advancement from the client-reported finite duration. During radio, it
accepts only station selection, stopping radio, and queue operations; finite
pause/seek controls are rejected or disabled.

## Audio state and compatibility adapters

`AudioPlayer` uses generation checks to ignore stale finite downloads and stale
radio connections. Finite playback is a local seekable `Clip` driven by the
server clock. `ClientRadioPlayback` owns the direct live connection and local
Java Sound output; a source change, failure, disconnect, or shutdown closes its
current local line.

`AudioChunkAssembler`, `ReadyPacket`, and `RadioStreamBuffer` remain for
compatibility-only relay tests. They do not describe the production pipeline:
the Minecraft server does not broadcast finite audio chunks, radio audio chunks,
or `NowPlayingPacket` metadata.

## GUI and input

`HorizonRadioScreen` preserves the 300x285 immediate-mode panel with Charts,
Search, Playlist, and Radio tabs, scrolling, shared removal, drag-and-drop
queue reordering, local volume, and source-aware controls. Search, charts,
imports, Radio Browser lookup, and metadata resolution occur directly on the
client. Queue rows resolve local display metadata from their server-distributed
source IDs and can show a bounded ID/loading/error fallback.

The Radio tab sends only the chosen station UUID. Radio mode disables pause and
seek as well as finite-only controls; stopping radio returns control to the
server-authoritative queue. The `N` key opens the GUI only when both
`Minecraft.theWorld` and `Minecraft.thePlayer` are non-null.

## Preserved, reimplemented, and omitted systems

| Source feature | Port status | Decision |
|---|---|---|
| Playlist/control authority | Reimplemented | The server coordinates source IDs, queue order, revisions, generations, finite timing, and controls. |
| Discovery and metadata | Reimplemented | YouTube and Radio Browser discovery/metadata are client-local; the server has no production lookup path. |
| Finite audio and live radio | Reimplemented | Clients download finite audio and connect to radio streams directly; no Minecraft audio relay is registered. |
| Protocol | Reimplemented | 24 active `IMessage` registrations carry compact mutations, ID-only queue state, source-aware sync, and clock data. Legacy serializers are compatibility-only. |
| JSON config | Preserved | Server/common queue limits remain; client volume and finite audio cache are stored separately. |
| GUI and N key | Reimplemented | Forge 1.7.10 GUI/input classes preserve the existing panel and interactions. |
| Items, blocks, recipes, machines, TileEntities, NBT persistence | Omitted | No world content or persistent playlist exists. |
| World rendering, mixins, coremod, access transformer | Omitted | The active behavior is event/API based; only the 2D GUI renders. |
| Companion Node/Express service | Omitted | Historical architecture; the port is self-contained. |
