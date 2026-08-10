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
- YouTube playlist import through server-side `yt-dlp` metadata extraction.
- Server-side `yt-dlp`/`ffmpeg` WAV download and cache.
- Server-side Radio Browser directory search and station lookup; clients receive
  station UUIDs and names, never station stream URLs.
- Shared live-radio playback: the server relays FFmpeg PCM and clients play it
  through Java Sound.
- Forge `SimpleNetworkWrapper` synchronization, including late-join pause,
  chunk transfer, ready, and resume messages.
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
- `yt-dlp` and `ffmpeg` available on the server `PATH`. They are external
  prerequisites and are not bundled in the JAR. `ffmpeg` is also required for
  live radio, and the server needs outbound HTTPS access to Radio Browser
  directory mirrors and the selected station's HTTP(S) stream.
- A usable Java Sound line on each client for audible playback. The client
  remains usable when a sound device is unavailable, but playback cannot work.

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
3. Install `yt-dlp` and `ffmpeg` on the server and verify both commands are on
   its `PATH`.
4. Start the server once. HorizonRadio creates `config/horizonradio.json` defaults and
   uses `./horizonradio-downloads` for cached WAV files unless configured otherwise.
5. Keep the server and every client on the same Forge 1.7.10 port build. The
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
  "youtubeCookiesFile": ""
}
```

`maxPlaylistSize` limits the total number of entries in the shared playlist;
additional songs are rejected once the configured maximum is reached.
`maxTrackDurationMinutes` limits YouTube search results to songs shorter than
the configured number of minutes. HorizonRadio uses yt-dlp's Android client
by default; if YouTube still requests a login,
set either `youtubeCookiesFromBrowser` (for example `chrome` or `firefox`) or
`youtubeCookiesFile` to an exported Netscape-format cookie file, then restart
the server.

The JSON above is the server/common configuration. Client audio settings are
kept separately: the volume slider stores its value in
`config/horizonradio-client.json` and restores it when the game starts again.

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

The server owns playlist/search/download/radio state. Finite tracks use the
existing cached-WAV chunk flow; live radio is decoded by server FFmpeg into
44.1 kHz stereo signed 16-bit little-endian PCM and relayed in bounded 30 KiB
chunks. The current source registers 32 Forge messages once from common
initialization: IDs 0-3, 10-15, 17, and 19-27 are C2S with server handlers, and
IDs 4-9, 16, 18, and 28-31 are S2C with common server-safe forwarding handlers.
Weekly chart metadata is cached in memory and persisted as
`config/horizonradio-charts.json` for seven days per canonical region. Existing
single-region cache files are treated as German (`DE`) data during migration,
never as Global data. Cached durations are reused when chart entries are added
to the queue, avoiding another `yt-dlp` lookup. The
server preloads the next and previous audio files while a finite track plays.
`ClientProxy` schedules the actual GUI/audio mutations on the client thread;
common, server, and network code never imports client classes.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the packet table,
audio state machine, port decisions, and omitted systems.

## Known limitations

YouTube's InnerTube endpoint and the Radio Browser directory are external
services and may change or be unreachable. WAV and live PCM chunks are
bandwidth- and memory-intensive. `yt-dlp`/`ffmpeg` and Java Sound depend on the
host environment. The playlist is in memory and is not persisted to NBT or a
database. Search thumbnails remain data-only because the active GUI does not
render them.
