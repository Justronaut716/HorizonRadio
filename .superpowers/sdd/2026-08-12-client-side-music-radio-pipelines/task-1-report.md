# Task 1 implementation report

## Changed files

- `src/main/java/com/horizonradio/core/model/MediaSourceType.java`
- `src/main/java/com/horizonradio/core/model/PlaylistEntry.java`
- `src/main/java/com/horizonradio/core/server/PlaylistState.java`
- `src/test/java/com/horizonradio/core/model/MediaSourceTypeTest.java`
- `src/test/java/com/horizonradio/core/server/PlaylistStateTest.java`

Pre-existing untracked documents were not staged or modified.

## Implementation

- Added source types `YOUTUBE` and `RADIO` with stable byte values `1` and `2` and strict decoding.
- Replaced playlist entry identity/duration state with source type, source ID, duration milliseconds, and added-by name.
- Added finite YouTube and live radio factories, validation, source predicates, equality, and source-based string output.
- Updated playlist current-track bookkeeping and lookup to use source type/source ID.
- Added radio start behavior with finite pause/seek controls unavailable for radio.
- Added monotonic queue revision tracking.
- Retained deprecated source-compatible adapters for existing protocol/server callers without storing title or formatted duration fields.

## Tests

1. `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.model.MediaSourceTypeTest --tests com.horizonradio.core.server.PlaylistStateTest`
   - Result: `BUILD SUCCESSFUL`; focused tests passed.
2. `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test`
   - Result: `BUILD SUCCESSFUL`; 387 tests completed, 2 skipped.
3. `git diff --check`
   - Result: passed with no whitespace errors.

## Commits

Implementation commit: `1e15f2f3bbe249de903b73556f8d31cd0e6444f5` (`refactor: model playlist sources explicitly`).

## Concerns

The deprecated accessors remain temporarily for callers that will be migrated by later protocol/client tasks. They derive compatibility values from the new source-aware fields and do not participate in equality or `toString()`.

## Fix round 1 report

### Changed files

- `src/main/java/com/horizonradio/core/model/PlaylistEntry.java`
- `src/main/java/com/horizonradio/core/server/PlaylistState.java`
- `src/test/java/com/horizonradio/core/server/PlaylistStateTest.java`

### Findings addressed

- Immediate playback and queue shuffle now each count as exactly one accepted queue mutation.
- Server queue insertion rejects finite entries with non-positive duration, and finite track start rejects non-positive duration.
- Added the required public `PlaylistEntry(MediaSourceType, String, long, String)` constructor and uses it in the radio-duration regression test.
- Legacy ownership removal now only matches YouTube entries.

### Exact verification commands and outputs

1. `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.model.MediaSourceTypeTest --tests com.horizonradio.core.server.PlaylistStateTest`
   - Output: `BUILD SUCCESSFUL in 4s`; `13 actionable tasks: 3 executed, 10 up-to-date`.
2. `git diff --check`
   - Output: no output; exit code `0`.

### Commit

- `c7bed3a3ead5edc651c11b50e42fc6b838a13d51` — `fix: address task 1 review findings`

### Concerns

- The focused covering tests pass. The full suite was not rerun for this review round; the server-side rejection intentionally changes behavior for unresolved finite entries, as required by the review and Task 1 semantics.

## Fix round 2 report

### Changed files

- `src/main/java/com/horizonradio/core/server/PlaylistState.java`
- `src/test/java/com/horizonradio/core/server/PlaylistStateTest.java`

### Finding addressed

Added server-side positive-duration validation to every finite queue insertion or promotion path: `addAtFront`, `prepareImmediatePlayback`, and `advanceToNext`, while retaining zero-duration construction for client-side ID-only projections. Added focused regressions for each bypass.

### Exact verification commands and outputs

1. `GRADLE_USER_HOME=/tmp/horizonradio-gradle ./gradlew test --tests com.horizonradio.core.model.MediaSourceTypeTest --tests com.horizonradio.core.server.PlaylistStateTest`
   - Output: `BUILD SUCCESSFUL in 2s`; `13 actionable tasks: 2 executed, 11 up-to-date`.
2. `git diff --check`
   - Output: no output; exit code `0`.

### Commit

- `ac88fc7df8ddb8cc6f0ea4146dfac160f51a4db2` — `fix: validate finite queue promotions`

### Concerns

- No remaining scoped concerns. Pre-existing untracked planning/spec documents remain untouched.
