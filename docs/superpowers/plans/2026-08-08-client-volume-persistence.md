# Client Volume Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the client-local HorizonRadio volume in `config/horizonradio-client.json` and restore it on client startup without changing server state or network behavior.

**Architecture:** A focused `HorizonRadioClientConfig` component owns JSON load/save and safe file replacement. `ClientProxy` loads it during client pre-initialization, while `HorizonRadioClient.setVolume` applies the effective bounded value to `AudioPlayer` and persists it. Disconnect cache clearing continues to leave the audio volume untouched.

**Tech Stack:** Java compatible with the Forge 1.7.10 runtime target, Gson already used by the project, JUnit 4, `java.nio.file.Files` for atomic-move fallback, and Gradle wrapper tests.

## Global Constraints

- Work only in `/home/benjamin/Projects/dev/HorizonRadio/.worktrees/persist-volume` on branch `feature/persist-volume`.
- Do not read from or modify the occupied `play-now-queue-click` worktree as part of implementation.
- Keep the setting client-local; do not add packets, server state, or fields to the shared `horizonradio.json`.
- Use `1.0f` as the default volume and preserve the existing inclusive `0.0f`–`1.0f` range.
- Store only `volume` in `config/horizonradio-client.json` using UTF-8 JSON.
- Treat missing, malformed, or unreadable persistence as non-fatal; playback keeps the in-memory value.
- Replace the target through a temporary file and use an atomic move when supported, with a regular replacement fallback.
- Follow TDD: every production behavior is preceded by a focused test that was observed failing.
- Preserve Forge 1.7.10 and Java 8-compatible runtime behavior; do not introduce modern-only APIs or syntax.

---

## File Map

| File | Responsibility |
|---|---|
| `src/main/java/com/horizonradio/client/HorizonRadioClientConfig.java` | Client-only JSON persistence, normalization, and safe file replacement. |
| `src/test/java/com/horizonradio/client/HorizonRadioClientConfigTest.java` | Isolated file-based tests for defaults, parsing, clamping, and round trips. |
| `src/main/java/com/horizonradio/client/HorizonRadioClient.java` | Owns the loaded client config, applies startup volume, and persists volume changes. |
| `src/main/java/com/horizonradio/client/ClientProxy.java` | Loads the client config during Forge client pre-initialization. |
| `src/test/java/com/horizonradio/client/GuiLayoutTest.java` | Regression coverage for the client API and reconnect cache behavior. |
| `README.md` | User-facing location and behavior of the client volume setting. |
| `docs/ARCHITECTURE.md` | Client/server ownership and configuration boundary documentation. |

## Interfaces

The persistence component exposes these exact methods:

~~~java
public final class HorizonRadioClientConfig {

    public static final float DEFAULT_VOLUME = 1.0f;
    public static final String FILE_NAME = "horizonradio-client.json";

    public static HorizonRadioClientConfig load(File configDirectory);

    public float getVolume();

    public void save(float volume);
}
~~~

The client state boundary exposes this package-local initialization hook and
keeps the existing public volume API:

~~~java
static synchronized void loadClientConfig(File configDirectory);
public static synchronized float getVolume();
public static synchronized void setVolume(float value);
~~~

`ClientProxy.preInit` calls `loadClientConfig` with
`event.getSuggestedConfigurationFile().getParentFile()`.

---

### Task 1: Add and test the client JSON persistence component

**Files:**

- Create: `src/main/java/com/horizonradio/client/HorizonRadioClientConfig.java`
- Create: `src/test/java/com/horizonradio/client/HorizonRadioClientConfigTest.java`

**Interfaces:**

- Produces `HorizonRadioClientConfig.load(File)`, `getVolume()`, and
  `save(float)` for Task 2.
- Uses Gson `JsonObject`, `FileInputStream`/`FileOutputStream`, UTF-8 reader
  and writer, and `Files.move` with `AtomicMoveNotSupportedException`
  fallback.

- [ ] **Step 1: Write the failing tests for defaults and file round trips**

Create a JUnit 4 test class in the same package. Use a fresh temporary
directory per test and recursively delete it in `finally`. The RED suite
must include the two behavioral tests below and the malformed/bounded cases
listed in Step 4; add all four before running Step 2.

~~~java
@Test
public void missingFileUsesDefaultVolume() throws IOException {
    File directory = Files.createTempDirectory("horizonradio-client-config-missing").toFile();
    try {
        HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);

        assertEquals(1.0f, config.getVolume(), 0.0001f);
    } finally {
        deleteRecursively(directory);
    }
}

@Test
public void savedVolumeLoadsAgainFromDedicatedFile() throws IOException {
    File directory = Files.createTempDirectory("horizonradio-client-config-roundtrip").toFile();
    try {
        HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);
        config.save(0.35f);

        assertEquals(0.35f, HorizonRadioClientConfig.load(directory).getVolume(), 0.0001f);
        assertTrue(new File(directory, "horizonradio-client.json").isFile());
    } finally {
        deleteRecursively(directory);
    }
}
~~~

Use static imports for `assertEquals` and `assertTrue`; the test helper should
delete children before deleting the directory, matching the existing
`HorizonRadioConfigTest` pattern.

- [ ] **Step 2: Run the tests and verify the expected RED failure**

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientConfigTest
~~~

Expected result: compilation/test failure because
`HorizonRadioClientConfig` does not exist yet. If Gradle cannot start because
Java is unavailable, record that environment failure and still keep the test
as the first implementation artifact.

- [ ] **Step 3: Write the minimal persistence implementation**

Implement the class with these rules:

~~~java
private static final Logger LOGGER = Logger.getLogger(HorizonRadioClientConfig.class.getName());
private final File configFile;
private final float volume;

private HorizonRadioClientConfig(File configFile, float volume) {
    this.configFile = configFile;
    this.volume = volume;
}

public static HorizonRadioClientConfig load(File configDirectory) {
    File configFile = configDirectory == null ? null : new File(configDirectory, FILE_NAME);
    if (configFile == null || !configFile.isFile()) {
        return new HorizonRadioClientConfig(configFile, DEFAULT_VOLUME);
    }
    // Read JsonObject and use DEFAULT_VOLUME for malformed/missing values.
    // Normalize finite numeric values to the inclusive 0.0f–1.0f range.
}

public void save(float value) {
    if (configFile == null) {
        return;
    }
    // Create the parent if necessary, serialize {"volume": normalizedValue}
    // to configFile.getPath() + ".tmp", then move/replace the target.
}
~~~

Catch `IOException`, `JsonParseException`, and the Gson primitive conversion
runtime failure around loading; log at warning level and return the default.
For writes, catch/log failures, delete the temporary file when possible, and
never throw into the GUI/audio path. Use `Float.isNaN` and
`Float.isInfinite` so non-finite values use the default rather than being
serialized.

- [ ] **Step 4: Run the malformed and bounded cases after implementation**

The following cases are part of the RED suite described in Step 1. They write
raw JSON and prove the parsing contract; after Step 3, run them together with
that suite:

~~~java
@Test
public void malformedFileUsesDefaultVolume() throws IOException {
    File directory = Files.createTempDirectory("horizonradio-client-config-malformed").toFile();
    try {
        write(directory, "{not-json");

        assertEquals(1.0f, HorizonRadioClientConfig.load(directory).getVolume(), 0.0001f);
    } finally {
        deleteRecursively(directory);
    }
}

@Test
public void persistedVolumeIsBoundedToSupportedRange() throws IOException {
    File directory = Files.createTempDirectory("horizonradio-client-config-bounds").toFile();
    try {
        write(directory, "{\"volume\":2.5}");
        assertEquals(1.0f, HorizonRadioClientConfig.load(directory).getVolume(), 0.0001f);

        write(directory, "{\"volume\":-0.5}");
        assertEquals(0.0f, HorizonRadioClientConfig.load(directory).getVolume(), 0.0001f);
    } finally {
        deleteRecursively(directory);
    }
}
~~~

The `write` helper writes UTF-8 bytes to
`new File(directory, HorizonRadioClientConfig.FILE_NAME)`. These tests must
already exist before the RED run in Step 2; after the implementation in
Step 3, run the focused test and verify all four cases pass.

- [ ] **Step 5: Implement normalization and safe replacement, then run GREEN**

Add one private normalization function used by both load and save:

~~~java
private static float normalize(float value) {
    if (Float.isNaN(value) || Float.isInfinite(value)) {
        return DEFAULT_VOLUME;
    }
    return Math.max(0.0f, Math.min(1.0f, value));
}
~~~

Use a `JsonObject` with `addProperty("volume", normalize(value))`. Write the
temporary file using `StandardCharsets.UTF_8`, then:

~~~java
try {
    try {
        Files.move(
            temporaryFile.toPath(),
            configFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporaryFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
} catch (IOException exception) {
    LOGGER.log(Level.WARNING, "Could not replace HorizonRadio client configuration", exception);
    temporaryFile.delete();
}
~~~

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientConfigTest
~~~

Expected result: all focused persistence tests pass.

- [ ] **Step 6: Commit the persistence unit**

~~~bash
git add src/main/java/com/horizonradio/client/HorizonRadioClientConfig.java \
    src/test/java/com/horizonradio/client/HorizonRadioClientConfigTest.java
git commit -m "feat: persist client volume configuration"
~~~

---

### Task 2: Load the value at client startup and persist changes

**Files:**

- Modify: `src/main/java/com/horizonradio/client/HorizonRadioClient.java` near
  the static client state and existing `getVolume`/`setVolume` methods.
- Modify: `src/main/java/com/horizonradio/client/ClientProxy.java` in
  `preInit`.
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`.

**Interfaces:**

- Consumes `HorizonRadioClientConfig.load(File)` from Task 1.
- Produces the package-local `HorizonRadioClient.loadClientConfig(File)` hook
  used by `ClientProxy` and tests.

- [ ] **Step 1: Write the failing startup/persistence tests**

Add tests to `GuiLayoutTest` that initialize a temporary client directory,
change volume through the public API, and prove the file is updated. Also add
a reconnect regression test:

~~~java
@Test
public void clientVolumeChangesPersistToClientConfig() throws IOException {
    File directory = Files.createTempDirectory("horizonradio-volume-api").toFile();
    try {
        HorizonRadioClient.loadClientConfig(directory);
        HorizonRadioClient.setVolume(0.4f);

        assertEquals(0.4f, HorizonRadioClientConfig.load(directory).getVolume(), 0.0001f);
    } finally {
        deleteRecursively(directory);
        HorizonRadioClient.loadClientConfig(null);
    }
}

@Test
public void clearingServerCachePreservesClientVolume() {
    HorizonRadioClient.loadClientConfig(null);
    HorizonRadioClient.setVolume(0.4f);

    HorizonRadioClient.clearCache();

    assertEquals(0.4f, HorizonRadioClient.getVolume(), 0.0001f);
}
~~~

Import `java.nio.file.Files` and reuse the test class's recursive cleanup
helper. The `@After` method must call `HorizonRadioClient.loadClientConfig(null)`
and `HorizonRadioClient.setVolume(1.0f)` so the singleton does not leak state
between tests.

- [ ] **Step 2: Run the tests and verify the expected RED failure**

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
~~~

Expected result: compilation failure because `loadClientConfig` is not yet
defined and the existing `setVolume` does not persist.

- [ ] **Step 3: Add the client-config field and initialization hook**

In `HorizonRadioClient`, add:

~~~java
private static HorizonRadioClientConfig clientConfig;

static synchronized void loadClientConfig(File configDirectory) {
    clientConfig = HorizonRadioClientConfig.load(configDirectory);
    AudioPlayer.getInstance().setVolume(clientConfig.getVolume());
}
~~~

Keep the hook package-local so only the client package controls startup
configuration; it remains callable from `ClientProxy` and same-package tests.

- [ ] **Step 4: Persist the effective volume in `setVolume`**

Replace the existing method body with:

~~~java
public static synchronized void setVolume(float value) {
    AudioPlayer player = AudioPlayer.getInstance();
    player.setVolume(value);
    if (clientConfig != null) {
        clientConfig.save(player.getVolume());
    }
}
~~~

Saving `player.getVolume()` ensures the value written to disk is the same
bounded value used by Java Sound. Do not add persistence to `clearCache` or to
any network handler.

- [ ] **Step 5: Load the configuration from `ClientProxy.preInit`**

After `super.preInit(event)` and before returning from the client override,
add:

~~~java
File configDirectory = event.getSuggestedConfigurationFile().getParentFile();
HorizonRadioClient.loadClientConfig(configDirectory);
~~~

Add `java.io.File` to the imports. This uses the same Forge-provided config
directory as common configuration without changing `CommonProxy` or the
server-side config object.

- [ ] **Step 6: Run the focused tests and verify GREEN**

Run:

~~~bash
./gradlew test --tests com.horizonradio.client.HorizonRadioClientConfigTest \
    --tests com.horizonradio.client.GuiLayoutTest
~~~

Expected result: both test classes pass, including the original slider test,
the startup persistence test, and the reconnect regression test.

- [ ] **Step 7: Commit the client integration**

~~~bash
git add src/main/java/com/horizonradio/client/HorizonRadioClient.java \
    src/main/java/com/horizonradio/client/ClientProxy.java \
    src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "feat: restore persisted client volume"
~~~

---

### Task 3: Document the client-only setting and verify the complete change

**Files:**

- Modify: `README.md` in the installation/use configuration text.
- Modify: `docs/ARCHITECTURE.md` in the configuration and ownership sections.

- [ ] **Step 1: Update user-facing configuration documentation**

Update the README so it states that the slider is client-local and stored in
`config/horizonradio-client.json`, while `config/horizonradio.json` remains
the server/common configuration. Extend the architecture documentation's
configuration row and server-authority paragraph with the same boundary.

- [ ] **Step 2: Check documentation and source formatting**

Run:

~~~bash
git diff --check
rg -n "horizonradio-client\\.json|client-local|Volume is client-local" README.md docs/ARCHITECTURE.md src/main/java src/test/java
~~~

Expected result: no whitespace errors and the dedicated filename appears in
the user docs and client implementation/tests, without adding it to the
server JSON example.

- [ ] **Step 3: Run the complete verification suite**

Run:

~~~bash
./gradlew test
./gradlew build
~~~

Expected result: both commands exit with status 0 and report no test failures.
If the environment still lacks a Java runtime, report the exact blocker and
retain the source-level evidence from focused tests once a compatible JDK is
available; do not claim the suite passed without command output.

- [ ] **Step 4: Inspect the final isolated diff and worktree boundaries**

Run:

~~~bash
git status --short --branch
git diff --stat main...HEAD
git worktree list --porcelain
~~~

Confirm that all feature changes are on `feature/persist-volume`, the main
checkout is not dirty, and the occupied `play-now-queue-click` worktree has
not been modified by this task.

- [ ] **Step 5: Commit the documentation update**

~~~bash
git add README.md docs/ARCHITECTURE.md
git commit -m "docs: describe persisted client volume"
~~~
