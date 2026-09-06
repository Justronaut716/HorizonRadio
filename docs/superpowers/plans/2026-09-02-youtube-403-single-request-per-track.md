# YouTube media transfer hardening

**Status:** Implemented and verified in commit `8f5fdbc` (amended below).

## Root cause

The Java backend made an un-ranged request to the signed `googlevideo.com` URL. YouTube returned HTTP 200 but throttled the body to approximately 28 KiB/s, causing the smoke test to appear stuck. yt-dlp uses bounded HTTP range requests instead.

The investigation also found that the backend was sending an Android-VR User-Agent and visitor header while the resolver used the VisionOS client. Finally, the 22-minute test stream exceeded the native WebM and WAV output limits, causing misleading fallback 403 messages after the successful media transfer.

## Implementation

- Use 10 MiB HTTP range requests for fresh and resumed media transfers, matching yt-dlp's YouTube downloader behavior.
- Preserve the selected resolver client User-Agent in `ResolvedAudioStream` and use it for media requests.
- Keep `X-Goog-Visitor-Id` on InnerTube requests but omit it from signed media requests.
- Prefer IPv6 consistently for IP-bound YouTube URLs, while allowing IPv4 fallback on dual-stack systems.
- Increase bounded WebM input and Opus packet limits for longer tracks.
- Increase the finite default WAV/output budget to 384 MiB.
- Improve the standalone smoke test with phase output, watchdog thread dumps, full exception chains, and explicit timeout reporting.

## Verification

```bash
./gradlew spotlessCheck test packagingTest build --no-daemon
scripts/test-youtube-audio.sh M7lc1UVf-VE
```

The full Gradle validation passed. The live smoke test passed without extra JVM flags:

```text
PASS M7lc1UVf-VE -> 237,019,180 bytes
```
