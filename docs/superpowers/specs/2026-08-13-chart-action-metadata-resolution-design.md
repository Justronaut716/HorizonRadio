# Chart Action Metadata Resolution Design

## Goal

Allow chart results whose discovery response has no duration to be added to the
shared playlist or played immediately without server-side media lookups.

## Design

Chart discovery remains lightweight and may return an empty duration. When the
user invokes a chart action, the client first uses the existing
`ClientMetadataCache`/`ClientMediaService.resolveVideo` path to obtain the
video's finite duration. The client sends the compact `videoId + durationMs`
selection only after resolution succeeds. Already-resolved chart results skip
the lookup.

Bulk additions resolve missing durations concurrently but preserve the input
order when constructing the one chart packet. Failed resolutions are omitted,
their pending UI state is cleared, and the client writes a useful debug message
to the Minecraft chat. Successful resolutions update the local chart cache so
later actions do not repeat the lookup. Direct playback uses the same resolver
and sends `PlayNowPacket` only after a valid duration is available.

The server protocol, queue validation, audio pipeline, and chart discovery
request remain unchanged. No audio data or chart metadata is sent through the
server.

## Testing

Add client tests covering successful lazy chart resolution for add and direct
play, preserving bulk order, failed resolution cleanup, and the existing fast
path for results that already contain a duration. Run the focused client tests,
the full test suite, formatting, and the normal build verification.
