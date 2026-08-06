# HorizonRadio GTNH-Compatible Portable Mod Design

**Date:** 2026-08-05
**Status:** Approved direction; awaiting review of this written specification
**Target release:** `1.0.0`

## Context

HorizonRadio is currently a Minecraft 1.7.10 Forge mod built around the legacy ForgeGradle 1.2 setup and Java 8 assumptions. Its existing feature set is a server-authoritative, shared YouTube music player using Forge networking, client audio playback, playlist/search/download services, and GUI controls. It intentionally has no GregTech dependency and adds no world content.

The next release should be a GTNH-grade mod in build quality and runtime ergonomics while remaining usable in ordinary Forge 1.7.10 environments. “GTNH mod” therefore means GTNH-native packaging, toolchain, and optional integration—not a mandatory GregTech or GTNH pack dependency.

This is a deliberate pre-1.0 breaking boundary. The current implementation is treated as the `0.0.1` line; the migrated foundation will be `1.0.0`.

## Goals

- Adopt the current GTNH ExampleMod/convention build structure.
- Use Java 25 for development and GTNH validation.
- Publish one JAR that can run in GTNH and in a non-GTNH Forge 1.7.10 installation.
- Keep the portable runtime target Java-8-compatible unless compatibility testing proves a broader safe target.
- Preserve HorizonRadio’s current radio behavior and server-authoritative model.
- Create a stable boundary for future GTNH-specific UI, configuration, recipes, progression, or other integrations.
- Keep GTNH-specific dependencies optional and isolated.
- Make the `1.0.0` protocol and state boundary explicit rather than pretending to support old clients.

## Non-goals for 1.0.0

- No mandatory GregTech, GTNHLib, ModularUI, or pack-specific dependency.
- No GregTech recipes, progression integration, quest content, machines, blocks, items, or other world content.
- No mixins, coremods, access transformers, or invasive Minecraft patches unless a later feature proves one necessary.
- No separate GTNH-only artifact unless the portable artifact becomes technically impossible.
- No broad rewrite of the radio feature, audio backend, or external service behavior merely to satisfy the build migration.
- No promise of compatibility with pre-1.0 clients or incompatible persisted state.

## Architecture decision

Use one portable artifact with three logical layers. These may initially be package/source boundaries rather than separate Gradle subprojects; the separation is about dependency direction and class loading, not about adding unnecessary repository complexity.

```text
HorizonRadio JAR
├── Portable core
│   └── radio domain, state, protocol model, and project-owned extension interfaces
├── Forge 1.7.10 adapter
│   └── lifecycle, networking, commands, GUI, client audio, and server hooks
└── Optional GTNH adapter
    └── capability-detected GTNH/GTNHLib enhancements with a no-op fallback
```

### Portable core

The core owns radio behavior and project-level contracts. It must not import Forge, GregTech, GTNHLib, ModularUI, or any other optional ecosystem type. Its common API must remain compatible with the selected Java 8 target and must not expose optional types in method signatures, fields, annotations, or static initializers.

The core will define only the extension points needed to prevent another foundational refactor. Candidate contracts include configuration presentation, enhanced UI services, and future content/progression registration. The first release implements the default behavior only; it does not add GTNH-specific features just because the hooks exist.

### Forge adapter

The Forge 1.7.10 layer remains the required runtime adapter. It owns Forge event registration, the existing `SimpleNetworkWrapper` integration, command/event wiring, GUI opening, client audio lifecycle, and the server/client service boundaries already present in the project.

The current behavior should move behind the project-owned contracts incrementally. This keeps the migration focused: the radio feature remains recognizable while its environment-specific assumptions become explicit.

### Optional GTNH adapter

The GTNH adapter is compiled as an optional capability and packaged without making its dependencies required at runtime. It must be isolated so a standalone Forge installation can start without GTNHLib or other GTNH-only classes present.

The bootstrap may detect supported capabilities, but common classes must never directly reference optional types. The adapter should provide a project-owned implementation of the relevant interface and otherwise leave the default implementation active. The absent-dependency startup path is a first-class test case.

Future GTNH features should follow this boundary:

- enhanced configuration or UI is an adapter capability;
- GTNH-specific recipes or progression are opt-in registration capabilities;
- any GregTech API usage stays outside the portable core;
- a missing optional capability always falls back cleanly or reports an actionable warning.

## Build and toolchain migration

The build should be migrated from the current hand-maintained ForgeGradle setup to the current GTNH ExampleMod layout and convention plugin. The exact template files should be taken from the maintained starter at implementation time rather than reconstructed manually.

The migration should include:

- the current GTNH convention build and wrapper;
- the template’s repository and dependency separation;
- `.java-version` set to the supported development JDK, currently Java 25 in the template;
- `gradle.properties` adapted to HorizonRadio’s metadata and Minecraft 1.7.10 target;
- `mcmod.info` using the template metadata placeholders;
- shared GTNH build resources and publishing metadata where applicable;
- custom build logic moved into the template-supported addon location rather than modifying convention internals.

Start with the template’s Java-8-compatible modern-syntax path. This allows Java 25 development without making the common artifact depend on Java-25-only bytecode. Enable the template’s JVM-downgrader path only if a concrete feature needs newer standard-library APIs and the resulting standalone Java 8 behavior is proven by tests.

Dependencies must be scoped by purpose. Forge and required runtime dependencies remain normal runtime inputs. Optional GTNH libraries are development/compile-only inputs unless a later feature explicitly requires a bundled, licensed runtime dependency. The first migration should not add GTNHLib merely to label the project GTNH-compatible.

The build must keep `usesMixins=false` unless a later approved feature changes that decision. Publishing should produce a single clearly named HorizonRadio artifact with no environment-specific classifier required for normal users.

## Version and compatibility policy

- Keep `modId` as `horizonradio`.
- Use `HorizonRadio` consistently for display name, project metadata, and user-facing documentation.
- Set the migrated release to `1.0.0`.
- Continue targeting Minecraft `1.7.10` and the supported Forge version unless the implementation audit finds a template-required update.
- Bump and explicitly identify the network protocol for `1.0.0`; pre-1.0 clients are not expected to connect.
- Treat incompatible configuration or playlist state as a clean-break concern. Preserve data only when the existing format can be read without ambiguity; never silently reinterpret it.
- Document the upgrade boundary, backup guidance, and any state reset in the migration notes.

The mod must not introduce a hard GregTech dependency as part of this work. Compatibility with a GTNH pack is validated by loading and exercising the mod in that environment, not by making the radio unusable elsewhere.

## Delivery stages

### Stage 1: Baseline and migration inventory

Record the current `0.0.1` build commands, artifact contents, configuration files, packet protocol, and core radio workflows. Identify all Java 8 assumptions, Forge-only references, persisted state, and custom Gradle behavior before changing the build.

### Stage 2: GTNH build foundation

Import the maintained GTNH starter structure, adapt project metadata and dependencies, and make a fresh checkout build under Java 25. Keep the source behavior unchanged during this stage so toolchain failures remain distinguishable from application regressions.

### Stage 3: Portability boundary

Separate portable core contracts from the Forge adapter, introduce the default/no-op capability path, and add the optional GTNH adapter boundary without implementing pack-specific gameplay features. Verify that optional classes are not loaded when their dependencies are absent.

### Stage 4: Runtime and release verification

Run the standalone Forge matrix, GTNH smoke test, client/server networking tests, audio lifecycle tests, and clean-checkout build. Update documentation, release metadata, and migration notes only after the matrix is green.

## Verification strategy

The implementation is complete only when all of these are demonstrated:

- a clean checkout completes the GTNH convention build;
- Java 25 development/build succeeds;
- the published JAR starts without GTNH or GregTech installed;
- the same JAR starts in a GTNH environment without a separate build;
- optional dependency absence causes no class-loading or startup failure;
- server startup, client join, playlist control, search/download, synchronized playback, disconnect, and reconnect still work;
- the `1.0.0` protocol boundary rejects or clearly separates incompatible pre-1.0 clients;
- the artifact contains no accidental build output, local configuration, or development-only dependency that was meant to remain compile-only;
- repository searches show no obsolete project identity or stale release metadata.

Testing should cover at least Java 8 for the portable floor and Java 25 for the GTNH/development path. A real GTNH pack smoke test is required in addition to a minimal non-GTNH Forge test because build success alone cannot validate optional class loading or pack interaction.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Legacy ForgeGradle behavior differs from the GTNH convention build | Port build structure first, preserve application source, and keep each build change isolated. |
| Java 25 tooling produces code that fails on the portable floor | Start with the Java-8-compatible path and run a real Java 8 launch test before enabling newer APIs. |
| Optional GTNH classes are resolved during Forge discovery | Keep optional types out of common signatures/annotations and test startup with dependencies physically absent. |
| A future UI/recipe integration leaks GTNH assumptions into core | Require each future feature to implement a project-owned capability interface with a default fallback. |
| The clean break surprises existing users | Publish the `1.0.0` boundary, backup guidance, protocol change, and state migration policy prominently. |

## Deferred decisions

The following are intentionally left open for future feature work, not for the foundation migration:

- which GTNHLib capabilities, if any, should be adopted;
- whether enhanced UI should use GTNHLib, ModularUI, or a project-owned Forge screen;
- whether GTNH-specific recipes/progression are desirable;
- whether JVM downgrading is needed beyond Java-8-compatible syntax;
- whether a second artifact is ever justified by a feature that cannot fit the portable runtime.

## References

- [GTNH ExampleMod 1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10)
- [GTNH ExampleMod migration guide](https://github.com/GTNewHorizons/ExampleMod1.7.10/blob/master/docs/migration.md)
- [GTNH ExampleMod Java/toolchain properties](https://raw.githubusercontent.com/GTNewHorizons/ExampleMod1.7.10/master/gradle.properties)
- [GTNH ExampleMod dependency conventions](https://raw.githubusercontent.com/GTNewHorizons/ExampleMod1.7.10/master/dependencies.gradle)
- [GTNHLib](https://github.com/GTNewHorizons/GTNHLib)
