# Modernization Safety and Backpressure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close confirmed network/resource risks and make the deployable JAR part of the ordinary quality gate.

**Architecture:** Add platform-neutral guards under `com.horizonradio.media`, inject bounded executors into blocking services, and bound/coalesce work before it reaches the Minecraft server thread. Preserve every active packet ID and field.

**Tech Stack:** Java 8-compatible output via Jabel, Forge 1.7.10, Gradle Kotlin DSL, JUnit 4, `HttpURLConnection`, `CompletableFuture`, Java concurrency.

**Spec:** `docs/superpowers/specs/2026-08-22-project-modernization-design.md`

## Global Constraints

- Preserve all 24 active packet registrations and wire layouts.
- Keep media client-local; server code performs no outbound media request.
- Build with Java 25; preserve Java-8-compatible runtime output and Java 17+ GTNH operation.
- Limit discovery JSON to 4 MiB.
- Discovery/metadata uses four workers and a 64-task queue; downloads use two workers and a 16-task queue.
- Server scheduling holds 4,096 tasks and drains 256 tasks per tick.
- Coalesce pending playlist resyncs per player and accept at most one per second.
- Add no external dependency or Java 9+ runtime API.

---

### Task 1: Make packaging verification an executable Gradle gate

**Files:**
- Modify: `build.gradle.kts`
- Modify: `.github/workflows/build.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `src/test/java/com/horizonradio/server/StandalonePackagingTest.java`
- Modify: `src/test/java/com/horizonradio/server/StandaloneMediaSourceAuditTest.java`

**Interfaces:**
- Consumes: Gradle `jar`, `reobfJar`, `test`, and `check` tasks.
- Produces: `packagingTest` with `horizonradio.test.artifact` set to the reobfuscated `jar` archive.

- [ ] **Step 1: Capture the current skip**

```bash
./gradlew clean test
perl -ne 'print if /<testsuite/ && /skipped="[1-9]/' build/test-results/test/TEST-*.xml
```

Expected: the two packaging suites are skipped because no artifact property is present.

- [ ] **Step 2: Make missing artifacts fail in packaging tests**

Replace `Assume.assumeTrue` guards in both classes with:

```java
private static Path requiredArtifact() {
    String configured = System.getProperty("horizonradio.test.artifact", "").trim();
    assertFalse("packaging test requires horizonradio.test.artifact", configured.isEmpty());
    Path artifact = Paths.get(configured);
    assertTrue("packaging artifact does not exist: " + artifact, Files.isRegularFile(artifact));
    return artifact;
}
```

- [ ] **Step 3: Register isolated packaging execution**

Add `import org.gradle.api.plugins.LifecycleBasePlugin` and `import org.gradle.api.tasks.testing.Test`, then add this task configuration to `build.gradle.kts`:

```kotlin
val packagingTest by tasks.registering(Test::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named("reobfJar"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/StandalonePackagingTest.class", "**/StandaloneMediaSourceAuditTest.class")
    val artifact = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(artifact)
    systemProperty("horizonradio.test.artifact", artifact.get().asFile.absolutePath)
}
tasks.test {
    exclude("**/StandalonePackagingTest.class", "**/StandaloneMediaSourceAuditTest.class")
}
tasks.named("check") { dependsOn(packagingTest) }
```

- [ ] **Step 4: Verify both test lanes**

```bash
./gradlew clean test packagingTest
```

Expected: ordinary tests contain no packaging skips; `packagingTest` executes both audit classes and passes.

- [ ] **Step 5: Gate both workflows**

Use this step in build and release workflows:

```yaml
- name: Format, test, package, and audit
  run: ./gradlew spotlessCheck test packagingTest build --no-daemon
```

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts .github/workflows/build.yml .github/workflows/release.yml
git add src/test/java/com/horizonradio/server/StandalonePackagingTest.java
git add src/test/java/com/horizonradio/server/StandaloneMediaSourceAuditTest.java
git commit -m "build: enforce deployable jar audits"
```

### Task 2: Reject unsafe radio destinations and redirects

**Files:**
- Create: `src/main/java/com/horizonradio/media/net/ExternalResourcePolicy.java`
- Create: `src/test/java/com/horizonradio/media/net/ExternalResourcePolicyTest.java`
- Modify: `src/main/java/com/horizonradio/server/media/RadioInputSession.java`
- Modify: `src/test/java/com/horizonradio/server/media/RadioInputSessionTest.java`

**Interfaces:**
- Consumes: radio `URL` values before each connection hop.
- Produces: `ExternalResourcePolicy.requirePublicHttpUrl(URL)` and injectable `HostResolver`.

- [ ] **Step 1: Write failing policy tests**

```java
@Test
public void rejectsHostWhenAnyResolvedAddressIsPrivate() throws Exception {
    ExternalResourcePolicy policy = new ExternalResourcePolicy(host -> new InetAddress[] {
        InetAddress.getByName("93.184.216.34"), InetAddress.getByName("192.168.1.10") });
    try {
        policy.requirePublicHttpUrl(new URL("https://radio.example/stream"));
        fail("expected private destination rejection");
    } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("non-public"));
    }
}
```

Add separate cases for loopback, unspecified, link-local, RFC1918, multicast, `::1`, `::`, `fe80::1`, `fc00::1`, `fd00::1`, allowed public IPv4/IPv6, invalid scheme, and missing host.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.media.net.ExternalResourcePolicyTest
```

Expected: compilation fails because the policy does not exist.

- [ ] **Step 3: Implement the policy**

```java
public final class ExternalResourcePolicy {
    public interface HostResolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    private final HostResolver resolver;

    public ExternalResourcePolicy() {
        this(new HostResolver() {
            @Override
            public InetAddress[] resolve(String host) throws IOException {
                return InetAddress.getAllByName(host);
            }
        });
    }

    ExternalResourcePolicy(HostResolver resolver) {
        if (resolver == null) throw new IllegalArgumentException("resolver is required");
        this.resolver = resolver;
    }

    public URL requirePublicHttpUrl(URL url) throws IOException;
}
```

The block above defines the required surface; implement the method in the concrete class. Reject a null URL, schemes other than HTTP/HTTPS, missing hosts, empty DNS results, and every address for which `isAnyLocalAddress`, `isLoopbackAddress`, `isLinkLocalAddress`, `isSiteLocalAddress`, or `isMulticastAddress` is true. For 16-byte addresses also reject `(address[0] & 0xFE) == 0xFC`. Return the original URL only after every resolved address passes.

- [ ] **Step 4: Verify GREEN**

```bash
./gradlew test --tests com.horizonradio.media.net.ExternalResourcePolicyTest
```

- [ ] **Step 5: Add a failing redirect test**

In `RadioInputSessionTest`, return a public initial response with `Location: http://127.0.0.1/admin`. Assert failure occurs before the fake connection factory records a second open.

```java
assertEquals(1, openedUrls.size());
assertTrue(failure.getMessage().contains("non-public"));
```

- [ ] **Step 6: Revalidate each hop**

Inject the policy into `HttpConnectionFactory` and execute this immediately before each `openConnection` and after resolving each redirect:

```java
current = externalResourcePolicy.requirePublicHttpUrl(current);
```

Preserve the existing five-redirect limit.

- [ ] **Step 7: Test and commit**

```bash
./gradlew test --tests com.horizonradio.media.net.ExternalResourcePolicyTest
./gradlew test --tests com.horizonradio.server.media.RadioInputSessionTest
git add src/main/java/com/horizonradio/media/net/ExternalResourcePolicy.java
git add src/main/java/com/horizonradio/server/media/RadioInputSession.java
git add src/test/java/com/horizonradio/media/net/ExternalResourcePolicyTest.java
git add src/test/java/com/horizonradio/server/media/RadioInputSessionTest.java
git commit -m "fix: block unsafe radio destinations"
```

### Task 3: Bound discovery HTTP responses

**Files:**
- Create: `src/main/java/com/horizonradio/media/net/BoundedResponseReader.java`
- Create: `src/test/java/com/horizonradio/media/net/BoundedResponseReaderTest.java`
- Modify: `src/main/java/com/horizonradio/server/YouTubeService.java`
- Modify: `src/main/java/com/horizonradio/server/RadioBrowserService.java`
- Modify: `src/test/java/com/horizonradio/server/YouTubeServiceTest.java`
- Modify: `src/test/java/com/horizonradio/server/RadioBrowserServiceTest.java`

**Interfaces:**
- Consumes: response stream, declared byte length, maximum bytes.
- Produces: `BoundedResponseReader.readUtf8(InputStream, long, int)`.

- [ ] **Step 1: Write failing byte-limit tests**

```java
@Test
public void rejectsUnknownLengthAtLimitPlusOne() throws Exception {
    byte[] body = new byte[] { '1', '2', '3', '4', '5' };
    try {
        BoundedResponseReader.readUtf8(new ByteArrayInputStream(body), -1L, 4);
        fail("expected response limit");
    } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("4"));
    }
}
```

Add exact-limit success, declared-length early rejection, multibyte UTF-8 accounting, and stream-closed-on-success/failure tests.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.media.net.BoundedResponseReaderTest
```

- [ ] **Step 3: Implement the bounded reader**

```java
public static String readUtf8(InputStream input, long declaredLength, int maximumBytes) throws IOException {
    if (input == null) throw new IllegalArgumentException("input is required");
    if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes must be positive");
    if (declaredLength > maximumBytes) throw new IOException("HTTP response exceeds " + maximumBytes + " bytes");
    try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        int total = 0;
        for (int count; (count = stream.read(buffer)) != -1;) {
            if (count > maximumBytes - total) throw new IOException("HTTP response exceeds " + maximumBytes + " bytes");
            output.write(buffer, 0, count);
            total += count;
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: Replace all unbounded discovery loops**

Add `MAX_RESPONSE_BYTES = 4 * 1024 * 1024` to both services and replace the YouTube search, YouTube charts, and Radio Browser readers with:

```java
String body = BoundedResponseReader.readUtf8(
    connection.getInputStream(), connection.getContentLengthLong(), MAX_RESPONSE_BYTES);
```

Keep `disconnect()` in each existing `finally` block.

- [ ] **Step 5: Add service-level limit tests**

Use the existing HTTP seams to return `MAX_RESPONSE_BYTES + 1` bytes from a repeating fake stream. Assert the future fails or returns the service's documented empty result and the stream/connection closes.

- [ ] **Step 6: Test and commit**

```bash
./gradlew test --tests com.horizonradio.media.net.BoundedResponseReaderTest
./gradlew test --tests com.horizonradio.server.YouTubeServiceTest
./gradlew test --tests com.horizonradio.server.RadioBrowserServiceTest
git add src/main/java/com/horizonradio/media/net/BoundedResponseReader.java
git add src/main/java/com/horizonradio/server/YouTubeService.java src/main/java/com/horizonradio/server/RadioBrowserService.java
git add src/test/java/com/horizonradio/media/net/BoundedResponseReaderTest.java
git add src/test/java/com/horizonradio/server/YouTubeServiceTest.java src/test/java/com/horizonradio/server/RadioBrowserServiceTest.java
git commit -m "fix: bound discovery responses"
```

### Task 4: Use bounded media executors

**Files:**
- Create: `src/main/java/com/horizonradio/media/concurrent/MediaExecutors.java`
- Create: `src/test/java/com/horizonradio/media/concurrent/MediaExecutorsTest.java`
- Modify: `src/main/java/com/horizonradio/server/YouTubeService.java`
- Modify: `src/main/java/com/horizonradio/server/RadioBrowserService.java`
- Modify: `src/main/java/com/horizonradio/server/AudioDownloadService.java`
- Modify: `src/main/java/com/horizonradio/client/ClientProxy.java`
- Modify: `src/test/java/com/horizonradio/server/AudioDownloadServiceTest.java`
- Modify: `src/test/java/com/horizonradio/server/YouTubeServiceTest.java`
- Modify: `src/test/java/com/horizonradio/server/RadioBrowserServiceTest.java`

**Interfaces:**
- Produces: `MediaExecutors.newDiscoveryExecutor()`, `newDownloadExecutor()`, and bounded shutdown.
- Consumers pass explicit `Executor`/`ExecutorService`; no service uses the common pool or `newCachedThreadPool`.

- [ ] **Step 1: Write failing saturation and thread tests**

Occupy every worker, fill each queue, and assert the next submission throws `RejectedExecutionException`. Assert daemon threads and prefixes `HorizonRadio-Discovery-` and `HorizonRadio-Download-`.

```java
assertEquals(4, ((ThreadPoolExecutor) discovery).getCorePoolSize());
assertEquals(64, ((ThreadPoolExecutor) discovery).getQueue().remainingCapacity());
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.media.concurrent.MediaExecutorsTest
```

- [ ] **Step 3: Implement fixed bounded pools**

```java
public static ExecutorService newDiscoveryExecutor() {
    return fixedBounded("HorizonRadio-Discovery-", 4, 64);
}

public static ExecutorService newDownloadExecutor() {
    return fixedBounded("HorizonRadio-Download-", 2, 16);
}
```

Use `ThreadPoolExecutor(workerCount, workerCount, 0L, MILLISECONDS, new ArrayBlockingQueue<Runnable>(queueSize), daemonFactory, new AbortPolicy())`.

- [ ] **Step 4: Inject discovery execution**

Add explicit constructors and pass the executor to every async call:

```java
public YouTubeService(Executor executor) { this.executor = requireExecutor(executor); }
public RadioBrowserService(Executor executor) { this.executor = requireExecutor(executor); }
```

No-arg constructors may remain only while an active production caller needs them; if retained, they own and expose shutdown for their bounded executor.

- [ ] **Step 5: Inject download execution**

Replace `newCachedThreadPool` in `AudioDownloadService` with an injected `ExecutorService`. On `RejectedExecutionException`, remove the provisional `activeDownloads` entry and complete exceptionally with `new MediaException("media queue is full", cause)`.

- [ ] **Step 6: Centralize lifecycle in `ClientProxy`**

Create one discovery pool and one download pool during client initialization, inject them into the services, and close them exactly once in the existing shutdown hook.

- [ ] **Step 7: Test and commit**

```bash
./gradlew test --tests 'com.horizonradio.media.concurrent.*'
./gradlew test --tests com.horizonradio.server.AudioDownloadServiceTest
./gradlew test --tests com.horizonradio.server.YouTubeServiceTest --tests com.horizonradio.server.RadioBrowserServiceTest
git add src/main/java/com/horizonradio/media/concurrent/MediaExecutors.java
git add src/main/java/com/horizonradio/server/YouTubeService.java src/main/java/com/horizonradio/server/RadioBrowserService.java
git add src/main/java/com/horizonradio/server/AudioDownloadService.java src/main/java/com/horizonradio/client/ClientProxy.java
git add src/test/java/com/horizonradio/media/concurrent/MediaExecutorsTest.java src/test/java/com/horizonradio/server
git commit -m "refactor: bound client media work"
```

### Task 5: Classify expected media failures at their source

**Files:**
- Create: `src/main/java/com/horizonradio/media/MediaFailureKind.java`
- Modify: `src/main/java/com/horizonradio/server/media/MediaException.java`
- Modify: `src/main/java/com/horizonradio/media/net/ExternalResourcePolicy.java`
- Modify: `src/main/java/com/horizonradio/media/net/BoundedResponseReader.java`
- Modify: `src/main/java/com/horizonradio/server/AudioDownloadService.java`
- Modify: `src/main/java/com/horizonradio/client/media/ClientMediaService.java`
- Create: `src/test/java/com/horizonradio/media/MediaFailureKindTest.java`
- Modify: `src/test/java/com/horizonradio/media/net/ExternalResourcePolicyTest.java`
- Modify: `src/test/java/com/horizonradio/media/net/BoundedResponseReaderTest.java`
- Modify: `src/test/java/com/horizonradio/media/concurrent/MediaExecutorsTest.java`
- Modify: `src/test/java/com/horizonradio/server/AudioDownloadServiceTest.java`
- Modify: `src/test/java/com/horizonradio/client/media/ClientMediaServiceTest.java`

**Interfaces:**
- `MediaFailureKind`: `INVALID_INPUT`, `BLOCKED_TARGET`, `RESPONSE_TOO_LARGE`, `TIMEOUT`, `REMOTE_FAILURE`, `UNSUPPORTED_MEDIA`, `OVERLOAD`, `CANCELLED`.
- `MediaException` retains both existing constructors as `REMOTE_FAILURE` defaults and adds kind-aware overloads plus `getKind()`.

- [ ] **Step 1: Write failing classification tests**

Test that unsafe targets are `BLOCKED_TARGET`, oversized bodies are `RESPONSE_TOO_LARGE`, saturated executors are `OVERLOAD`, interrupted/superseded operations are `CANCELLED`, socket timeouts are `TIMEOUT`, malformed user URLs are `INVALID_INPUT`, decoder rejection is `UNSUPPORTED_MEDIA`, and an ordinary non-2xx response is `REMOTE_FAILURE`.

Also assert the existing one- and two-argument `MediaException` constructors retain their message/cause and default to `REMOTE_FAILURE`.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.media.MediaFailureKindTest
```

- [ ] **Step 3: Add the typed exception surface**

Implement these constructors without changing existing call sites:

```java
public MediaException(String message);
public MediaException(String message, Throwable cause);
public MediaException(MediaFailureKind kind, String message);
public MediaException(MediaFailureKind kind, String message, Throwable cause);
public MediaFailureKind getKind();
```

Reject a null kind. Preserve the original cause and technical message for logs.

- [ ] **Step 4: Classify boundary failures**

Use kind-aware constructors in the external-target policy, bounded reader, executor rejection path, timeout translation, cancellation checks, URL/input validation, and decoder-registry unsupported-format path. Do not rewrite every internal parse exception in one mechanical sweep; classify it where it crosses the media-service boundary.

- [ ] **Step 5: Keep presentation safe and behavior-compatible**

In `ClientMediaService`, map kinds to the existing short user-facing error text. Cancellation caused by a newer generation produces no visible error. Log the original exception/cause at the existing diagnostic level and never include request headers, cookies, tokens, or full signed media URLs.

- [ ] **Step 6: Verify and commit**

```bash
./gradlew test --tests com.horizonradio.media.MediaFailureKindTest
./gradlew test --tests 'com.horizonradio.media.net.*'
./gradlew test --tests com.horizonradio.client.media.ClientMediaServiceTest
git add src/main/java/com/horizonradio/media/MediaFailureKind.java src/main/java/com/horizonradio/server/media/MediaException.java
git add src/main/java/com/horizonradio/media/net src/main/java/com/horizonradio/server/AudioDownloadService.java
git add src/main/java/com/horizonradio/client/media/ClientMediaService.java src/test/java/com/horizonradio
git commit -m "refactor: classify media failures"
```

### Task 6: Consolidate crash-safe configuration writes

**Files:**
- Create: `src/main/java/com/horizonradio/core/io/AtomicFileWriter.java`
- Create: `src/test/java/com/horizonradio/core/io/AtomicFileWriterTest.java`
- Modify: `src/main/java/com/horizonradio/core/config/HorizonRadioConfig.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClientConfig.java`
- Modify: `src/test/java/com/horizonradio/HorizonRadioConfigTest.java`
- Modify: `src/test/java/com/horizonradio/client/HorizonRadioClientConfigTest.java`

**Interfaces:**
- `AtomicFileWriter.write(Path target, OutputWriter writer)` writes a sibling temporary file, closes it, atomically replaces the target when supported, and falls back to a replacing move.
- `OutputWriter.write(OutputStream output)` does not close the supplied stream.

- [ ] **Step 1: Write failure and replacement tests**

Cover successful replacement, parent-directory creation, writer failure preserving the old target, temporary cleanup, atomic-move fallback through an injectable mover, and cleanup failure being attached to the primary exception rather than ignored.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.core.io.AtomicFileWriterTest
```

- [ ] **Step 3: Implement one lifecycle owner**

`AtomicFileWriter` creates the target parent when needed, opens the temporary output in try-with-resources, invokes `OutputWriter`, attempts `ATOMIC_MOVE` with `REPLACE_EXISTING`, catches only `AtomicMoveNotSupportedException` for the fallback, and deletes a remaining temporary file in `finally`. Expose the mover seam package-privately for deterministic tests. Propagate write/move failures; attach cleanup failures with `addSuppressed`.

- [ ] **Step 4: Migrate both active configuration stores**

Replace direct overwrite in `HorizonRadioConfig.save` and the duplicated temporary/move logic in `HorizonRadioClientConfig.save`. Serialize UTF-8 through the supplied stream. Preserve server exception propagation and client warning behavior. Verify removed external-tool fields from the legacy plan are not reintroduced.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew test --tests com.horizonradio.core.io.AtomicFileWriterTest
./gradlew test --tests com.horizonradio.HorizonRadioConfigTest
./gradlew test --tests com.horizonradio.client.HorizonRadioClientConfigTest
git add src/main/java/com/horizonradio/core/io/AtomicFileWriter.java src/test/java/com/horizonradio/core/io/AtomicFileWriterTest.java
git add src/main/java/com/horizonradio/core/config/HorizonRadioConfig.java src/main/java/com/horizonradio/client/HorizonRadioClientConfig.java
git add src/test/java/com/horizonradio/HorizonRadioConfigTest.java src/test/java/com/horizonradio/client/HorizonRadioClientConfigTest.java
git commit -m "refactor: unify atomic config writes"
```

### Task 7: Bound and isolate server-thread work

**Files:**
- Modify: `src/main/java/com/horizonradio/server/ServerThreadExecutor.java`
- Modify: `src/test/java/com/horizonradio/server/ServerThreadExecutorTest.java`
- Create: `src/main/java/com/horizonradio/server/ResyncRequestGate.java`
- Create: `src/test/java/com/horizonradio/server/ResyncRequestGateTest.java`
- Modify: `src/main/java/com/horizonradio/network/ServerMessageHandlers.java`
- Modify: `src/main/java/com/horizonradio/CommonProxy.java`
- Modify: `src/test/java/com/horizonradio/network/PacketRoundTripTest.java`

**Interfaces:**
- `ServerThreadExecutor.execute(MinecraftServer, Runnable)` returns `boolean`.
- `TaskQueue(int, int)` exposes package-private `offer`, `drain`, and `size` for tests.
- `ResyncRequestGate.tryAcquire(UUID, long)`, `release(UUID)`, and `remove(UUID)`.

- [ ] **Step 1: Write failing capacity, budget, and isolation tests**

```java
@Test
public void drainsOnlyBudgetAndContinuesAfterFailure() {
    ServerThreadExecutor.TaskQueue queue = new ServerThreadExecutor.TaskQueue(4, 2);
    AtomicInteger ran = new AtomicInteger();
    assertTrue(queue.offer(() -> { throw new IllegalStateException("boom"); }));
    assertTrue(queue.offer(ran::incrementAndGet));
    assertTrue(queue.offer(ran::incrementAndGet));
    assertEquals(2, queue.drain());
    assertEquals(1, ran.get());
    assertEquals(1, queue.size());
}
```

Add a separate test that a capacity-two queue rejects its third offer without growing.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.server.ServerThreadExecutorTest
```

- [ ] **Step 3: Implement bounded draining**

Use `ArrayBlockingQueue<Runnable>`, constants `DEFAULT_CAPACITY = 4096` and `DEFAULT_MAX_TASKS_PER_TICK = 256`, and return `tasks.offer(task)` from `execute`. Catch/log `RuntimeException` around each task and continue until the budget is reached.

- [ ] **Step 4: Write failing resync timing tests**

With a fixed UUID, assert first request accepted, concurrent pending request rejected, released request at 999 ms rejected, and request at 1,000 ms accepted.

```java
assertTrue(gate.tryAcquire(playerId, 10_000L));
assertFalse(gate.tryAcquire(playerId, 10_001L));
gate.release(playerId);
assertFalse(gate.tryAcquire(playerId, 10_999L));
assertTrue(gate.tryAcquire(playerId, 11_000L));
```

- [ ] **Step 5: Implement the gate**

```java
public final class ResyncRequestGate {
    private static final long MIN_INTERVAL_MILLIS = 1000L;
    public synchronized boolean tryAcquire(UUID playerId, long nowMs);
    public synchronized void release(UUID playerId);
    public synchronized void remove(UUID playerId);
}
```

Use a `Set<UUID>` for pending players and a `Map<UUID, Long>` for the last accepted time. `tryAcquire` rejects null IDs, pending IDs, and elapsed times below the interval; accepted calls update both collections. `release` removes only the pending marker. `remove` clears both collections for disconnect cleanup.

- [ ] **Step 6: Gate requests before scheduling**

Change the handler scheduler to return `boolean`. In `PlaylistResyncRequestHandler`, acquire before enqueue and always release in the queued task's `finally`. Release immediately when queue offer fails.

```java
boolean accepted = schedule(new Runnable() {
    @Override public void run() {
        try { hook.handlePlaylistResyncRequest(player, message.getKnownRevision()); }
        finally { RESYNC_GATE.release(player.getUniqueID()); }
    }
});
if (!accepted) RESYNC_GATE.release(player.getUniqueID());
```

- [ ] **Step 7: Remove player state on logout**

Expose `ServerMessageHandlers.onPlayerLoggedOut(UUID)` and call it from `CommonProxy.onPlayerLoggedOut(EntityPlayerMP)` before or after existing playlist cleanup.

- [ ] **Step 8: Test and commit**

```bash
./gradlew test --tests com.horizonradio.server.ServerThreadExecutorTest
./gradlew test --tests com.horizonradio.server.ResyncRequestGateTest
./gradlew test --tests com.horizonradio.network.PacketRoundTripTest
git add src/main/java/com/horizonradio/server/ServerThreadExecutor.java src/main/java/com/horizonradio/server/ResyncRequestGate.java
git add src/main/java/com/horizonradio/network/ServerMessageHandlers.java src/main/java/com/horizonradio/CommonProxy.java
git add src/test/java/com/horizonradio/server/ServerThreadExecutorTest.java src/test/java/com/horizonradio/server/ResyncRequestGateTest.java
git add src/test/java/com/horizonradio/network/PacketRoundTripTest.java
git commit -m "fix: bound server packet scheduling"
```

### Task 8: Verify the safety phase

**Files:**
- Modify only a file already touched above when verification exposes an integration defect.

**Interfaces:**
- Consumes: packaging gate, network policy, response reader, executors, server queue, resync gate.
- Produces: green build, executed packaging audit, clean diff.

- [ ] **Step 1: Validate formatting**

```bash
./gradlew spotlessCheck
```

Expected: PASS. If it fails, run `./gradlew spotlessApply` and retain formatting only in files already modified by this plan.

- [ ] **Step 2: Run all tests and packaging audit**

```bash
./gradlew test packagingTest
```

Expected: PASS with no unexpected skip and both packaging classes executed.

- [ ] **Step 3: Build the artifact**

```bash
./gradlew build
```

Expected: PASS with a reobfuscated JAR under `build/libs`.

- [ ] **Step 4: Check the repository diff**

```bash
git diff --check
git status --short
git diff --stat
```

Expected: no whitespace errors and only reviewed safety-phase files.

- [ ] **Step 5: Commit verification corrections only when needed**

If verification exposes a defect, return to the task that introduced it, add a focused regression test, make the smallest correction, rerun that task's focused command plus this complete gate, and amend that task's commit. Do not create an empty verification commit.
