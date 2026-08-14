# Audio Candidate Fallback Design

## Goal

Make client-side YouTube audio downloads survive an unusable first audio candidate, especially progressive-decoder rejection of fragmented M4A, without implementing a fragmented-MP4 demuxer or changing Minecraft's OpenAL path.

## Scope

The change covers the finite YouTube download path used by `AudioDownloadService`, `YouTubeStreamResolver`, and `JavaAudioDownloadBackend`.

It does not change `AudioPlayer`, Paulscode, LWJGL, OpenAL, Minecraft resource reloads, or the radio PCM path.

## Design

`YouTubeStreamResolver` will retain the current deterministic ordering (WebM/Opus, M4A, AAC, then lower-priority supported formats) but expose a `ResolvedAudioCandidates` value. The value contains the candidates from the primary Android VR InnerTube response and a lazy, one-shot resolver for the iOS fallback profile. The iOS request is therefore made only after the primary candidates have all failed for candidate-local media reasons.

The existing `resolveAudio` method remains available and returns the first primary candidate for compatibility. The new candidate value deduplicates identical media URLs and excludes expired candidates before publication.

`JavaAudioDownloadBackend` will attempt each candidate at most once per resolution cycle. Decoder, container, format-mismatch, and complete-body validation failures are candidate-local and advance the loop. Cancellation, output publication failures, and other non-media failures terminate the operation. A single fresh resolution is retained for the existing transient initial HTTP failure behavior; it cannot recursively restart the candidate loop.

Every candidate attempt owns its own `WavFileSink`. The existing temporary-file and atomic-publication behavior remains the transaction boundary, so a failed candidate cannot replace a valid cache entry or leave a final partial WAV.

If all candidate attempts fail, the backend throws one aggregate `MediaException` with the individual candidate failures suppressed. The existing `AudioDownloadService` failure eviction is not changed in this first patch; broader negative-cache backoff can be added separately after observing retry frequency.

## Non-goals

- No `moof`/`traf`/`trun` fragmented-MP4 decoder.
- No removal or weakening of `IsoBmffPreflight`.
- No integration with Minecraft's SoundSystem/OpenAL.
- No retry loop based on exception-message matching.

## Verification

Tests will cover ordered candidates, lazy alternate-profile resolution, fallback from a fragmented-M4A fixture to a usable candidate, aggregate failure, cancellation, and preservation of the existing atomic WAV behavior. Existing media regression tests must continue to pass.
