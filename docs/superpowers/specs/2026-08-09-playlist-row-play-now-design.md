# Queue-row immediate playback design

## Goal

When a player clicks a song row in the Playlist/queue tab, the currently
playing song is interrupted and the clicked song starts immediately. The
existing `X` removal control, scrolling, and drag-and-drop reordering must keep
their current behavior.

## Existing context

Search and chart result rows already call
`HorizonRadioClient.sendPlayNow(videoId, title, duration)`. The client sends the
existing `PlayNowPacket`, and `PlaylistManager.handlePlayNow` already performs
the server-authoritative immediate transition: it selects the requested queue
entry, makes it the active front entry, removes the previous active entry from
the active queue according to the existing semantics, synchronizes clients,
and starts the selected track. No new packet or server state is required.

Queue rows currently treat a left click as the start of a possible drag. The
release path only sends a reorder when movement occurred, so a normal click has
no action.

## Chosen interaction

The queue row continues to use the press/move/release gesture so clicking does
not break drag-and-drop:

1. On left-button press outside the row's `X` button, remember the playlist
   index, the immutable `PlaylistEntry` pressed, and the initial mouse
   position. This applies to the currently playing row as well; it may be
   clicked to restart it through the same `PlayNow` path.
2. Mark the gesture as moved if the mouse position changes while the button is
   held.
3. On left-button release, clear the pending gesture state.
   - If the gesture did not move and the pointer is still over the original
     row, send `PlayNow` with the remembered entry's video ID, title, and
     duration.
   - If the gesture moved, preserve the existing reorder validation and send
     `sendReorder(fromIndex, targetIndex)` only for an allowed, different
     target.
   - Otherwise do nothing.

The release-time entry snapshot prevents a playlist synchronization between
mouse press and release from changing which song a click refers to. The
existing current-entry drag restriction remains in force for reordering; it
does not prevent a click from playing the current entry.

## Data flow and boundaries

Only `HorizonRadioScreen` changes for the interaction. It calls the existing
client transport API, which serializes the existing `PlayNowPacket` to the
server. `PlaylistManager` keeps all validation, download cancellation,
playback reset, queue mutation, synchronization, and external dependency
handling unchanged. The client does not optimistically mutate its playlist or
now-playing cache; it waits for the authoritative server packets.

## Error handling

The client sends no request when the release is outside the pressed row, when
the row was dragged to an invalid target, or when the remove button was used.
Server-side validation of video ID, title, and duration remains the existing
source of truth. Invalid requests continue to produce the current server chat
message and do not alter playback.

## Tests

Add GUI interaction coverage in `GuiLayoutTest`:

- a press/release on a queue row sends the expected `playNowRequest` and does
  not send removal or reorder;
- a moved queue gesture still sends the expected reorder and does not send
  `PlayNow`;
- the current row can be clicked through the same path while its reorder
  restriction remains intact.

The existing transport and server `PlayNow` tests remain relevant and should
continue to pass without changes to the packet or server implementation.

## Acceptance criteria

- Clicking a queue song starts it through the existing immediate-playback
  behavior.
- The previous active song is interrupted according to existing
  `PlayNow` semantics.
- Drag-and-drop reorder behavior is unchanged.
- The queue `X` button, scrollbar, and other tabs are unaffected.
- Focused GUI tests and the full Gradle test suite pass.
