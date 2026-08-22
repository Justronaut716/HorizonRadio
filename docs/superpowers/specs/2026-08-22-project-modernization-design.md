# HorizonRadio modernization

## Objective

Modernize HorizonRadio without changing its user-visible behavior or active
Forge wire protocol. The resulting codebase must be safer under hostile or
unreliable network conditions, easier to navigate, and simpler to extend.

The modernization removes compatibility-only production code that is not part
of the registered protocol. It preserves the server-authoritative shared queue,
client-local discovery and media playback, Forge 1.7.10 support, ordinary
Java-8-compatible runtime output, and Java 17+ GTNH operation. Java 25 remains
the development and release-build JDK.

## Current baseline

- `spotlessCheck` and the complete Gradle test task pass.
- The suite currently reports 530 tests, no failures or errors, and two skipped
  packaging tests.
- The active protocol registers 24 packet types. Their IDs, directions, and
  serialized fields remain unchanged.
- The largest maintenance risks are the global `HorizonRadioClient` facade,
  the multi-purpose `HorizonRadioScreen`, misleading client-media package
  names, GUI-owned presentation models, compatibility-only relay code, and
  unbounded asynchronous/network work.

## Scope and constraints

### Required outcomes

- Block radio connections to local, private, link-local, multicast, and
  unspecified network targets, including redirect targets.
- Bound HTTP response sizes, client I/O concurrency, server task queue growth,
  and work performed during one server tick.
- Keep asynchronous result publication generation-safe and cancellation-aware.
- Establish one-way package dependencies and transport-neutral state models.
- Split discovery, queue synchronization, playback, cache, and presentation
  responsibilities out of the two largest client classes.
- Remove non-registered relay packets, relay audio paths, obsolete overloads,
  and tests whose only purpose is preserving those obsolete APIs.
- Make packaging tests execute against the produced deployable JAR in CI.
- Make release automation validation-only: it must never format or stage
  unrelated tracked changes implicitly.
- Reconcile README, architecture, compatibility, and release documentation with
  the final implementation.

### Non-goals

- No GUI redesign or feature change.
- No packet ID reuse, packet field change, or protocol-version change.
- No server-side YouTube, Radio Browser, station-stream, metadata, or audio
  proxying.
- No playlist persistence, HLS support, new decoder format, or new integration.
- No claim that an automated build replaces real Forge and GTNH smoke tests.

## Target architecture

### Package boundaries

`com.horizonradio.core`

- Pure Java models, protocol-independent queue operations, validation rules,
  and deterministic state machines.
- Must not import Forge, Minecraft, GUI, or packet implementation classes.

`com.horizonradio.network`

- Active packet codecs, protocol bounds, message registration, and adapters
  between wire packets and core operations.
- Packet decoding performs structural bounds checks; server-side state rules
  remain in server services.

`com.horizonradio.server`

- Forge lifecycle integration, server-thread scheduling, queue authority,
  permissions, timing, and broadcasts.
- Contains no production outbound media or discovery requests.

`com.horizonradio.media`

- Platform-neutral HTTP, YouTube, Radio Browser, stream validation, download,
  format detection, decoding, resampling, and media resource abstractions.
- Replaces the misleading `server.media` location and the server package
  location of client-only remote services.

`com.horizonradio.client`

- Forge client lifecycle, controller composition, transport facade, playback,
  presentation state, and GUI.
- Depends on `core`, `media`, and `network`; media code never depends on GUI or
  packet classes.

### Client components

`HorizonRadioClient` remains the static Forge-facing lifecycle facade while
existing call sites migrate. It owns and delegates to the following
instantiable components:

- `ClientDiscoveryController`: search, charts, imports, Radio Browser requests,
  request generations, cancellation, and result publication.
- `ClientQueueController`: snapshots, deltas, resync state, pending adds, and
  mapping between packet DTOs and transport-neutral queue operations.
- `ClientPlaybackController`: active source generation, clock alignment,
  private/server playback mode, finite playback, and radio handoff.
- `AudioCacheController`: download ownership, current/neighbor prefetching,
  recently played entries, and cache pruning.
- `ClientPresentationStore`: immutable or defensively copied screen-facing
  state for queue rows, discovery results, now playing, radio, and controls.

GUI-owned nested result and playlist types move to
`com.horizonradio.client.presentation`. `HorizonRadioScreen` consumes those
types instead of owning models used by other classes.

The screen uses reusable `ResultPaneState<T>` instances for result lists,
loading, errors, progress, reveal delay, and scroll state. Rendering and input
handling remain immediate-mode, but remote work and cross-tab state transitions
are delegated to controllers.

## Data flow

### Client discovery

```text
GUI action
  -> ClientDiscoveryController
  -> bounded client I/O executor
  -> media service
  -> generation/current-screen check
  -> ClientPresentationStore
  -> GUI refresh
```

Superseded requests are cancelled when practical. A result that cannot be
cancelled may finish, but it cannot publish after its generation or originating
screen becomes stale.

### Queue and protocol

```text
C2S packet
  -> packet bounds validation
  -> per-player request policy
  -> bounded ServerThreadExecutor
  -> PlaylistManager / core state
  -> existing S2C snapshot or delta
  -> ClientQueueController
  -> ClientPresentationStore
```

Repeated playlist-resync requests are coalesced or rate-limited per player.
The server task queue initially holds at most 4,096 tasks and drains at most 256
tasks per tick. A player may enqueue one playlist-resync request per second;
additional pending resync requests for that player are coalesced. These values
are named constants and boundary-tested. One task failure is logged and
isolated from subsequent tasks.

### Playback

```text
TrackSync
  -> ClientPlaybackController generation check
  -> AudioCacheController cache/download request
  -> AudioPlayer or direct radio session
  -> presentation update
```

Source and generation checks remain the authority for rejecting stale download
and radio completions. Each stream, decoder, audio line, future, and executor
has one explicit lifecycle owner.

## Network and resource safety

### External target policy

A shared `ExternalResourcePolicy` validates every radio URL before connection
and every redirect destination. It permits only HTTP and HTTPS with a valid
host. Resolution rejects loopback, site-local/private, link-local, multicast,
unspecified, and IPv6 unique-local destinations.

Validation is injectable for deterministic tests. Production validation checks
all resolved addresses immediately before opening a connection. Redirects are
resolved relative to the current URL and revalidated. Rejected targets fail
without retry.

This policy protects client-local radio connections. Trusted fixed YouTube and
Radio Browser API endpoints remain explicit service constants rather than
arbitrary user-provided targets.

### HTTP limits

A shared bounded response reader:

- rejects a declared `Content-Length` above the endpoint limit;
- counts bytes while reading chunked or unknown-length responses;
- aborts as soon as the limit is exceeded;
- always closes the stream and disconnects its connection owner;
- decodes UTF-8 only after enforcing the byte limit.

Discovery JSON responses are limited to 4 MiB. Endpoint-specific exceptions
require a separately named constant, a documented payload reason, and boundary
tests.

### Executors and backpressure

- Client media work uses named, bounded executors rather than the common
  `CompletableFuture` pool or an unbounded cached thread pool. Discovery and
  metadata start with four workers and a 64-task queue; finite downloads start
  with two workers and a 16-task queue.
- Download concurrency and metadata concurrency remain separate so metadata
  cannot starve active playback. Their capacities are named, boundary-tested
  constants rather than runtime-sized pools.
- Queue saturation fails the request predictably and produces a bounded user
  error or log entry.
- Shutdown rejects new work, cancels owned pending work, and waits for a bounded
  interval before forced cancellation.

## Error handling

Expected media failures are classified as invalid input, blocked target,
response too large, timeout, remote failure, unsupported media, overload, or
cancellation. Controllers translate them into short presentation messages;
logs retain the technical cause without exposing secrets.

Security rejection and invalid input are never retried. Retryable remote
failures keep existing bounded backoff behavior. Cancellation is not displayed
as a user error when a newer request replaced the old request.

Configuration and cache writes use temporary files plus atomic replacement
where supported, with a safe fallback. Failed delete, move, close, and executor
termination operations are handled or logged rather than silently ignored.

## Legacy removal

Remove production classes and paths that are not registered and exist only for
historical relay compatibility, including obsolete finite/radio relay packet
serializers and their inactive `AudioPlayer` assembly paths. Remove deprecated
metadata-carrying constructors/accessors and compatibility transport overloads
after all active callers use source ID plus duration or source-aware models.

Tests are retained only when they cover active behavior, migration of persisted
user data, or the active wire contract. Tests that merely require deleted Java
APIs or unregistered packet serializers are removed with those APIs.

Persisted config/cache migration support is not protocol relay compatibility
and remains where users can still possess the old file format.

## Build, CI, and release quality gates

- Keep formatting and compilation warning cleanup compatible with Java-8
  runtime output; do not adopt records or newer runtime APIs.
- Replace deprecated local Spotless configuration with its supported
  equivalent.
- CI builds the deployable artifact before running dedicated packaging tests
  with an explicit artifact path.
- Packaging tests fail when their artifact is absent in CI; they do not silently
  skip the release guarantee.
- Release automation uses `spotlessCheck`, never `spotlessApply`, and stages
  only the version files it intentionally changes.
- Coverage is published as an initial baseline. The baseline commit does not
  impose an arbitrary percentage; every subsequent modernization commit must
  keep or improve line and branch coverage for each refactored component.
- Dependency verification/locking and immutable CI action references are added
  only where compatible with the GTNH convention build and documented release
  flow.

## Test strategy

All behavioral changes follow test-driven development. Each extraction starts
with characterization tests when current behavior is not already explicit.

Required additions include:

- external-target tests for IPv4, IPv6, DNS results, and redirect chains;
- bounded-response tests for declared, chunked, and unknown lengths;
- executor saturation, cancellation, shutdown, and task-isolation tests;
- server queue capacity, per-tick budget, and resync-coalescing tests;
- controller generation and stale-screen publication tests;
- transport-neutral queue delta tests and active packet round trips;
- presentation-state tests shared across search, charts, imports, and radio;
- packaging tests against the newly built reobfuscated JAR;
- source/package boundary checks for dedicated-server safety.

Every implementation phase ends with focused tests followed by
`spotlessCheck`, the complete test suite, build/JAR audit, and
`git diff --check`. Standalone Forge/Java 8 and pinned GTNH/Java 17+ smoke tests
remain explicit manual release gates until reproducible environments are added.

## Implementation sequence

1. Establish characterization tests and repair CI packaging execution.
2. Add external-target validation, HTTP response limits, bounded executors, and
   bounded server scheduling.
3. Remove unregistered relay/compatibility production paths and their obsolete
   tests.
4. Move media services and decoders into the media boundary; remove transport
   dependencies from media and core.
5. Extract presentation models and shared result-pane state.
6. Extract discovery, queue, playback, cache, and presentation components from
   `HorizonRadioClient` while retaining a small lifecycle facade.
7. Reduce `HorizonRadioScreen` to rendering, input, and controller calls.
8. Harden release automation, add coverage/verification gates, and reconcile
   all documentation.

Each step must leave the repository buildable and the active protocol
compatible. Mechanical moves are separated from behavior changes so failures
can be attributed to one cause.

## Acceptance criteria

- No production path can connect a radio stream to a disallowed local/private
  target or consume an unbounded discovery response.
- Client and server asynchronous queues have explicit capacity and work limits.
- `core` imports no packet, Forge, Minecraft, GUI, or media implementation type.
- Client media services no longer reside in `server` packages.
- GUI presentation models are top-level client presentation types.
- `HorizonRadioClient` and `HorizonRadioScreen` delegate the approved
  responsibilities instead of implementing them directly.
- No unregistered relay packet or inactive relay audio path remains in main
  source.
- The 24 active packet registrations and their wire layouts are unchanged.
- CI runs all ordinary tests plus packaging tests against the produced JAR with
  no unexpected skips.
- Formatter, tests, build, package-boundary checks, JAR audit, and diff checks
  pass.
- Documentation describes only the final active architecture and verified
  compatibility evidence.
