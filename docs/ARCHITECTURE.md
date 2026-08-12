# HorizonRadio Forge architecture

## Forge lifecycle and runtime boundaries

The runtime is organized around the Forge lifecycle and a project-owned optional
integration seam:

```text
Forge @Mod lifecycle
        |
  HorizonRadio + IntegrationManager
        |
  Portable core contracts/state  <---  optional GTNH capability
        |
  CommonProxy / ClientProxy / ServerEvents
        |
  SimpleNetworkWrapper + Java Sound client adapter
```

`HorizonRadio` registers the network, loads configuration, selects the sided
proxy, and drives the pre-init/init/post-init lifecycle. `IntegrationManager`
discovers the optional GTNH capability without making it a runtime dependency.
The integration seam accepts only `HorizonRadioIntegrationContext`, which is
project-owned and currently contains the mod version and `HorizonRadioConfig`;
optional integrations do not add external types to core contracts, metadata, or
static initialization.

`HorizonRadioNetwork.registerMessages()` is called from common pre-initialization.
The dedicated server therefore knows every discriminator and can serialize
S2C messages sent with `sendTo`, without loading `Minecraft`, GUI, LWJGL, or
Java Sound classes. The S2C handlers call only methods on `HorizonRadio.proxy`.
`ClientProxy` converts packet data into client state and schedules mutations
with the Forge 1.7.10 client-thread method. C2S handlers queue server work in
`ServerThreadExecutor` before touching playlist state. `ServerEvents` drains
that server work on server ticks and forwards player login/logout transitions.

## Package map

| Package | Boundary and responsibility |
|---|---|
| `com.horizonradio` | Forge mod entry point and common sided proxy boundary. |
| `com.horizonradio.core.config`, `.model`, `.audio`, `.server` | Portable configuration, models, audio state/chunk assembly, playlist/import/chart state, and Java-only services. |
| `com.horizonradio.core.protocol` | Version `1.0.0` and the `horizonradio_1_0` channel contract. |
| `com.horizonradio.core.integration` | Project-owned integration interface and context. |
| `com.horizonradio.integration` | Optional capability detection, manager, and adapter implementation. |
| `com.horizonradio.network` and `.network.packets` | Forge `SimpleNetworkWrapper`, handlers, codecs, and the 36 current-source packet types, including the contract below. |
| `com.horizonradio.server` | Forge/server-facing playlist manager, events, embedded metadata/radio services, YouTube, Radio Browser, and server-thread services. |
| `com.horizonradio.client` and `.client.audio` | Client proxy, GUI, keybinds, client transport, and Java Sound playback. |

## Forge message contract (current source: IDs 0–35)

The channel is `horizonradio_1_0`. IDs and field order are stable within the
1.0 port. The current source contract is:

| ID | Direction | Packet | Fields |
|---:|:---:|---|---|
| 0 | C2S | `SearchRequestPacket` | query |
| 1 | C2S | `AddToPlaylistPacket` | video ID, title, duration |
| 2 | C2S | `RemoveFromPlaylistPacket` | video ID |
| 3 | C2S | `ReadyPacket` | video ID |
| 4 | S2C | `SearchResultsPacket` | charts flag, chart region code, count + five strings per result |
| 5 | S2C | `PlaylistSyncPacket` | count + four strings per entry |
| 6 | S2C | `AudioChunkPacket` | video ID, title, index, count, offset, bytes |
| 7 | S2C | `NowPlayingPacket` | title, progress |
| 8 | S2C | `PausePacket` | position in milliseconds |
| 9 | S2C | `ResumePacket` | position in milliseconds |
| 10 | C2S | `ReorderPlaylistPacket` | source index, target index |
| 11 | C2S | `SeekRequestPacket` | normalized track progress |
| 12 | C2S | `TogglePlaybackPacket` | no fields |
| 13 | C2S | `SkipTrackPacket` | no fields |
| 14 | C2S | `PreviousTrackPacket` | no fields |
| 15 | C2S | `ToggleLoopPacket` | no fields |
| 16 | S2C | `LoopStatePacket` | enabled flag |
| 17 | C2S | `ToggleShufflePacket` | no fields |
| 18 | S2C | `ShuffleStatePacket` | enabled flag |
| 19 | C2S | `ImportPlaylistPacket` | playlist URL |
| 20 | C2S | `ImportVideoPacket` | video URL |
| 21 | C2S | `RequestChartsPacket` | canonical region code, force-refresh flag |
| 22 | C2S | `AddChartsToPlaylistPacket` | remove flag, count + video ID, title, duration per entry |
| 23 | C2S | `ClearPlaylistPacket` | no fields |
| 24 | C2S | `PlayNowPacket` | video ID, title, duration |
| 25 | C2S | `RadioSearchRequestPacket` | query |
| 26 | C2S | `SelectRadioStationPacket` | station UUID |
| 27 | C2S | `StopRadioPacket` | no fields |
| 28 | S2C | `RadioSearchResultsPacket` | count + station UUID and name per entry |
| 29 | S2C | `RadioStatePacket` | active flag, generation, station UUID, station name, status |
| 30 | S2C | `RadioAudioStartPacket` | generation, first sequence, PCM format |
| 31 | S2C | `RadioAudioChunkPacket` | generation, sequence, PCM bytes |
| 32 | S2C | `ChartAddCompletionPacket` | completed chart video IDs |
| 33 | C2S | `ClockSyncRequestPacket` | client send timestamp |
| 34 | S2C | `ClockSyncResponsePacket` | client send, server receive, server send timestamps |
| 35 | S2C | `TrackSyncPacket` | generation, video ID, position, absolute start timestamp, paused flag |

The C2S handlers obtain the player, schedule each packet request on the server
thread, and delegate to `PlaylistManager`. There, request validation,
authoritative `PlaylistState` mutation, and broadcasts to clients occur.
`AddChartsToPlaylistPacket` writes a leading remove boolean, then the entry
count and each entry's video ID, title, and duration; a false flag adds valid
chart entries and a true flag removes the matching chart entries.
`ClearPlaylistPacket` requests that `PlaylistManager` clear the playlist, and
`PlayNowPacket` requests immediate selection of the supplied video ID, title,
and duration while preserving the server-owned queue and playback state.

Chart requests are validated against the common `ChartRegionCatalog`, which
contains every ISO-3166-1 country plus locale-derived aliases. The internal
legacy `GLOBAL` code remains available for packet compatibility, but the GUI
does not request or display global charts.
The server fetches only weekly `TRACKS` charts from YouTube and stores each
canonical region independently in the seven-day `ChartCache`; the old single
German cache format migrates to `DE`. Chart result packets include the
canonical region so a client can discard stale responses after switching
regions. The Charts tab starts empty and only shows a country after a successful
country search; unknown or ambiguous local input leaves the current results
visible.

All strings, collection counts, indexes, and byte arrays are bounded before
allocation. Legacy server-relayed audio remains split at 30 KiB. A
`TrackSyncPacket` contains no title or audio payload: its video ID is resolved
locally by each client. A future absolute start timestamp gives clients a
three-second preparation window; a download that completes after that point
starts at the elapsed server-clock position. Radio queries are at
most 100 characters; station UUIDs are at most 64 UTF-8 bytes; station names are
at most 200 UTF-8 bytes; a search response has at most 50 entries; radio status
is at most 160 UTF-8 bytes; and each radio PCM chunk is at most 30 KiB.

Radio result and state packets deliberately contain only a station UUID, display
name, status, and relay metadata. A stream URL is neither a client-facing model
field nor a packet field. The client can request a UUID, but the server performs
the authoritative lookup and starts the external stream.

## Radio directory, relay, and source switching

`RadioBrowserService` resolves Radio Browser directory mirrors and makes the
HTTP(S) directory requests on the server. An empty query requests up to 50
popular stations; a non-empty query is bounded to 100 characters and searches
by name. Before a result is exposed, the service rejects duplicate UUIDs,
unnamed or broken records, and streams that are not valid HTTP(S) URLs. Selecting
a result performs a fresh server-side lookup by UUID and records the directory
click only after the candidate becomes active.

`RadioInputSession` opens the selected server-only URL with Java HTTP APIs,
strips ICY metadata, detects the direct stream format, and decodes MP3,
ADTS/AAC, Ogg Vorbis, or Ogg Opus into 44.1 kHz stereo signed 16-bit
little-endian PCM. `RadioStreamService` requires first PCM data within 15
seconds, bounds inactivity, and owns session cleanup. Station changes use a
candidate handover: the published relay remains audible until the candidate
produces its first PCM chunk; only then does the manager promote it, send
state/start/chunk packets, and close the previous relay. A failed or superseded
candidate leaves the published station intact.

## Embedded media backend

Finite YouTube audio, YouTube metadata, and direct radio decoding run in the
embedded Java backend. Its decoder registry supports MP3, ADTS/AAC, WAV/PCM,
M4A/MP4 AAC, Ogg Vorbis, Ogg Opus, and WebM/Opus. A client resolves the video
ID, normalizes finite audio through `WavFileSink`, and stores the result in its
own cache; the production server does not download, decode, or relay finite
audio. Direct radio is normalized through the radio input session and bounded
relay. The backend requires outbound HTTP(S) access to YouTube on each client,
plus server access to Radio Browser/the selected station and Java Sound on each
client for audible playback.

HLS/M3U8 was intentionally not added because the current acceptance corpus has
no concrete required HLS URL. Direct HTTP radio remains the implemented path.

`PlaylistManager` owns the single shared source and its clock. Promoting a radio
candidate cancels finite-track synchronization work and stops finite playback
while preserving the queue. A radio stop clears the live source but does not
auto-resume the queue. Playlist additions use the radio-active guard, so adding
a track while radio is active does not start it. Play Now explicitly stops
radio before selecting finite playback. Server-side seek, play/pause,
previous/next, repeat, shuffle, automatic advance, and progress broadcasts are
also guarded while radio is active.

## Audio state machine

`AudioChunkAssembler` is Java-8-pure and rejects invalid indexes, mismatched
track metadata, out-of-order chunks received before chunk zero, duplicates, and
oversized chunks. A new chunk zero clears older in-flight video IDs. Completed
chunks are assembled in index order and buffers are cleared.

For the production finite-track path, `AudioPlayer` uses one daemon executor:

1. Receive a `TrackSyncPacket` containing only the video ID and shared timing.
2. Download/normalize the ID through the client-local Java media service and
   reuse a completed WAV cache entry.
3. Decode through `AudioSystem.getAudioInputStream` and convert to signed
   16-bit PCM when necessary.
4. Open one `Clip`, seek to the shared server timestamp, apply bounded
   `MASTER_GAIN`, and start it, or hold it while a pause is active. If loading
   finishes late, the seek position includes the elapsed time since the shared
   start.
5. Pause/resume/stop/close through the same executor and generation/lock
   guards. Natural completion closes the line; disconnect cancels the active
   download and stops playback.

The `AudioChunkAssembler` and `ReadyPacket` path remain as a compatibility
adapter for isolated legacy/serverless relay tests; the real Minecraft server
does not send finite audio chunks.

`AudioPlayerState` supplies a sound-device-independent model for state tests;
the real `Clip` remains optional at test time.

For radio, `RadioStreamBuffer` accepts only the fixed relay format and strictly
ordered packets for one generation. It bounds startup buffering to three 30 KiB
packets before playback begins. `AudioPlayer` moves those packets through a
bounded handoff, preserves complete PCM frames, and writes them to one
`SourceDataLine`; it applies the same local volume and closes the line on a
source change, failure, disconnect, or shutdown. This is a live adapter, not a
seekable `Clip`, and stale generations are ignored.

## Server authority rules

The server owns the shared playlist, ordering, current source, playback
position, pause/resume, loop/shuffle state, chart/search/radio results, and
radio stream URLs. Clients send intent packets; server handlers validate and
apply those requests, then broadcast authoritative state and the finite-track
video ID/timing. A client may remove entries as allowed by the active server
rules, but it cannot directly change shared state. Finite audio bytes and the
WAV cache are client-local. Volume is client-local and persists in
`config/horizonradio-client.json`; Java Sound playback follows server timing
but does not become a second shared source of truth.

## GUI and input

`HorizonRadioScreen` preserves the active 300x285 immediate-mode panel: Charts,
Search, Playlist, and Radio tabs; six visible rows; scroll offsets; shared `X`
removal; drag-and-drop reordering for queued entries; empty states; now-playing
status; and local volume control. The Charts tab exposes the existing search
field for multilingual country/region lookup and displays the selected
canonical region. The currently playing entry remains fixed at the head of the
queue. The Radio tab requests popular stations on first open or
searches station names, displays a `LIVE` marker, and sends only the selected
station UUID. During live radio it keeps the standard control center but
disables shuffle, previous, next, and repeat; the center play/stop control
stops and restarts the selected station. The port uses Forge 1.7.10
`GuiScreen`, `GuiButton`, `GuiTextField`, LWJGL mouse/keyboard input, and
`drawRect`/`drawString`. No thumbnail HTTP or texture pipeline was added.

The `N` key opens the GUI only when `Minecraft.theWorld` and
`Minecraft.thePlayer` are both non-null. A title-screen test is intentionally
not accepted as GUI evidence.

## Preserved, reimplemented, and omitted systems

| Source feature | Port status | Decision |
|---|---|---|
| Playlist/search/import/download/playback | Reimplemented | Forge events, Java 8 services, embedded Java metadata/audio handling, client-local finite downloads, and SimpleNetworkWrapper preserve behavior. |
| JSON config | Preserved | `config/horizonradio.json` keeps server/common settings such as `downloadDir` and `maxPlaylistSize`; `maxTrackDurationMinutes` filters search results server-side, while client volume and finite audio cache are stored separately. |
| GUI and N key | Reimplemented | Same geometry and interaction, Forge 1.7.10 classes. |
| Legacy payloads/receivers and Radio protocol | Reimplemented | Thirty-six explicit Forge `IMessage` classes and common registrations; IDs 25–31 add server-authoritative radio search, source selection, state, and PCM relay, while ID 35 carries finite-track timing only. |
| Java 11 HTTP/process helpers | Reimplemented | `HttpURLConnection`, Java 8 stream handling, and embedded media decoders. |
| Live radio | Reimplemented | Server-only Radio Browser lookup and embedded Java PCM relay; client-side `SourceDataLine` adapter. |
| Items and blocks | Omitted | None exist in the active source. |
| Recipes/GT machines | Omitted | No crafting, smelting, GregTech, or MineTweaker recipes exist. |
| TileEntities/NBT persistence | Omitted | No world object or persistent playlist exists. |
| World/entity/item rendering | Omitted | Only the 2D GUI renders in the active source. |
| Mixins/coremod/access transformer | Omitted | The active behavior is event/API based; none are required. |
| Companion Node/Express service | Omitted | It is inactive historical architecture; the port is self-contained. |
| Thumbnail loading | Simplified/omitted | Search keeps thumbnail metadata, but the active GUI does not draw it. |
| GregTech dependency | Omitted | The mod is compatible by isolation, not by registering GT content. |

## No-world-content decisions

The port deliberately adds no items, blocks, recipes, machines, TileEntities,
NBT persistence, world renderers, entity renderers, mixins, coremods, or access
transformers. The only rendered surface is the client GUI; shared playlist and
chart state remain in services/configuration rather than world content.
