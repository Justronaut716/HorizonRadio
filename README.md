# HorizonRadio for Forge 1.7.10

HorizonRadio is a server-authoritative, shared YouTube music player and live
radio relay for Minecraft 1.7.10. This repository is a Forge port that runs in standalone Forge
installations and alongside GregTech: New Horizons (GTNH). It has no required
GTNHLib or GregTech dependency and adds no world content.

## Features

- Shared server playlist with add/remove ownership checks.
- Drag-and-drop reordering of queued songs in the Playlist tab.
- Separate Charts tab with server-cached weekly Top-50 results for all ISO
  countries, with multilingual region search and bulk queueing.
- YouTube search through the server-side InnerTube request.
- Empty Charts tab until a country is searched; country aliases and ISO codes
  can be searched in multiple languages.
- YouTube playlist import through the embedded Java metadata resolver.
- Client-local embedded Java decoding, normalization, WAV download, and cache
  for finite YouTube audio; the server sends only playback control and video IDs.
- Server-side Radio Browser directory search and station lookup; clients receive
  station UUIDs and names, never station stream URLs.
- Shared live-radio playback: the server decodes direct station streams through
  the embedded Java backend and clients play normalized PCM through Java Sound.
- Forge `SimpleNetworkWrapper` synchronization, including a three-second
  client-local start window and late-client catch-up from the shared server clock.
- 300x285 client GUI with Charts, Search, Playlist, and Radio tabs; scrolling,
  shared remove access, queue toggle buttons, progress, and volume control.
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
- The server needs outbound HTTP(S) access to YouTube's InnerTube metadata
  endpoints, Radio Browser directory mirrors, and the selected station's direct
  HTTP(S) stream. Each client needs outbound HTTP(S) access to fetch finite
  YouTube audio locally. The mod JAR includes its Java media decoders; no separately
  installed media program or native media library is required.
- A usable Java Sound line on each client for audible playback. The client
  remains usable when a sound device is unavailable, but playback cannot work.

### Embedded media backend

YouTube finite audio, YouTube metadata, and direct radio decoding run in the
embedded Java media backend. The decoder registry supports MP3, ADTS/AAC,
WAV/PCM, M4A/MP4 AAC, Ogg Vorbis, Ogg Opus, and WebM/Opus. The direct radio
acceptance path covers MP3, ADTS/AAC, Ogg Vorbis, and Ogg Opus. Finite audio is
normalized to cached WAV; live radio is normalized to 44.1 kHz stereo signed
16-bit little-endian PCM.

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
   Finite-track WAV files are cached per client in `config/horizonradio-audio`;
   the server-side `downloadDir` remains for compatibility and metadata-related
   services.
4. Keep the server and every client on the same Forge 1.7.10 port build. The
   current protocol uses the versioned `horizonradio_1_0` channel.

## 1.0 migration boundary

This is a breaking boundary. Pre-1.0 clients cannot connect after the channel
change, and old configurations are not automatically reinterpreted. Before
upgrading, back up `config/horizonradio.json`,
`config/horizonradio-charts.json`, and the configured download directory.

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
`maxTrackDurationMinutes` limits YouTube search results to songs shorter than
the configured number of minutes. YouTube metadata and finite audio use the
embedded Java resolver/backend. The legacy cookie fields remain readable for
configuration compatibility, but the embedded resolver does not use them; they
can be left empty.
`serverDebugChat` is disabled by default; set it to `true` only to mirror
server diagnostics into Minecraft chat.

The JSON above is the server/common configuration. Client audio settings and
the finite-track cache are kept separately: the volume slider stores its value
in `config/horizonradio-client.json`, while downloaded WAV files live in
`config/horizonradio-audio` and are reused across rejoins.

## Use

Join a server or load a client world with a player, then press `N` to open the
HorizonRadio screen. The Charts tab starts empty. Its search field accepts ISO
codes and country/region names such as `Germany`, `Deutschland`, `America`, or
`Amerika`; the last successfully searched country remains selected when the
tab is revisited. Charts are loaded and cached on the server for seven days per
region. `Refresh` updates the selected region, and the `+`/`-` button adds or
removes all displayed chart songs. Unknown or ambiguous region names keep the
current list and show an error. Search remains separate. Each search or chart
result also has a queue button. The server still allows every player to remove
entries.
The volume slider is local to the client and persists in
`config/horizonradio-client.json` across restarts and rejoins. The server
controls ordering, playback position, and late-join synchronization.

The Radio tab initially shows popular working Radio Browser stations and uses
the same field to search by station name. Selecting a station asks the server to
look it up again by UUID; the station stream URL never leaves the server. Radio
is live rather than seekable: its standard control center remains visible, but
shuffle, previous, next, and repeat are disabled. The center play/stop button
ends the current radio and starts the same station again when pressed once more.
The station name remains visible while stopped. Starting radio stops
finite-track playback without changing the queue, and Play Now stops radio and
starts the selected song.

For GUI verification, the client must already have a loaded world and a
non-null player (`Minecraft.getMinecraft().theWorld != null` and
`Minecraft.getMinecraft().thePlayer != null`). The title screen and a
player-null client are not valid GUI test states.

## Architecture

The server owns playlist/search/control/radio state. For finite tracks it sends
only a video ID, track generation, absolute start timestamp, and position in a
small `TrackSyncPacket`. Clients download and decode their own cached WAV file;
the server does not distribute finite audio bytes. A track is announced three
seconds before its shared start, and a client that finishes later seeks forward
to the current server-clock position. Direct radio is decoded by
`RadioInputSession` into 44.1 kHz stereo signed 16-bit little-endian PCM and
relayed in bounded 30 KiB chunks. The current source registers 36 Forge messages once from common
initialization: IDs 0-3, 10-15, 17, 19-27, and 33 are C2S with server handlers,
and IDs 4-9, 16, 18, 28-32, 34, and 35 are S2C with common server-safe
forwarding handlers.
Weekly chart metadata is cached in memory and persisted as
`config/horizonradio-charts.json` for seven days per canonical region. Existing
single-region cache files are treated as German (`DE`) data during migration,
never as Global data. Cached durations are reused when chart entries are added
to the queue, avoiding another metadata HTTP lookup. The
`ClientProxy` schedules the actual GUI/audio mutations and local download
completion on the client thread; common, server, and network code never imports
client classes.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the packet table,
audio state machine, port decisions, and omitted systems.

## Known limitations

YouTube's InnerTube endpoint, the Radio Browser directory, and selected station
streams are external services and may change or be unreachable. Every client
must be able to reach YouTube for finite audio; the server is intentionally not
a proxy for that traffic. WAV conversion and live PCM chunks are
bandwidth- and memory-intensive locally. Clients still require a usable Java
Sound line; a headless client or unavailable audio device cannot produce
audible playback. The playlist is in memory and is not persisted to NBT or a
database. Search thumbnails remain data-only because the active GUI does not
render them.
