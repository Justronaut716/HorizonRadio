# HorizonRadio Release Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add guarded SemVer bumping, one-command remote releases, tag-triggered GitHub Release publishing, and accurate shared-artifact compatibility documentation.

**Architecture:** Keep `gradle.properties` as the checked-in version source and make `HorizonRadioProtocol.VERSION` consume the GTNH-generated `Tags.VERSION`. Add focused release logic as an applied Gradle Kotlin script: `bumpVersion` only changes the version, while `release` validates, builds in a nested versioned Gradle invocation, commits, pushes, tags, and pushes the tag. A dedicated GitHub Actions workflow rebuilds the tag and publishes only the plain reobfuscated JAR plus its checksum.

**Tech Stack:** Gradle 9.3.1 wrapper, Gradle Kotlin DSL, GTNH convention build, Java 25 build toolchain, Git, GitHub Actions, GitHub CLI (`gh`) in the hosted runner.

## Global Constraints

- Minecraft target remains `1.7.10` with Forge `10.13.4.1614`.
- Java 25 is the development/build requirement; the intended runtime targets are ordinary Forge 1.7.10 on the Java 8-compatible path and GTNH on Java 17+.
- `gradle.properties` remains the only checked-in version source.
- The deployable artifact is the plain reobfuscated `build/libs/horizonradio-<version>.jar`; do not publish `-dev` or `-sources` JARs.
- GTNHLib and GregTech remain optional; no production dependency or shaded package may be added.
- Patch/minor version bumps must not automatically change the `horizonradio_1_0` protocol channel.
- Release commands must reject dirty worktrees, existing tags, invalid versions, and failed verification before any remote mutation.
- The existing user change to `src/main/resources/mcmod.info` must be preserved and included in the next implementation commit without changing its author values.
- Do not execute a real GitHub release, push, or tag during implementation verification.

---

### Task 1: Centralize the effective mod version

**Files:**
- Modify: `src/main/java/com/horizonradio/core/protocol/HorizonRadioProtocol.java`
- Test: `src/test/java/com/horizonradio/core/protocol/HorizonRadioProtocolTest.java`

**Interfaces:**
- Consumes: generated `com.horizonradio.Tags.VERSION`, produced by the existing `gradleTokenVersion=VERSION` convention setting.
- Produces: `HorizonRadioProtocol.VERSION == Tags.VERSION` for development and release builds.

- [ ] **Step 1: Write the failing version-source regression test**

Replace the hard-coded release assertion with a generated-token assertion while retaining the stable channel assertion:

```java
package com.horizonradio.core.protocol;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.horizonradio.Tags;

public class HorizonRadioProtocolTest {

    @Test
    public void modVersionComesFromGeneratedBuildTag() {
        assertEquals(Tags.VERSION, HorizonRadioProtocol.VERSION);
        assertEquals("horizonradio_1_0", HorizonRadioProtocol.CHANNEL_NAME);
    }
}
```

- [ ] **Step 2: Run the test with a deliberately different generated version**

Run:

```bash
VERSION=9.9.9 ./gradlew test --tests com.horizonradio.core.protocol.HorizonRadioProtocolTest --no-daemon
```

Expected: FAIL because generated `Tags.VERSION` is `9.9.9` while the current protocol constant is still `1.0.0`.

- [ ] **Step 3: Connect the protocol constant to the generated tag**

Add `import com.horizonradio.Tags;` and replace the literal with:

```java
public static final String VERSION = Tags.VERSION;
```

Keep `CHANNEL_NAME` exactly `horizonradio_1_0`.

- [ ] **Step 4: Run the focused test with the release override**

Run:

```bash
VERSION=9.9.9 ./gradlew test --tests com.horizonradio.core.protocol.HorizonRadioProtocolTest --no-daemon
```

Expected: PASS, proving that the compiled protocol version follows the generated build version while the channel remains stable.

- [ ] **Step 5: Commit the version-source change**

```bash
git add src/main/java/com/horizonradio/core/protocol/HorizonRadioProtocol.java src/test/java/com/horizonradio/core/protocol/HorizonRadioProtocolTest.java
git commit -m "feat: centralize generated mod version"
```

---

### Task 2: Add guarded Gradle version and release tasks

**Files:**
- Modify: `build.gradle.kts`
- Create: `gradle/release.gradle.kts`

**Interfaces:**
- Consumes: `-Pbump=patch|minor|major`, `-PnewVersion=MAJOR.MINOR.PATCH`, and optional `-PreleaseRemote=<remote>`.
- Produces: `bumpVersion` for local edits and `release` for validated commit/tag/push automation.

- [ ] **Step 1: Verify the task does not exist before implementation**

Run:

```bash
./gradlew bumpVersion -PnewVersion=1.0.1 --no-daemon
```

Expected: FAIL with Gradle’s unknown-task error. This establishes the red state for the new build interface.

- [ ] **Step 2: Apply the focused release script**

Append the following to `build.gradle.kts` after the GTNH convention plugin declaration:

```kotlin
apply(from = "gradle/release.gradle.kts")
```

- [ ] **Step 3: Implement strict SemVer parsing and bump calculation**

In `gradle/release.gradle.kts`, define a private `SemVer` value type and helpers with these exact behaviors:

```kotlin
private data class SemVer(val major: Int, val minor: Int, val patch: Int) {
    override fun toString(): String = "$major.$minor.$patch"
}

private fun parseVersion(raw: String): SemVer
private fun nextVersion(current: SemVer, bump: String): SemVer
private fun requestedVersion(current: SemVer): SemVer
```

`parseVersion` must accept only `^[0-9]+\.[0-9]+\.[0-9]+$` and reject negative values, suffixes, whitespace, empty values, and integer overflow. `nextVersion` must accept only `patch`, `minor`, or `major`; patch increments patch, minor increments minor and resets patch to zero, and major increments major while resetting minor and patch to zero. `requestedVersion` must reject simultaneous `bump` and `newVersion`, default to `patch` only when the caller supplied neither property to `release`, and reject an explicit version lower than or equal to the current version.

- [ ] **Step 4: Implement version-file read/write helpers**

Read exactly one `modVersion=` line from `gradle.properties`. Replace only that line, preserve all other properties and the existing file newline style, and fail if the line is absent or duplicated. The task must write `modVersion=<newVersion>` before the nested release build starts.

- [ ] **Step 5: Implement the local `bumpVersion` task**

Register a task in group `release` with description `Bump HorizonRadio modVersion without publishing`. It must:

- calculate the requested version from the current `modVersion`;
- write only `gradle.properties`;
- print `HorizonRadio version: <old> -> <new>`;
- never invoke Git, the Gradle build, or a remote service;
- be marked incompatible with configuration cache because it intentionally writes source configuration during execution.

- [ ] **Step 6: Implement Git preflight helpers for `release`**

Use Gradle `Project.exec` with argument lists rather than shell interpolation. Add helpers that:

- fail when `git status --porcelain` is non-empty;
- fail when `git symbolic-ref --quiet --short HEAD` returns no branch;
- fail when `refs/tags/v<version>` already exists locally;
- fail when `git ls-remote --exit-code --tags <remote> refs/tags/v<version>` finds a remote tag;
- read the current branch name and configured remote from `releaseRemote`, defaulting to `origin`.

The clean-worktree check must run before `gradle.properties` is modified. This means the existing authorized `mcmod.info` change must be committed before exercising `release`.

- [ ] **Step 7: Implement the versioned nested build and artifact validation**

After preflight and version-file update, invoke the wrapper as a child process with the release version in its environment:

```kotlin
project.exec {
    workingDir(project.projectDir)
    environment("VERSION", targetVersion.toString())
    commandLine("./gradlew", "spotlessApply", "build", "--no-daemon")
}
```

Use `gradlew.bat` on Windows. Open `build/libs/horizonradio-<version>.jar` with `java.util.zip.ZipFile`, require `mcmod.info`, and require matching `"modid": "horizonradio"`, `"name": "HorizonRadio"`, `"version": "<version>"`, and `"mcversion": "1.7.10"`. Fail before any Git mutation if the artifact or metadata check fails.

- [ ] **Step 8: Implement commit, branch push, tag, and tag push**

After the versioned build succeeds:

```text
git add -u
git commit -m "release: prepare HorizonRadio <version>"
git push <remote> <branch>
git tag -a v<version> -m "HorizonRadio <version>"
git push <remote> v<version>
```

Do not use force flags or destructive rollback commands. If a remote operation fails, propagate the failure and leave the local commit/tag for explicit retry.

- [ ] **Step 9: Verify the Gradle task interface and local bump behavior**

Run:

```bash
./gradlew tasks --all --no-daemon
```

Expected: `bumpVersion` and `release` appear under the `release` group.

Run both invalid-input checks:

```bash
./gradlew bumpVersion -Pbump=invalid --no-daemon
./gradlew bumpVersion -Pbump=patch -PnewVersion=1.0.1 --no-daemon
```

Expected: both commands fail before writing `gradle.properties`. In a temporary clean clone with a local bare `origin`, run `./gradlew bumpVersion -PnewVersion=1.0.1 --no-daemon`, confirm only `gradle.properties` changes, then remove that temporary clone. Do not run the real repository’s release task against GitHub.

- [ ] **Step 10: Commit the release task implementation**

```bash
git add build.gradle.kts gradle/release.gradle.kts
git commit -m "feat: add guarded release tasks"
```

---

### Task 3: Publish tagged artifacts through GitHub Actions

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: pushed tags matching `v*.*.*` and the repository’s `GITHUB_TOKEN`.
- Produces: a GitHub Release containing the one deployable JAR and `.sha256` checksum.

- [ ] **Step 1: Add the tag-triggered workflow**

Create a workflow with `on.push.tags: ["v*.*.*"]`, `permissions.contents: write`, and these steps:

```yaml
name: Release

on:
  push:
    tags:
      - "v*.*.*"

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - name: Check out tagged source
        uses: actions/checkout@v4

      - name: Set up Java 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
          cache: gradle

      - name: Make Gradle wrapper executable
        run: chmod +x gradlew

      - name: Validate release version
        shell: bash
        run: |
          version="${GITHUB_REF_NAME#v}"
          if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "Invalid release tag: $GITHUB_REF_NAME" >&2
            exit 1
          fi
          file_version="$(sed -n 's/^modVersion=//p' gradle.properties)"
          if [[ "$file_version" != "$version" ]]; then
            echo "Tag $version does not match gradle.properties ($file_version)" >&2
            exit 1
          fi
          echo "VERSION=$version" >> "$GITHUB_ENV"

      - name: Clean build outputs
        run: ./gradlew clean --no-daemon

      - name: Test and build release artifact
        run: ./gradlew spotlessCheck test build --no-daemon

      - name: Verify and checksum deployable JAR
        shell: bash
        run: |
          artifact="build/libs/horizonradio-${VERSION}.jar"
          test -f "$artifact"
          unzip -p "$artifact" mcmod.info | grep -F "\"version\": \"$VERSION\""
          sha256sum "$artifact" | tee "${artifact}.sha256"

      - name: Create GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: gh release create "$GITHUB_REF_NAME" "build/libs/horizonradio-${VERSION}.jar" "build/libs/horizonradio-${VERSION}.jar.sha256" --verify-tag --generate-notes --title "HorizonRadio ${VERSION}"
```

The final workflow must upload no `-dev` or `-sources` artifact and must not invoke Maven, Modrinth, or CurseForge publishing.

- [ ] **Step 2: Add static workflow checks**

Run:

```bash
rg -n 'v\*\.\*\.\*|contents: write|VERSION|horizonradio-\$\{VERSION\}\.jar|gh release create' .github/workflows/release.yml
git diff --check
```

Expected: all required release trigger, permission, version, artifact, checksum, and GitHub Release lines are present and whitespace validation passes.

- [ ] **Step 3: Commit the workflow**

```bash
git add .github/workflows/release.yml
git commit -m "ci: publish tagged HorizonRadio releases"
```

---

### Task 4: Document release usage and shared runtime compatibility

**Files:**
- Create: `docs/RELEASE.md`
- Modify: `README.md`
- Modify: `docs/COMPATIBILITY.md`
- Modify: `src/main/resources/mcmod.info` (preserve the user’s existing author change)

**Interfaces:**
- Consumes: `bumpVersion` and `release` task behavior from Task 2 and the tag workflow from Task 3.
- Produces: user-facing instructions for local builds, remote releases, deployment, and runtime compatibility.

- [ ] **Step 1: Add the dedicated release guide**

Document:

- Java 25 and Git push prerequisites;
- the clean-worktree requirement;
- `./gradlew bumpVersion -Pbump=patch|minor|major` examples;
- `./gradlew bumpVersion -PnewVersion=1.2.3`;
- `./gradlew release -Pbump=patch` as the one-command remote path;
- explicit warning that `release` commits, pushes, tags, and causes a GitHub Release;
- the exact artifact selection rule: plain `horizonradio-<version>.jar` only;
- GitHub Actions’ checksum and generated release notes;
- recovery instructions for a failed branch or tag push;
- the distinction between the build JVM (Java 25) and runtime targets.

- [ ] **Step 2: Update README build and installation sections**

Replace the current implication that every build directly produces a published `1.0.0` file with:

- normal local development using `./gradlew build`;
- versioned release builds controlled by `VERSION=<version>` inside the release process;
- the deployable plain reobfuscated JAR versus `-dev`/`-sources` outputs;
- one JAR installed on both ordinary Forge 1.7.10 and GTNH Java 17+;
- Java 25 described only as the build requirement;
- a link to `docs/RELEASE.md`.

- [ ] **Step 3: Reconcile compatibility notes**

Update `docs/COMPATIBILITY.md` so its target matrix says:

```text
Build JDK: Java 25
Ordinary Forge runtime: Java 8-compatible Forge 1.7.10 target
GTNH runtime: Java 17+
Artifact: the same reobfuscated horizonradio-<version>.jar
Hard GTNH/GregTech dependency: none
```

Retain explicit pending status for any runtime smoke test not actually executed. Do not claim that a Java 25 Gradle build alone proves a game launch.

- [ ] **Step 4: Commit documentation and the authorized metadata change**

Review `src/main/resources/mcmod.info` and preserve its current `authorList` values exactly. Then stage the documentation and metadata together:

```bash
git add README.md docs/COMPATIBILITY.md docs/RELEASE.md src/main/resources/mcmod.info
git commit -m "docs: document releases and shared runtime artifact"
```

---

### Task 5: Execute complete local verification without publishing

**Files:**
- Verify: `build.gradle.kts`, `gradle/release.gradle.kts`, `.github/workflows/release.yml`, `README.md`, `docs/RELEASE.md`, `docs/COMPATIBILITY.md`, and the generated release artifact.

**Interfaces:**
- Consumes: all implementation tasks.
- Produces: fresh evidence that versioning, build output, metadata, release simulation, and compatibility documentation agree.

- [ ] **Step 1: Run the generated-version regression test**

```bash
VERSION=9.9.9 ./gradlew test --tests com.horizonradio.core.protocol.HorizonRadioProtocolTest --no-daemon
```

Expected: PASS with the protocol constant equal to the generated token and the channel still `horizonradio_1_0`.

- [ ] **Step 2: Run the full versioned Java 25 build**

```bash
VERSION=1.0.0 ./gradlew clean --no-daemon
VERSION=1.0.0 ./gradlew spotlessCheck test build --no-daemon
```

Expected: `BUILD SUCCESSFUL`, all existing tests pass, and `build/libs/horizonradio-1.0.0.jar` exists.

- [ ] **Step 3: Inspect artifact metadata and dependency isolation**

```bash
unzip -p build/libs/horizonradio-1.0.0.jar mcmod.info
if jar tf build/libs/horizonradio-1.0.0.jar | rg -q '(^|/)(gregtech|gtnhlib|org/lwjgl)/'; then
  echo "Forbidden runtime package found in deployable JAR" >&2
  exit 1
fi
sha256sum build/libs/horizonradio-1.0.0.jar
```

Expected: metadata reports `horizonradio`, `HorizonRadio`, version `1.0.0`, and Minecraft `1.7.10`; no GTNHLib, GregTech, or LWJGL classes are packaged.

- [ ] **Step 4: Exercise release operations against a temporary local bare remote**

Use a temporary bare Git repository and temporary clone so the real GitHub remote is never contacted:

```bash
temp_root="$(mktemp -d)"
git clone --bare "$PWD" "$temp_root/origin.git"
git clone "$temp_root/origin.git" "$temp_root/work"
git -C "$temp_root/work" config user.name "HorizonRadio Release Test"
git -C "$temp_root/work" config user.email "release-test@example.invalid"
env JAVA_HOME=/home/benjamin/.jdks/ms-25.0.4 GRADLE_USER_HOME="$temp_root/gradle" PATH=/home/benjamin/.jdks/ms-25.0.4/bin:$PATH ./gradlew -p "$temp_root/work" release -Pbump=patch --no-daemon
git --git-dir="$temp_root/origin.git" tag --list 'v1.0.1'
unzip -p "$temp_root/work/build/libs/horizonradio-1.0.1.jar" mcmod.info | grep -F '"version": "1.0.1"'
```

Expected: the temporary `origin` contains `v1.0.1`, the release commit is present, and only the temporary remote receives the push. Remove only `temp_root` after recording the evidence.

- [ ] **Step 5: Run repository hygiene checks**

```bash
git diff --check
git status --short
retired_name="Mine""ify"
retired_scope="PORT_""SCOPE"
rg -n "$retired_name|$retired_scope" --glob '!build/**' --glob '!.git/**' .
```

Expected: no whitespace errors, only intentionally ignored/generated outputs outside the committed tree, and no retired identity or retired scope-variable references.

- [ ] **Step 6: Review the final diff and commit list**

```bash
git diff f5ca264..HEAD --stat
git log --oneline -8
```

Confirm that the release design, version-source change, release tasks, workflow, documentation, and authorized `mcmod.info` metadata are the only intentional changes. Do not push a real release tag as part of this verification.
