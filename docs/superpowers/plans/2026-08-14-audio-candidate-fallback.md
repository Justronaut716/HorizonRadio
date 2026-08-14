# Audio Candidate Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add bounded, decoder-aware YouTube audio candidate fallback for fragmented-M4A failures while preserving atomic WAV publication and leaving OpenAL untouched.

**Architecture:** The resolver returns primary candidates plus a lazy one-shot alternate InnerTube profile. The download backend owns the bounded candidate loop because it is the first layer that knows whether a stream actually decodes. Each attempt uses its own existing atomic WAV sink; candidate-local failures are aggregated instead of recursively retrying the same stream.

**Tech Stack:** Java 8 source compatibility, Forge 1.7.10, JUnit 4, existing `YouTubeMediaModels`, JAAD decoders, `WavFileSink`, Gradle test task.

## Global Constraints

- Do not implement fragmented-MP4 demuxing.
- Do not change Minecraft SoundManager, Paulscode, LWJGL, OpenAL, or Java Sound playback lifecycle.
- Keep `resolveAudio(String)` source-compatible for existing callers.
- Preserve temporary-file plus atomic WAV publication semantics.
- Do not classify failures by matching exception-message text.

---

### Task 1: Resolver candidate value and ordering

**Files:**
- Modify: `src/main/java/com/horizonradio/server/media/YouTubeStreamResolver.java`
- Test: `src/test/java/com/horizonradio/server/media/YouTubeStreamResolverTest.java`

**Interfaces:**
- Produces `YouTubeStreamResolver.ResolvedAudioCandidates resolveAudioCandidates(String videoId)`.
- `ResolvedAudioCandidates.getPrimaryCandidates()` returns an immutable `List<YouTubeMediaModels.ResolvedAudioStream>`.
- `ResolvedAudioCandidates.resolveAlternativeCandidates()` performs at most one lazy iOS-profile resolution.
- Existing `resolveAudio(String)` returns the first primary candidate.

- [ ] **Step 1: Add a failing test for ordered primary candidates and lazy alternate resolution.**

  Use the existing `FakeHttp`: make the Android response contain M4A and AAC candidates, configure `iosResponse` with a WebM candidate, assert primary order, assert only one player request before lazy resolution, then assert the iOS candidate appears after the second request.

- [ ] **Step 2: Run the resolver test and verify it fails because the candidate API is absent.**

  Run: `./gradlew test --tests com.horizonradio.server.media.YouTubeStreamResolverTest`

  Expected: compilation failure referencing the missing candidate API.

- [ ] **Step 3: Implement the candidate value and refactor selection to return all supported, unexpired, deduplicated candidates.**

  Keep the existing preference comparator. Convert `select` and `resolveAudioWithClient` to return immutable lists, retain the current Android-to-iOS fallback when Android is unavailable, and attach a lazy iOS resolver when Android succeeds.

- [ ] **Step 4: Run the resolver tests and verify they pass.**

  Run: `./gradlew test --tests com.horizonradio.server.media.YouTubeStreamResolverTest`

- [ ] **Step 5: Commit the resolver slice.**

  Run: `git add src/main/java/com/horizonradio/server/media/YouTubeStreamResolver.java src/test/java/com/horizonradio/server/media/YouTubeStreamResolverTest.java && git commit -m "feat: expose ordered YouTube audio candidates"`

### Task 2: Decoder-aware backend fallback

**Files:**
- Modify: `src/main/java/com/horizonradio/server/media/JavaAudioDownloadBackend.java`
- Test: `src/test/java/com/horizonradio/server/media/JavaAudioDownloadBackendTest.java`

**Interfaces:**
- Consumes `ResolvedAudioCandidates` from Task 1.
- Produces a successful destination path after the first decodable candidate.
- Throws one aggregate `MediaException` after bounded candidate-local failures.

- [ ] **Step 1: Add a failing same-response fallback test.**

  Add a deterministic HTTP fixture whose first URL returns a minimal ISO-BMFF body containing `moof`, while the second URL returns a valid WAV. Assert the backend publishes the WAV, makes two media requests, and leaves no `.part-*` files.

- [ ] **Step 2: Run the focused backend test and verify it fails because the backend still calls only `resolveAudio`.**

  Run: `./gradlew test --tests com.horizonradio.server.media.JavaAudioDownloadBackendTest --tests com.horizonradio.server.media.WavFileSinkTest`

- [ ] **Step 3: Implement a bounded candidate loop.**

  Extract one-stream download/decode into a helper. Wrap only detector/decoder/full-body validation failures as an internal candidate-local exception. Keep cancellation and sink publication errors outside that wrapper. Track attempted URLs, allow one fresh resolution after the existing initial transport failure, and then try the lazy alternate profile only after all primary media attempts fail.

- [ ] **Step 4: Add aggregate-failure and cancellation assertions.**

  Assert all candidate-local causes are suppressed on the final `MediaException`, and assert a cancelled token stops before another candidate is opened.

- [ ] **Step 5: Run focused backend tests and verify they pass.**

  Run: `./gradlew test --tests com.horizonradio.server.media.JavaAudioDownloadBackendTest --tests com.horizonradio.server.media.WavFileSinkTest --tests com.horizonradio.server.media.Task3ReviewRegressionTest`

- [ ] **Step 6: Commit the backend slice.**

  Run: `git add src/main/java/com/horizonradio/server/media/JavaAudioDownloadBackend.java src/test/java/com/horizonradio/server/media/JavaAudioDownloadBackendTest.java && git commit -m "fix: fall back across unusable audio candidates"`

### Task 3: Full verification and handoff

**Files:**
- Modify: none unless verification exposes a regression.

- [ ] **Step 1: Run the complete test suite.**

  Run: `./gradlew test`

- [ ] **Step 2: Inspect the diff and verify OpenAL/client audio files are unchanged.**

  Run: `git diff --stat HEAD~2..HEAD` and `git diff HEAD~2..HEAD -- src/main/java/com/horizonradio/client/audio src/main/java/com/horizonradio/client/ClientProxy.java`

- [ ] **Step 3: Check repository status and report exact verification results.**

  Run: `git status --short --branch`
