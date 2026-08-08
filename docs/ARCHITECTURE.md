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
| `com.horizonradio.network` and `.network.packets` | Forge `SimpleNetworkWrapper`, handlers, codecs, and the 25 current-source packet types, including the preserved contract below. |
| `com.horizonradio.server` | Forge/server-facing playlist manager, events, download, YouTube, and server-thread services. |
| `com.horizonradio.client` and `.client.audio` | Client proxy, GUI, keybinds, client transport, and Java Sound playback. |

## Forge message contract (current source: IDs 0–24)

The channel is `horizonradio_1_0`. IDs and field order are stable within the
1.0 port. The current source contract is:

| ID | Direction | Packet | Fields |
|---:|:---:|---|---|
| 0 | C2S | `SearchRequestPacket` | query |
| 1 | C2S | `AddToPlaylistPacket` | video ID, title, duration |
| 2 | C2S | `RemoveFromPlaylistPacket` | video ID |
| 3 | C2S | `ReadyPacket` | video ID |
| 4 | S2C | `SearchResultsPacket` | count + five strings per result |
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
| 21 | C2S | `RequestChartsPacket` | no fields |
| 22 | C2S | `AddChartsToPlaylistPacket` | remove flag, count + video ID, title, duration per entry |
| 23 | C2S | `ClearPlaylistPacket` | no fields |
| 24 | C2S | `PlayNowPacket` | video ID, title, duration |

The C2S handlers obtain the player, schedule each packet request on the server
thread, and delegate to `PlaylistManager`. There, request validation,
authoritative `PlaylistState` mutation, and broadcasts to clients occur.
`AddChartsToPlaylistPacket` writes a leading remove boolean, then the entry
count and each entry's video ID, title, and duration; a false flag adds valid
chart entries and a true flag removes the matching chart entries.
`ClearPlaylistPacket` requests that `PlaylistManager` clear the playlist, and
`PlayNowPacket` requests immediate selection of the supplied video ID, title,
and duration while preserving the server-owned queue and playback state.

All strings, collection counts, indexes, and byte arrays are bounded before
allocation. Audio data remains split at 30 KiB. `startOffsetMs == -1` means a
late-join client should load the clip without starting it.

## Audio state machine

`AudioChunkAssembler` is Java-8-pure and rejects invalid indexes, mismatched
track metadata, out-of-order chunks received before chunk zero, duplicates, and
oversized chunks. A new chunk zero clears older in-flight video IDs. Completed
chunks are assembled in index order and buffers are cleared.

`AudioPlayer` then uses one daemon executor:

1. Assemble a track on the client/network callback boundary.
2. Decode through `AudioSystem.getAudioInputStream` and convert to signed
   16-bit PCM when necessary.
3. Open one `Clip`, seek to the server offset, apply bounded `MASTER_GAIN`,
   and start it, or hold it for late-join resume.
4. Send `ReadyPacket` through `HorizonRadioClient.ClientTransport` after late-join
   loading, using the Forge client transport boundary.
5. Pause/resume/stop/close through the same executor and generation/lock
   guards. Natural completion closes the line; disconnect clears the client
   cache and stops playback.

`AudioPlayerState` supplies a sound-device-independent model for state tests;
the real `Clip` remains optional at test time.

## Server authority rules

The server owns the shared playlist, ordering, current track, playback position,
pause/resume, loop/shuffle state, chart/search results, downloads, and audio
cache. Clients send intent packets; server handlers validate and apply those
requests, then broadcast authoritative state. A client may remove entries as
allowed by the active server rules, but it cannot directly change shared state.
Volume is client-local, and Java Sound playback is an adapter for server-directed
audio rather than a second source of truth.

## GUI and input

`HorizonRadioScreen` preserves the active 300x285 immediate-mode panel: search and
playlist tabs, six visible rows, scroll offsets, shared `X` removal, drag-and-drop
reordering for queued entries, empty states, now-playing/progress bar, and local
volume control. The currently playing entry remains fixed at the head of the
queue. The port uses Forge 1.7.10
`GuiScreen`, `GuiButton`, `GuiTextField`, LWJGL mouse/keyboard input, and
`drawRect`/`drawString`. No thumbnail HTTP or texture pipeline was added.

The `N` key opens the GUI only when `Minecraft.theWorld` and
`Minecraft.thePlayer` are both non-null. A title-screen test is intentionally
not accepted as GUI evidence.

## Preserved, reimplemented, and omitted systems

| Source feature | Port status | Decision |
|---|---|---|
| Playlist/search/import/download/playback | Reimplemented | Forge events, Java 8 services, server-side `yt-dlp` metadata extraction, and SimpleNetworkWrapper preserve behavior. |
| JSON config | Preserved | `config/horizonradio.json` keeps `downloadDir` and `maxPlaylistSize`; `maxTrackDurationMinutes` filters search results server-side. |
| GUI and N key | Reimplemented | Same geometry and interaction, Forge 1.7.10 classes. |
| Legacy payloads/receivers | Reimplemented | Twenty-five explicit Forge `IMessage` classes and common registrations in the current source; the preserved baseline table above covers IDs 0–21. |
| Java 11 HTTP/process helpers | Reimplemented | `HttpURLConnection`, Java 8 stream/process handling. |
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
