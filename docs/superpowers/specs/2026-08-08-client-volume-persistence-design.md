# Client-side volume persistence design

## Goal

Persist the HorizonRadio volume selected by a player on the client so that it
is restored after restarting the game and remains unchanged when the client
disconnects and joins another server or world.

The setting must remain client-local. It must not be sent to the server or be
added to the shared/server configuration.

## Storage

Store one JSON value in:

```text
config/horizonradio-client.json
```

The file has this shape:

```json
{
  "volume": 0.75
}
```

The existing `horizonradio.json` remains server/common configuration and is
not changed by this feature.

## Components and data flow

### `HorizonRadioClientConfig`

Add a small client-only persistence component under
`com.horizonradio.client`. It will:

- load the `volume` value from the dedicated file;
- use `1.0` when the directory/file/value is missing or malformed;
- clamp valid numeric values to the existing `0.0`–`1.0` volume range;
- write the value as UTF-8 JSON;
- write through a temporary file and replace the target, with a non-atomic
  move fallback for filesystems that do not support atomic moves;
- log I/O or parse failures and leave playback usable when persistence fails.

### Client startup

`ClientProxy.preInit` obtains the same configuration-directory parent already
provided by Forge, loads `HorizonRadioClientConfig`, and applies its volume to
the singleton `AudioPlayer` before the GUI can be opened.

### Volume changes

`HorizonRadioClient.setVolume` remains the single client API used by the
slider. It first applies the bounded value to `AudioPlayer`, then persists the
effective value when the client config has been initialized. This makes the
setting durable immediately, including while the slider is dragged.

### Reconnect behavior

`HorizonRadioClient.clearCache` continues to clear server-owned playlist and
playback state and stop the current clip. It must not reset the volume. A new
screen reads the retained `AudioPlayer` value, and a new game process reloads
the value from `horizonradio-client.json` during client pre-initialization.

## Error handling and compatibility

The feature is best-effort persistence. A missing directory is created when a
write is attempted; if creation, writing, or replacement fails, the current
in-memory volume remains active and the client continues normally. Malformed
or invalid persisted data is ignored in favor of the default volume. Existing
configuration files are not migrated or rewritten.

## Tests

Add focused tests for:

1. missing and malformed client files returning the default volume;
2. saving a volume and loading the same value again;
3. clamping persisted values outside the supported range;
4. `HorizonRadioClient.setVolume` persisting through the dedicated file once
   client configuration is initialized;
5. `HorizonRadioClient.clearCache` preserving the selected volume.

The existing slider test remains the interaction-level check that dragging
updates the audio player's effective value.

## Scope

This change does not alter network packets, server playlist state, audio
decoding, GUI geometry, or Minecraft's global sound settings.
