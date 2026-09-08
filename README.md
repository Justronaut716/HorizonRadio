# HorizonRadio for Forge 1.7.10

HorizonRadio is a server-authoritative shared queue for client-side YouTube
music and live radio playback in Minecraft 1.7.10. This repository is a Forge
port that runs in standalone Forge installations and alongside GregTech: New
Horizons (GTNH). It has no required GTNHLib or GregTech dependency and adds no
world content.

## Features

- Shared server playlist with add/remove ownership checks.
- Drag-and-drop reordering of queued songs in the Playlist tab.
- Separate Charts tab with client-local weekly Top-50 discovery for all ISO
  countries, multilingual region search, and bulk queueing.
- Client-side YouTube search, chart lookup, playlist/video import, metadata
  resolution, download, decoding, normalization, WAV cache, and playback.
- Empty Charts tab until a country is searched; country aliases and ISO codes
  can be searched in multiple languages.
- Queue mutations send a source ID and a positive finite duration only; the
  server validates and orders them without looking up YouTube metadata.
- Client-side Radio Browser search and station lookup; each client opens the
  selected station's live stream directly.
- Forge `SimpleNetworkWrapper` snapshots/deltas with queue revisions and
  source-aware playback synchronization. Finite YouTube tracks use the shared
  server clock; radio carries station UUID plus generation and starts at the
  local live edge.
- WebPrototype-aligned, responsive two-column client GUI with Songs/Radio tabs,
  Charts/Search/Playlists modes, a persistent queue, scrolling, drag reorder,
  playback controls, progress, and volume control. The prototype's Client,
  Server, and Group scope controls are intentionally omitted for now.
- Client-local Java Sound playback.

There are deliberately no items, blocks, crafting or machine recipes,
TileEntities, world renderers, mixins, coremods, or GregTech recipe
integrations. The inactive companion service is not part of this port.

## Requirements

- Java 25 for development and release builds. This is a build requirement,
  not the game runtime requirement.
- Ordinary Forge 1.7.10 targets a Java 8-compatible runtime. GTNH targets Java
  17 or newer; runtime smoke tests remain pending.
- Minecraft 1.7.10 with Forge `10.13.4.1614`.
- Either standalone Forge 1.7.10 or a GTNH installation for deployment. The
  same plain reobfuscated JAR works for both, with no hard GTNHLib or GregTech
  dependency.
- The server needs no outbound access to YouTube, Radio Browser, or station
  streams for normal operation. Each client needs outbound HTTP(S) access to
  YouTube, Radio Browser, and the selected station stream. The mod JAR includes
  its Java media decoders; no separately installed media program or native media
  library is required.
- A usable Java Sound line on each client for audible playback. The client
  remains usable when a sound device is unavailable, but playback cannot work.

### Embedded media backend

YouTube discovery/metadata, finite audio, Radio Browser lookup, and direct
radio decoding run in the embedded Java media backend on each client. The
decoder registry supports MP3, ADTS/AAC,
WAV/PCM, M4A/MP4 AAC, Ogg Vorbis, Ogg Opus, and WebM/Opus. The direct radio
acceptance path covers MP3, ADTS/AAC, Ogg Vorbis, and Ogg Opus. Finite audio is
normalized to cached WAV; each client opens live radio directly at its current
edge.

HLS/M3U8 was intentionally not added because the current acceptance corpus has
no concrete required HLS URL. Direct HTTP radio remains the default path.

## Build

The project uses the GTNH convention build and its Gradle `9.3.1` wrapper.
With Java 25 active, the normal development build is:

```bash
./gradlew build
```

The `build` lifecycle task runs the checks/tests and assembles the JAR. The
repository’s `.java-version` file contains `25`; if your shell does not load
that file automatically, select a Java 25 installation before invoking the
wrapper. Use `./gradlew clean` separately only when you intentionally need a
fresh Forge/decompilation build. CI keeps that clean step separate from
`test build` because first-time parallel dependency downloads write under
`build/`.

The convention build owns Forge setup, resource expansion, and the Java 8
portable-runtime compatibility path. See
[`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) for the verified local build
and pending runtime matrix.

For version bumps, remote releases, artifact selection, checksums, and recovery,
see the [`release guide`](docs/RELEASE.md).

## Installation

1. For a local development build, run `./gradlew build` with Java 25. For a
   versioned release, use the process in [`docs/RELEASE.md`](docs/RELEASE.md);
   it sets `VERSION=<version>` and publishes the plain reobfuscated
   `horizonradio-<version>.jar`.
2. Install that same `horizonradio-<version>.jar` in the Forge `mods` directory
   on the server and every connecting client. Do not install `-dev` or
   `-sources` outputs. This one JAR is for ordinary Forge 1.7.10 and GTNH
   Java 17+; Java 25 is only required to build it.
3. Start the server once. HorizonRadio creates `config/horizonradio.json` defaults.
   Finite-track WAV files are cached per client in `horizonradio-audio` at the
   game root (`.minecraft`); the server-side `downloadDir` remains for
   configuration compatibility only.
4. Keep the server and every client on the same Forge 1.7.10 port build. The
   current protocol uses the versioned `horizonradio_1_0` channel.

## 1.0 migration boundary

This is a breaking boundary. Pre-1.0 clients cannot connect after the channel
change, and old configurations are not automatically reinterpreted. Before
upgrading, back up `config/horizonradio.json` and the configured download
directory.

Example configuration:

```json
{
  "maxPlaylistSize": 50,
  "maxTrackDurationMinutes": 15,
  "downloadDir": "./horizonradio-downloads",
  "youtubeCookiesFromBrowser": "",
  "youtubeCookiesFile": "",
  "serverDebugChat": false
}
```

`maxPlaylistSize` limits the total number of entries in the shared playlist;
additional songs are rejected once the configured maximum is reached.
`maxTrackDurationMinutes` limits the finite duration that the server accepts
for a queue mutation; clients use the same bound for local discovery. YouTube
metadata and finite audio use the client-local embedded Java resolver/backend.
The legacy cookie fields remain readable for configuration compatibility, but
the embedded resolver does not use them; they can be left empty.
`serverDebugChat` is disabled by default; set it to `true` only to mirror
server diagnostics into Minecraft chat.

The JSON above is the server/common configuration. Client audio settings and
the finite-track cache are kept separately: the volume slider stores its value
in `config/horizonradio-client.json`, while downloaded WAV files live in
`horizonradio-audio` at the game root. The cache is session-scoped: it is
deleted in full when the client starts and when it exits, and while playing it
is pruned to the playback window - the current track, the last two finished
tracks, and the next two queued tracks - so skipping or going back still finds
cached audio.

## Use

Join a server or load a client world with a player, then press `N` to open the
HorizonRadio screen. The Charts tab starts empty. Its search field accepts ISO
codes and country/region names such as `Germany`, `Deutschland`, `America`, or
`Amerika`; the last successfully searched country remains selected when the
tab is revisited. Charts, search, and imports run directly on the client; their
titles, channels, thumbnails, and durations remain local. `Refresh` updates
the selected region, and the `+`/`-` button adds or removes all displayed chart
songs. A finite add sends only a video ID and positive duration to the server.
Unknown or ambiguous region names keep the current list and show an error.
Search remains separate. The server still allows every player to remove entries.
The volume slider is local to the client and persists in
`config/horizonradio-client.json` across restarts and rejoins. The server
controls queue ordering and finite-track playback synchronization.

The Radio tab initially shows popular working Radio Browser stations and uses
the same field to search by station name. Selecting a station sends only its
UUID to the server; every client looks up the UUID and opens the station stream
locally. Radio is live rather than seekable: the synchronization packet contains
only the station UUID and playback generation, with no `startAt`, position, or
late-join catch-up. It starts at each client's current live edge. Its standard
control center remains visible, but shuffle, previous, next, repeat, pause, and
seek are unavailable. The center play/stop button ends the current radio and
starts the same station again when pressed once more. The station name remains
visible while stopped. Starting radio stops finite-track playback without
changing the queue, and Play Now stops radio and starts the selected song.

For GUI verification, the client must already have a loaded world and a
non-null player (`Minecraft.getMinecraft().theWorld != null` and
`Minecraft.getMinecraft().thePlayer != null`). The title screen and a
player-null client are not valid GUI test states.

## Architecture

The server owns queue order, accepted mutations, playback generation, controls,
and finite-track timing. It receives source IDs plus positive finite durations
for finite mutations, sends revisioned snapshots/deltas containing source type,
source ID, and adder only, and sends source-aware sync. For a finite track,
`TrackSyncPacket` contains the video ID, generation, absolute start timestamp,
position, and pause state; clients download/decode their cached WAV locally and
late clients catch up from the shared server clock. For radio it contains only
station UUID plus generation, so every client joins its own live edge. The
server performs no YouTube/Radio Browser lookup and relays no metadata or audio.

The current source registers 24 Forge messages once from common initialization:
16 C2S and 8 S2C, including clientbound `PlaylistDeltaPacket` ID 36 and
serverbound `PlaylistResyncRequestPacket` ID 37. Legacy result and relay packet
serializers remain only for compatibility tests; they are not production
registrations. `ClientProxy` schedules GUI/audio mutations and local discovery
completion on the client thread; common, server, and network code never imports
client classes.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the packet table,
audio state machine, port decisions, and omitted systems.

## Known limitations

YouTube's InnerTube endpoint, the Radio Browser directory, and selected station
streams are external services and may change or be unreachable. Every client
must be able to reach the services it uses; the server is intentionally not a
proxy. Finite WAV conversion is bandwidth- and memory-intensive locally.
Independent radio connections cannot be sample-accurately synchronized: clients
join the live edge without a position or catch-up. Clients still require a
usable Java Sound line; a headless client or unavailable audio device cannot
produce audible playback. The playlist is in memory and is not persisted to NBT
or a database. Search thumbnails remain data-only because the active GUI does
not render them.
