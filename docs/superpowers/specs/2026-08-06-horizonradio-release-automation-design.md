# HorizonRadio Release Automation Design

**Date:** 2026-08-06

**Status:** Approved for implementation

## Goal

Provide a repeatable release process that can bump a semantic version, build and validate the mod, push the release commit and tag, and publish the deployable JAR through GitHub Actions with one command.

The process will keep one reobfuscated HorizonRadio JAR as the default artifact for ordinary Forge 1.7.10 installations and GTNH installations running Java 17 or newer. A separate artifact will only be introduced if runtime verification later proves that the shared artifact cannot work in both environments.

## Current context

- The project uses the GTNH convention build through `com.gtnewhorizons.gtnhconvention`.
- The Gradle wrapper is pinned to Gradle `9.3.1`.
- Java 25 is the development/build JDK required by the current convention build.
- `gradle.properties` contains the release version in `modVersion`.
- `gradleTokenVersion=VERSION` causes the convention build to generate `com.horizonradio.Tags.VERSION` from the effective build version.
- `HorizonRadioProtocol.VERSION` is currently duplicated as a Java string literal and must be connected to the generated version token.
- The convention build produces development, sources, and reobfuscated JARs. Only the plain `horizonradio-<version>.jar` is deployable.
- The current CI workflow builds and tests but does not publish GitHub Releases.

## Release interface

### Local version bump

`bumpVersion` is a local, non-publishing task. It changes only the project version and prints the resulting version.

```bash
./gradlew bumpVersion -Pbump=patch
./gradlew bumpVersion -Pbump=minor
./gradlew bumpVersion -Pbump=major
./gradlew bumpVersion -PnewVersion=1.2.3
```

The `bump` and `newVersion` properties are mutually exclusive. Versions use the strict `MAJOR.MINOR.PATCH` form. Invalid values, unsupported bump names, or a version below the current version fail without writing a new version.

### Remote release

`release` is the explicit remote-publishing task. It accepts the same `bump` or `newVersion` property and defaults the remote name to `origin`.

```bash
./gradlew release -Pbump=patch
./gradlew release -PnewVersion=1.2.3
```

The task performs these operations in order:

1. Require a clean worktree and a non-detached branch.
2. Calculate and validate the next version.
3. Reject an existing local or remote `v<version>` tag.
4. Update `gradle.properties`.
5. Run formatting and a full versioned Java 25 build with `VERSION=<version>`.
6. Confirm that the plain `build/libs/horizonradio-<version>.jar` exists and contains matching mod metadata.
7. Commit the intentional version/formatting changes with a release commit.
8. Push the current branch without force.
9. Create an annotated `v<version>` tag and push it without force.

The task will not delete, reset, or force-update any Git ref. A failed build stops before commit, branch push, or tag creation. A failed remote push leaves the local commit or tag available for a later explicit retry.

## Version consistency

`gradle.properties` remains the only checked-in version source. `HorizonRadioProtocol.VERSION` will reference the generated `com.horizonradio.Tags.VERSION`, so the following values come from the same effective version:

- Forge `@Mod` metadata;
- expanded `mcmod.info` metadata;
- generated build artifact name;
- integration context version;
- protocol version constant.

The protocol channel remains `horizonradio_1_0` for ordinary patch and minor releases. A protocol-breaking release must deliberately update the channel and migration documentation rather than deriving a new channel automatically from every version bump.

## GitHub Actions release workflow

Add a dedicated release workflow triggered only by pushed tags matching `v*.*.*`.

The workflow will:

1. Check out the tagged commit.
2. Provision Temurin Java 25 and use the Gradle wrapper.
3. Extract and validate the version from the tag.
4. Confirm that `gradle.properties` contains the same version.
5. Run the clean step separately, then run the versioned formatting check, tests, and build.
6. Verify `build/libs/horizonradio-<version>.jar` and generate a SHA-256 checksum.
7. Create a GitHub Release with generated notes and attach only the plain deployable JAR and checksum.

The workflow will use `contents: write` and the repository-provided GitHub token for release creation. It will not publish Maven, Modrinth, or CurseForge packages; those remain separate publishing decisions.

## Compatibility documentation

Update the README and compatibility notes to distinguish:

- build-time Java 25, required by the current GTNH convention build;
- ordinary Forge 1.7.10 runtime compatibility on the Java 8-compatible target;
- GTNH runtime compatibility on Java 17+;
- the fact that both environments install the same reobfuscated JAR;
- the absence of a hard GTNHLib or GregTech dependency.

The documentation will state that the shared artifact is the intended compatibility contract while retaining honest runtime-test status. It will not describe Java 25 as a second mod artifact or imply that GTNH requires a separate build.

## Verification strategy

The implementation must provide evidence for:

- strict version parsing and patch/minor/major calculations;
- rejection of invalid or conflicting version inputs;
- synchronization between the generated version token and `HorizonRadioProtocol.VERSION`;
- successful versioned artifact assembly and metadata expansion;
- release workflow syntax and tag/version validation;
- the existing full Java 25 test/build suite;
- absence of a second production JAR in the published GitHub Release.

The remote release task will not be executed during implementation. Its local validation path will be exercised without pushing a branch or tag, and the final handoff will document the exact command and its external effects.

## Success criteria

- `./gradlew bumpVersion -Pbump=patch` produces the expected next version.
- `./gradlew release -Pbump=patch` is the documented one-command remote release path.
- A pushed `vX.Y.Z` tag produces a GitHub Release containing `horizonradio-X.Y.Z.jar` and its checksum.
- The artifact metadata, generated protocol version, and tag all agree.
- The same plain JAR remains the documented deployment artifact for ordinary Forge and GTNH Java 17+.
- Existing tests and the complete Java 25 build remain green.
