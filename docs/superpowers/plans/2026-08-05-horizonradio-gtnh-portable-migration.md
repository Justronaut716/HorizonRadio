# HorizonRadio GTNH-Compatible Portable Migration Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Convert HorizonRadio into a GTNH-native Minecraft 1.7.10 mod with Java 25 development, one portable JAR, an optional GTNH integration seam, and an intentional 1.0.0 breaking boundary.

**Architecture:** Adopt the current GTNH convention build while keeping HorizonRadio’s Forge 1.7.10 runtime. Move Java-only models, configuration, state, parsing, and audio state into a dependency-clean core package; keep Forge lifecycle, networking, GUI, Java Sound, and server player interaction in the Forge adapter. Add a project-owned integration SPI with a default path and a capability-detected GTNH path that does not make GTNHLib or GregTech a runtime dependency.

**Tech Stack:** Minecraft 1.7.10, Forge 10.13.4.1614, GTNH convention build, Java 25 development, Java-8-compatible common artifact, Gradle wrapper from the GTNH ExampleMod template, JUnit 4.13.2, Forge SimpleNetworkWrapper, Gson, Java Sound, yt-dlp, and ffmpeg.

## Global Constraints

- Keep modId as horizonradio.
- Use HorizonRadio consistently for display name, project metadata, and user-facing documentation.
- Set the migrated release to 1.0.0.
- Publish one JAR that runs in GTNH and in a non-GTNH Forge 1.7.10 installation.
- Use Java 25 for development and GTNH validation.
- Keep the portable runtime target Java-8-compatible unless compatibility testing proves a broader safe target.
- Do not add a mandatory GregTech, GTNHLib, ModularUI, or pack-specific dependency.
- Keep optional GTNH types out of common class signatures, annotations, and static initializers.
- Keep usesMixins=false; do not add mixins, coremods, access transformers, or world content in this migration.
- Change the Forge channel identifier for the 1.0.0 protocol so pre-1.0 clients cannot silently share the new packet contract.
- Treat incompatible pre-1.0 configuration or playlist state as unsupported; never silently reinterpret it.
- Preserve .gitignore coverage for .superpowers/, .agents/, .codex/, Gradle output, runtime output, local configuration, audio files, and logs.
- Do not reintroduce retired project identity strings in source, metadata, documentation, artifact names, or generated resources.

## File Map

Build and packaging files:

- Replace build.gradle with build.gradle.kts containing the GTNH convention plugin.
- Replace settings.gradle with settings.gradle.kts containing the GTNH settings convention plugin.
- Modify gradle.properties for HorizonRadio metadata, Java syntax mode, and the 1.7.10 target.
- Replace repositories.gradle and dependencies.gradle with current template-compatible versions while retaining JUnit 4.13.2.
- Replace gradlew, gradlew.bat, and gradle/wrapper/* with the maintained template wrapper.
- Create .java-version containing 25.
- Copy the template’s gtnhShared/ build resources.
- Do not carry forward the legacy repairForgeBinCache task; the old workaround belongs to the removed ForgeGradle build.

Application boundary files:

- Modify src/main/java/com/horizonradio/HorizonRadio.java to use centralized metadata and invoke the integration lifecycle.
- Modify src/main/java/com/horizonradio/CommonProxy.java and src/main/java/com/horizonradio/client/ClientProxy.java for moved core imports and unchanged sided behavior.
- Modify src/main/java/com/horizonradio/network/HorizonRadioNetwork.java to use the versioned channel constant.
- Keep src/main/java/com/horizonradio/network/packets/*.java and the two message-handler files in the Forge adapter; packet field order and discriminator IDs remain unchanged.
- Keep src/main/java/com/horizonradio/server/PlaylistManager.java, ServerEvents.java, ServerThreadExecutor.java, AudioDownloadService.java, and YouTubeService.java in the Forge/server adapter because they touch Minecraft server types or Forge networking.

Portable core files:

- Move HorizonRadioConfig.java to src/main/java/com/horizonradio/core/config/HorizonRadioConfig.java.
- Move the three model classes to src/main/java/com/horizonradio/core/model/.
- Move AudioChunkAssembler.java and AudioPlayerState.java to src/main/java/com/horizonradio/core/audio/.
- Move PlaylistState.java, PlaylistImportService.java, and ChartCache.java to src/main/java/com/horizonradio/core/server/; expose only the methods required by PlaylistManager and the existing tests.
- Create src/main/java/com/horizonradio/core/protocol/HorizonRadioProtocol.java.
- Create src/main/java/com/horizonradio/core/integration/HorizonRadioIntegration.java and HorizonRadioIntegrationContext.java.

Optional integration files:

- Create src/main/java/com/horizonradio/integration/IntegrationManager.java.
- Create src/main/java/com/horizonradio/integration/GtnhEnvironmentDetector.java.
- Create src/main/java/com/horizonradio/integration/GtnhIntegration.java.

Tests and documentation:

- Move pure tests with their classes under src/test/java/com/horizonradio/core/.
- Create src/test/java/com/horizonradio/core/protocol/HorizonRadioProtocolTest.java.
- Create src/test/java/com/horizonradio/integration/IntegrationManagerTest.java.
- Modify src/test/java/com/horizonradio/network/PacketRoundTripTest.java only for moved model imports; keep packet wire assertions intact.
- Modify README.md, docs/ARCHITECTURE.md, and docs/COMPATIBILITY.md for the new build/runtime matrix and package boundaries.
- Modify src/main/resources/mcmod.info to use the new version property and the canonical repository URL.

---

### Task 1: Migrate the build to the GTNH convention

**Files:**
- Delete: build.gradle
- Delete: settings.gradle
- Create: build.gradle.kts
- Create: settings.gradle.kts
- Modify: gradle.properties
- Replace: repositories.gradle
- Replace: dependencies.gradle
- Replace: gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.jar, gradle/wrapper/gradle-wrapper.properties
- Create: .java-version
- Create: gtnhShared/**
- Preserve: .gitignore

**Interfaces:**
- Consumes: Minecraft 1.7.10, Forge 10.13.4.1614, MCP mappings 12, and the existing JUnit 4.13.2 test suite.
- Produces: a fresh-checkout GTNH convention build with clean, setupDecompWorkspace, test, and build tasks.

- [ ] Step 1: Record the pre-migration baseline.

Run:

~~~bash
git status --short
./gradlew --version
./gradlew test --no-daemon
~~~

Record the Java version, Gradle version, test result, and any ForgeGradle repository failure in the implementation commit description. Do not change application source in this step.

- [ ] Step 2: Copy the maintained GTNH starter build files.

Use the current GTNH ExampleMod 1.7.10 template as the source for the wrapper, gtnhShared/, repositories.gradle, dependencies.gradle, build.gradle.kts, and settings.gradle.kts.

The settings plugin must use the current template plugin declaration:

~~~kotlin
plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")
}
~~~

The build script must use the current convention declaration:

~~~kotlin
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}
~~~

- [ ] Step 3: Adapt gradle.properties.

Set these project values and retain the template’s property spelling:

~~~properties
modName=HorizonRadio
modId=horizonradio
modGroup=com.horizonradio
modVersion=1.0.0
minecraftVersion=1.7.10
forgeVersion=10.13.4.1614
channel=stable
mappingsVersion=12
developmentEnvironmentUserName=HorizonRadioDev
usesMixins=false
enableModernJavaSyntax=jabel
enableGenericInjection=true
~~~

Remove the empty legacy accessTransformersFile setting. Do not add a GTNHLib dependency in this task.

- [ ] Step 4: Restore only project dependencies.

Keep JUnit 4.13.2 in the template’s test configuration. Do not carry over the legacy buildscript repository or ForgeGradle 1.2.11 classpath declaration. Use the template’s GTNH Maven repository configuration and retain only repositories required by the actual project dependencies.

- [ ] Step 5: Remove the legacy cache-repair workaround.

Do not copy repairForgeBinCache, its compileJava.dependsOn hook, or the old manual processResources block. Let the GTNH convention own Forge setup and resource expansion. Do not create addon.gradle for this migration.

- [ ] Step 6: Set the development JDK and verify Gradle configuration.

Create .java-version with exactly:

~~~text
25
~~~

Run:

~~~bash
./gradlew --version
./gradlew tasks --all --no-daemon
~~~

Expected: Gradle configures under Java 25 and exposes the Forge setup, test, and build tasks without evaluating the removed legacy buildscript.

- [ ] Step 7: Prepare the decompilation workspace and run the existing tests.

Run:

~~~bash
./gradlew clean setupDecompWorkspace --no-daemon
./gradlew test --no-daemon
~~~

Expected: the current source compiles under the new convention. If the current source does not compile, capture the first compiler error and keep the fix in the next task rather than reintroducing the old build.

- [ ] Step 8: Commit the isolated build migration.

~~~bash
git add .java-version build.gradle.kts settings.gradle.kts gradle.properties repositories.gradle dependencies.gradle gradlew gradlew.bat gradle/wrapper gtnhShared
git rm build.gradle settings.gradle
git commit -m "build: migrate to GTNH convention"
~~~

---

### Task 2: Establish the 1.0.0 metadata and protocol boundary

**Files:**
- Create: src/main/java/com/horizonradio/core/protocol/HorizonRadioProtocol.java
- Create: src/test/java/com/horizonradio/core/protocol/HorizonRadioProtocolTest.java
- Modify: src/main/java/com/horizonradio/HorizonRadio.java
- Modify: src/main/java/com/horizonradio/network/HorizonRadioNetwork.java
- Modify: src/main/resources/mcmod.info

**Interfaces:**
- Consumes: modVersion=1.0.0 from gradle.properties and the existing packet registration table.
- Produces: HorizonRadioProtocol.VERSION == "1.0.0" and HorizonRadioProtocol.CHANNEL_NAME == "horizonradio_1_0".

- [ ] Step 1: Write the protocol contract test.

Create a pure Java test with these assertions:

~~~java
@Test
public void onePointZeroUsesVersionedProtocol() {
    assertEquals("1.0.0", HorizonRadioProtocol.VERSION);
    assertEquals("horizonradio_1_0", HorizonRadioProtocol.CHANNEL_NAME);
}
~~~

- [ ] Step 2: Run the focused test to verify it fails.

~~~bash
./gradlew test --tests com.horizonradio.core.protocol.HorizonRadioProtocolTest --no-daemon
~~~

Expected: compilation fails because HorizonRadioProtocol does not yet exist.

- [ ] Step 3: Add the centralized protocol constants.

Implement:

~~~java
package com.horizonradio.core.protocol;

public final class HorizonRadioProtocol {
    public static final String VERSION = "1.0.0";
    public static final String CHANNEL_NAME = "horizonradio_1_0";

    private HorizonRadioProtocol() {
    }
}
~~~

- [ ] Step 4: Apply the constants to Forge metadata and networking.

Use HorizonRadioProtocol.VERSION for the @Mod version and HorizonRadioProtocol.CHANNEL_NAME in NetworkRegistry.INSTANCE.newSimpleChannel(...). Keep all existing packet discriminators and field order unchanged. Update mcmod.info to expand the modVersion property and set its url to https://github.com/Justronaut716/HorizonRadio.

- [ ] Step 5: Run protocol and packet tests.

~~~bash
./gradlew test --tests com.horizonradio.core.protocol.HorizonRadioProtocolTest --tests com.horizonradio.network.PacketRoundTripTest --no-daemon
~~~

Expected: both tests pass, with the packet round-trip assertions unchanged.

- [ ] Step 6: Commit the release boundary.

~~~bash
git add src/main/java/com/horizonradio/core/protocol src/test/java/com/horizonradio/core/protocol src/main/java/com/horizonradio/HorizonRadio.java src/main/java/com/horizonradio/network/HorizonRadioNetwork.java src/main/resources/mcmod.info
git commit -m "feat: establish HorizonRadio 1.0 protocol"
~~~

---

### Task 3: Extract dependency-clean core classes

**Files:**
- Move: src/main/java/com/horizonradio/HorizonRadioConfig.java → src/main/java/com/horizonradio/core/config/HorizonRadioConfig.java
- Move: src/main/java/com/horizonradio/model/DurationParser.java → src/main/java/com/horizonradio/core/model/DurationParser.java
- Move: src/main/java/com/horizonradio/model/PlaylistEntry.java → src/main/java/com/horizonradio/core/model/PlaylistEntry.java
- Move: src/main/java/com/horizonradio/model/SearchResult.java → src/main/java/com/horizonradio/core/model/SearchResult.java
- Move: src/main/java/com/horizonradio/client/audio/AudioChunkAssembler.java → src/main/java/com/horizonradio/core/audio/AudioChunkAssembler.java
- Move: src/main/java/com/horizonradio/client/audio/AudioPlayerState.java → src/main/java/com/horizonradio/core/audio/AudioPlayerState.java
- Move: src/main/java/com/horizonradio/server/PlaylistState.java → src/main/java/com/horizonradio/core/server/PlaylistState.java
- Move: src/main/java/com/horizonradio/server/PlaylistImportService.java → src/main/java/com/horizonradio/core/server/PlaylistImportService.java
- Move: src/main/java/com/horizonradio/server/ChartCache.java → src/main/java/com/horizonradio/core/server/ChartCache.java
- Modify imports across src/main/java/com/horizonradio/** and src/test/java/com/horizonradio/**.
- Move pure tests under src/test/java/com/horizonradio/core/ to match their new packages.

**Interfaces:**
- Consumes: the existing public model/audio APIs and the package-private server-state APIs used by PlaylistManager.
- Produces: core classes that import only Java SE, Gson, and project-owned core types; no core class imports Forge, Minecraft, LWJGL, Java Sound, GTNHLib, or GregTech.

- [ ] Step 1: Add the core dependency audit.

Use this audit after the package moves:

~~~bash
rg -n '^(import|package) (cpw\.mods\.fml|net\.minecraft|org\.lwjgl|javax\.sound|com\.gtnewhorizons|gregtech)' src/main/java/com/horizonradio/core
~~~

Expected: no matches after the move.

- [ ] Step 2: Move the pure public classes and update package declarations.

Use git mv for the config, model, and audio classes. Update each package declaration and every import in CommonProxy, HorizonRadio, PlaylistManager, HorizonRadioClient, HorizonRadioScreen, AudioPlayer, the packet classes, and tests. Preserve method signatures and behavior.

- [ ] Step 3: Move the pure server state and parsing classes.

Move PlaylistState, PlaylistImportService, and ChartCache into com.horizonradio.core.server. Make the class and each constructor/static or instance method called from PlaylistManager public; leave helper methods private. Do not change playlist rules, chart TTL, URL parsing, or late-join state transitions.

The public boundary required by PlaylistManager includes:

~~~java
public PlaylistState(int maxPlaylistSize);
public List<PlaylistEntry> snapshot();
public boolean add(PlaylistEntry entry);
public boolean isPlaying();
public boolean isPaused();
public boolean isSyncing();
public boolean isLooping();
public boolean isShuffling();
public int size();
public int getCurrentIndex();
public int findIndex(String videoId);
public int remove(String videoId);
public PlaylistEntry get(int index);
public void clear();
~~~

Retain the remaining existing state methods with public visibility where PlaylistManager calls them; do not rename or alter their parameter or return types.

- [ ] Step 4: Update tests to the new packages.

Move and update package declarations for DurationParserTest, PlaylistStateTest, ChartCacheTest, YouTubeParserTest, AudioChunkAssemblyTest, and AudioPlayerStateTest. Keep PacketRoundTripTest, GuiLayoutTest, ServerEventsStructureTest, and the Forge-facing server tests in their existing adapter packages, updating imports only.

- [ ] Step 5: Run pure unit tests before touching lifecycle code.

~~~bash
./gradlew test --tests 'com.horizonradio.core.*' --no-daemon
~~~

Expected: all moved model, parser, state, cache, and audio-state tests pass with no behavior changes.

- [ ] Step 6: Run the full test suite and fix only import/package regressions.

~~~bash
./gradlew test --no-daemon
~~~

Expected: the existing packet, GUI layout, server event structure, download command, and YouTube tests pass. Do not change production behavior in response to a package-only failure.

- [ ] Step 7: Verify the core dependency boundary.

~~~bash
rg -n '^(import|package) (cpw\.mods\.fml|net\.minecraft|org\.lwjgl|javax\.sound|com\.gtnewhorizons|gregtech)' src/main/java/com/horizonradio/core
~~~

Expected: no output and exit status 1 from rg.

- [ ] Step 8: Commit the core extraction.

~~~bash
git add src/main/java/com/horizonradio src/test/java/com/horizonradio
git commit -m "refactor: isolate portable HorizonRadio core"
~~~

---

### Task 4: Add the project-owned optional integration SPI

**Files:**
- Create: src/main/java/com/horizonradio/core/integration/HorizonRadioIntegration.java
- Create: src/main/java/com/horizonradio/core/integration/HorizonRadioIntegrationContext.java
- Create: src/main/java/com/horizonradio/integration/IntegrationManager.java
- Create: src/main/java/com/horizonradio/integration/GtnhEnvironmentDetector.java
- Create: src/main/java/com/horizonradio/integration/GtnhIntegration.java
- Create: src/test/java/com/horizonradio/integration/IntegrationManagerTest.java

**Interfaces:**
- Consumes: HorizonRadioConfig from com.horizonradio.core.config and the Forge 1.7.10 mod-discovery API only in the adapter package.
- Produces: a project-owned lifecycle SPI with no GTNH types in its signatures.

Define these exact core contracts:

~~~java
public interface HorizonRadioIntegration {
    String getId();

    void onPreInit(HorizonRadioIntegrationContext context);

    void onInit(HorizonRadioIntegrationContext context);

    void onPostInit(HorizonRadioIntegrationContext context);
}
~~~

~~~java
public final class HorizonRadioIntegrationContext {
    private final String modVersion;
    private final HorizonRadioConfig config;

    public HorizonRadioIntegrationContext(String modVersion, HorizonRadioConfig config) {
        this.modVersion = modVersion;
        this.config = config;
    }

    public String getModVersion() {
        return modVersion;
    }

    public HorizonRadioConfig getConfig() {
        return config;
    }
}
~~~

- [ ] Step 1: Write the lifecycle manager test with an injected recording integration.

The test must construct IntegrationManager from a list containing a recording implementation, call each lifecycle method once, and assert the events occur in preInit, init, postInit order with the same context instance.

- [ ] Step 2: Run the focused test to verify it fails.

~~~bash
./gradlew test --tests com.horizonradio.integration.IntegrationManagerTest --no-daemon
~~~

Expected: compilation fails because the SPI and manager do not exist.

- [ ] Step 3: Implement the core SPI and context.

Keep both classes free of Forge, Minecraft, GTNHLib, GregTech, and client-only imports. The context may expose only the version and already-loaded HorizonRadio configuration in this release.

- [ ] Step 4: Implement IntegrationManager.

Give it a constructor accepting List<HorizonRadioIntegration>, copy the list defensively, and expose:

~~~java
public void onPreInit(HorizonRadioIntegrationContext context);
public void onInit(HorizonRadioIntegrationContext context);
public void onPostInit(HorizonRadioIntegrationContext context);
~~~

Each method must invoke the corresponding callback once per registered integration in list order.

- [ ] Step 5: Implement GTNH capability detection without GTNH imports.

GtnhEnvironmentDetector may import cpw.mods.fml.common.Loader and must use the GTNHLib mod ID gtnhlib:

~~~java
public static boolean isAvailable() {
    return Loader.isModLoaded("gtnhlib");
}
~~~

GtnhIntegration must implement the core SPI, return the ID gtnh, and contain no com.gtnewhorizons, gregtech, or GTNHLib import. Its callbacks should log that the optional capability is available; they must not register gameplay content.

- [ ] Step 6: Add discovery as a Forge-adapter factory.

Add IntegrationManager.discover() or an equivalent adapter-only factory that returns a manager containing GtnhIntegration when GtnhEnvironmentDetector.isAvailable() is true and an empty manager otherwise. Keep the injected-list constructor for deterministic unit tests.

- [ ] Step 7: Run the focused and full tests.

~~~bash
./gradlew test --tests com.horizonradio.integration.IntegrationManagerTest --no-daemon
./gradlew test --no-daemon
~~~

Expected: lifecycle ordering and all existing tests pass.

- [ ] Step 8: Commit the optional integration seam.

~~~bash
git add src/main/java/com/horizonradio/core/integration src/main/java/com/horizonradio/integration src/test/java/com/horizonradio/integration
git commit -m "feat: add optional GTNH integration seam"
~~~

---

### Task 5: Wire the integration lifecycle into Forge initialization

**Files:**
- Modify: src/main/java/com/horizonradio/HorizonRadio.java
- Modify: src/main/java/com/horizonradio/CommonProxy.java
- Modify: src/main/java/com/horizonradio/client/ClientProxy.java
- Preserve: src/main/java/com/horizonradio/server/ServerEvents.java unchanged.
- Preserve: src/test/java/com/horizonradio/server/ServerEventsStructureTest.java unchanged.

**Interfaces:**
- Consumes: IntegrationManager, HorizonRadioIntegrationContext, HorizonRadioConfig, and the existing proxy lifecycle.
- Produces: one integration manager created during pre-initialization and invoked exactly once per Forge lifecycle phase.

- [ ] Step 1: Add lifecycle state to HorizonRadio.

Add private fields for IntegrationManager and HorizonRadioIntegrationContext. Keep HorizonRadio.getConfig() behavior unchanged for existing server code.

- [ ] Step 2: Invoke discovery after configuration is loaded.

Keep the current pre-initialization order for network registration and proxy.preInit(event). Immediately after proxy.preInit(event), construct the context with HorizonRadioProtocol.VERSION and HorizonRadio.getConfig(), discover integrations, and call onPreInit(context). Register ServerEvents once as before.

- [ ] Step 3: Invoke integration callbacks around existing init phases.

Call integrationManager.onInit(context) after proxy.init(event) and integrationManager.onPostInit(context) after proxy.postInit(event). Do not move client keybind registration, server services, packet registration, or proxy dispatch across sides.

- [ ] Step 4: Add a source-level lifecycle regression assertion.

Run ServerEventsStructureTest unchanged to preserve its existing server-registration assertion. Do not add a source-string test for HorizonRadio.java; verify callback ordering through the Forge lifecycle smoke test in Task 8.

- [ ] Step 5: Run all unit tests and the common/server scope audit.

~~~bash
./gradlew test --no-daemon
rg -n 'import (net\.minecraft\.client|org\.lwjgl|javax\.sound)' src/main/java/com/horizonradio src/main/java/com/horizonradio/core
~~~

Expected: client-only imports remain in client/audio adapter classes only; core and common/server/network paths remain free of client-only imports.

- [ ] Step 6: Commit lifecycle wiring.

~~~bash
git add src/main/java/com/horizonradio/HorizonRadio.java src/main/java/com/horizonradio/CommonProxy.java src/main/java/com/horizonradio/client/ClientProxy.java src/main/java/com/horizonradio/server/ServerEvents.java src/test/java/com/horizonradio/server/ServerEventsStructureTest.java
git commit -m "feat: wire optional integrations into Forge lifecycle"
~~~

---

### Task 6: Verify Java compatibility and dependency isolation

**Files:**
- Modify: gradle.properties to keep the Java mode aligned with the measured build.
- Modify: dependencies.gradle to keep dependency scopes aligned with the measured artifact.
- Modify: docs/COMPATIBILITY.md with measured results, not anticipated results.
- Create: .github/workflows/build.yml for the Java 25 convention build.

**Interfaces:**
- Consumes: the complete source tree and the one JAR produced by the convention build.
- Produces: evidence that no required runtime artifact depends on GTNHLib or GregTech and that common classes remain Java-8-compatible.

- [ ] Step 1: Audit source imports.

~~~bash
rg -n 'import (com\.gtnewhorizons|gregtech|gtnhlib)' src/main/java
rg -n 'import (cpw\.mods\.fml|net\.minecraft|org\.lwjgl|javax\.sound)' src/main/java/com/horizonradio/core
~~~

Expected: the first command has no GTNH/GregTech imports; the second command has no Forge, Minecraft, LWJGL, or Java Sound imports in core.

- [ ] Step 2: Audit dependency declarations.

~~~bash
rg -n -i 'gtnh|gregtech|gtlib|shadow|runtimeOnly|compileOnly|devOnly' gradle.properties repositories.gradle dependencies.gradle build.gradle.kts settings.gradle.kts
~~~

Expected: no GTNH library is declared as a required runtime dependency. Any optional dependency declaration must use the template’s compile-only/development-only configuration and must have a reason in the file comment.

- [ ] Step 3: Build the artifact under Java 25.

~~~bash
java -version
./gradlew clean test build --no-daemon
~~~

Expected: Java reports version 25 and Gradle ends with BUILD SUCCESSFUL.

- [ ] Step 4: Inspect the artifact.

~~~bash
jar tf build/libs/horizonradio-1.0.0.jar | rg '(^|/)(mcmod\.info|com/horizonradio/|gtnh|gregtech)'
unzip -p build/libs/horizonradio-1.0.0.jar mcmod.info
~~~

Expected: the artifact contains HorizonRadio classes and metadata, contains no shaded GTNH/GregTech library classes, and reports mod ID horizonradio, name HorizonRadio, version 1.0.0, and Minecraft 1.7.10.

- [ ] Step 5: Run a Java 8 launch smoke test.

From a Forge 1.7.10 test installation with Forge 10.13.4.1614, no GTNHLib, and no GregTech, install the same build/libs/horizonradio-1.0.0.jar on both server and client. Start the dedicated server with Java 8 and confirm it reaches the normal Done state without NoClassDefFoundError, UnsupportedClassVersionError, or mod-load failure.

- [ ] Step 6: Add the Java 25 build workflow.

Create .github/workflows/build.yml with checkout, Java 25 setup, executable Gradle wrapper permissions, and:

~~~yaml
- run: ./gradlew clean test build --no-daemon
~~~

Do not claim that a Java 25 CI build proves Java 8 runtime compatibility; retain the dedicated Java 8 launch smoke test as a separate release gate.

- [ ] Step 7: Commit the compatibility audit and build workflow.

~~~bash
git add gradle.properties dependencies.gradle docs/COMPATIBILITY.md .github/workflows/build.yml
git commit -m "test: verify portable GTNH artifact"
~~~

---

### Task 7: Update architecture, installation, and migration documentation

**Files:**
- Modify: README.md
- Modify: docs/ARCHITECTURE.md
- Modify: docs/COMPATIBILITY.md
- Modify: src/main/resources/mcmod.info

**Interfaces:**
- Consumes: measured build/runtime results from Tasks 1–6 and the final artifact metadata.
- Produces: documentation that gives a new user one installation path and explains the GTNH/standalone matrix without implying a hard GTNH dependency.

- [ ] Step 1: Update the README requirements and build instructions.

Document Java 25 as the development/GTNH path and Java 8 as the portable runtime floor. Replace the old Java-8-only Gradle instructions with:

~~~bash
./gradlew clean test build --no-daemon
~~~

State that the same horizonradio-1.0.0.jar is installed on the server and every client, that yt-dlp and ffmpeg remain server prerequisites, and that GTNHLib/GregTech are not required for the first release.

- [ ] Step 2: Update the architecture diagram and package map.

Document the flow:

~~~text
Forge @Mod lifecycle
        |
  HorizonRadio + IntegrationManager
        |
  Portable core contracts/state  <---  optional GTNH capability
        |
  CommonProxy / ClientProxy / ServerEvents
        |
  SimpleNetworkWrapper + Java Sound client adapter
~~~

Keep the existing packet ID table, audio state machine, server-authority rules, and no-world-content decisions. Add the versioned channel name horizonradio_1_0 and explain that optional integrations receive project-owned context only.

- [ ] Step 3: Update the compatibility matrix and verification record.

Add rows for:

~~~text
Environment                  Requirement                                      Evidence
Standalone Forge 1.7.10      Java 8, Forge 10.13.4.1614, no GTNH mods       Dedicated-server/client smoke test
GTNH                         Java 25, same JAR, GTNHLib optional              Pinned GTNH pack smoke test
Build                        Java 25, GTNH convention wrapper                clean test build output
~~~

Record the exact Java version, GTNH pack version, command, and result only after running each check. Keep unresolved infrastructure failures explicitly marked as pending rather than converting them into success claims.

- [ ] Step 4: Document the 1.0.0 breaking boundary.

State that pre-1.0 clients do not connect because the channel changed, old configurations are not automatically reinterpreted, and users should back up config/horizonradio.json, config/horizonradio-charts.json, and the download directory before upgrading.

- [ ] Step 5: Run documentation and identity scans.

~~~bash
rg -n -i '1\.21\.11|Fabric' README.md docs src/main/resources || true
rg -n 'HorizonRadio|horizonradio|1\.0\.0' README.md docs src/main/resources/mcmod.info
~~~

Remove any stale platform, version, or repository references found in active documentation. Repeat the repository-wide retired-identity scan from the earlier rename checkpoint without copying the retired value into tracked files. Do not remove historical technical context that is clearly labeled as inactive unless it creates a user-facing contradiction.

- [ ] Step 6: Commit the documentation update.

~~~bash
git add README.md docs/ARCHITECTURE.md docs/COMPATIBILITY.md src/main/resources/mcmod.info
git commit -m "docs: document GTNH and standalone compatibility"
~~~

---

### Task 8: Run the final standalone and GTNH smoke matrix

**Files:**
- Modify: docs/COMPATIBILITY.md with final measured results.
- Preserve: docs/ARCHITECTURE.md unless a measured runtime difference requires a factual correction.
- No production source changes are permitted in this task unless a smoke test identifies a reproducible defect; each defect fix gets its own test and commit before rerunning this matrix.

**Interfaces:**
- Consumes: build/libs/horizonradio-1.0.0.jar, the portable core, Forge adapter, optional integration seam, and documentation.
- Produces: release evidence for Java 8 standalone Forge and Java 25 GTNH usage.

- [ ] Step 1: Verify the clean repository and artifact.

~~~bash
git status --short
./gradlew clean test build --no-daemon
jar tf build/libs/horizonradio-1.0.0.jar | rg 'mcmod.info|horizonradio|gtnh|gregtech'
~~~

Expected: only intentional source/documentation changes are committed, the build succeeds, and the artifact contains no bundled GTNH/GregTech library.

- [ ] Step 2: Exercise the standalone Forge Java 8 path.

Use a disposable Forge 10.13.4.1614 client/server installation with no GTNHLib or GregTech and the same JAR on both sides. Start the server with Java 8, connect with the client, and verify:

- server reaches the normal ready state;
- a player can open the screen after a world is loaded;
- search and chart requests return results or controlled empty/error states;
- add, remove, reorder, clear, loop, shuffle, skip, previous, seek, pause, and resume remain server-authoritative;
- audio chunks assemble and playback starts when yt-dlp, ffmpeg, and Java Sound are available;
- disconnect/reconnect clears and rehydrates client state without a crash;
- logs contain no missing GTNH/GregTech class error.

- [ ] Step 3: Exercise the pinned GTNH Java 25 path.

Use a disposable GTNH 2.9.0-beta-2 client/server instance or the project’s available pinned GTNH test instance, install the exact same JAR, and run the same functional checklist. Confirm that GTNHLib is detected when present, no pack-specific content is registered, and the standalone fallback remains the default HorizonRadio behavior.

- [ ] Step 4: Validate optional dependency absence and presence separately.

Run one startup with GTNHLib physically absent and one with GTNHLib present. Compare logs for class-loading errors and confirm that GtnhIntegration callbacks run only in the detected environment.

- [ ] Step 5: Record evidence.

Update docs/COMPATIBILITY.md with the exact Java versions, Forge/GTNH versions, artifact hash if available, test dates, commands, and pass/fail results. If an environment cannot be executed because a dependency mirror or pack is unavailable, record the exact blocker and leave that criterion pending.

- [ ] Step 6: Perform the final repository audit.

~~~bash
git diff --check HEAD~8..HEAD
rg -n -i '1\.21\.11|Fabric' . --glob '!.git/**' --glob '!build/**' --glob '!run/**'
git status --short
~~~

Expected: no whitespace errors, no retired identity matches, and a clean working tree.

- [ ] Step 7: Create the release commit only after every required criterion is evidenced.

~~~bash
git add README.md docs/ARCHITECTURE.md docs/COMPATIBILITY.md
git commit -m "release: prepare HorizonRadio 1.0.0"
~~~

Do not push or publish from this plan unless separately requested after the final verification report.

## References

- GTNH ExampleMod 1.7.10: https://github.com/GTNewHorizons/ExampleMod1.7.10
- GTNH ExampleMod migration guide: https://github.com/GTNewHorizons/ExampleMod1.7.10/blob/master/docs/migration.md
- GTNH ExampleMod Java/toolchain properties: https://raw.githubusercontent.com/GTNewHorizons/ExampleMod1.7.10/master/gradle.properties
- GTNH ExampleMod dependency conventions: https://raw.githubusercontent.com/GTNewHorizons/ExampleMod1.7.10/master/dependencies.gradle
- GTNHLib: https://github.com/GTNewHorizons/GTNHLib

## Completion Checklist

- [ ] GTNH convention build replaces the legacy ForgeGradle script.
- [ ] Java 25 development/build succeeds.
- [ ] The common artifact remains Java-8-compatible and passes a real Java 8 launch test.
- [ ] One JAR works both without GTNH and inside the pinned GTNH environment.
- [ ] Optional integration has no GTNH/GregTech hard dependency.
- [ ] The versioned 1.0.0 protocol boundary is explicit.
- [ ] Existing radio behavior and server-authoritative workflows remain verified.
- [ ] Documentation and artifact metadata describe the same compatibility matrix.
- [ ] No retired project identity remains in tracked source, docs, metadata, or artifact output.
