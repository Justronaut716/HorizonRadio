# Modernization Build, Release, and Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the modernized codebase into a reproducibly verified release candidate with coverage visibility, dependency integrity controls, immutable CI inputs, and documentation that matches the implemented system.

**Architecture:** Keep quality enforcement in Gradle, keep GitHub workflows declarative, and make the release task mutate only the version property it owns. Treat runtime smoke tests as explicit manual evidence rather than inferring game compatibility from compilation.

**Tech Stack:** Gradle 9.3.1 Kotlin DSL, GTNH convention plugins, JaCoCo 0.8.13, GitHub Actions, Java 25 build JDK, Java-8-compatible runtime output, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-22-project-modernization-design.md`

## Global Constraints

- Execute after the safety, legacy/boundary, controller, and GUI-state plans.
- Preserve Forge 1.7.10, all 24 active packet layouts, Java-8-compatible output, and Java 17+ GTNH runtime support.
- Do not create a coverage percentage gate in the baseline commit.
- Do not let release automation format, stage, commit, or push files other than the intended `gradle.properties` version change.
- Use exact immutable commit SHAs for third-party CI actions and retain the human-readable release tag in a comment.
- Generate and review dependency lock and checksum metadata; do not weaken verification globally to make an unexplained artifact pass.
- Do not claim standalone Forge or GTNH launch compatibility until the documented manual smoke matrix has actually run.

---

### Task 1: Make formatting and release automation validation-only

**Files:**
- Modify: `gtnhShared/spotless.gradle`
- Modify: `gradle/release.gradle.kts`
- Modify: `src/test/java/com/horizonradio/build/ReleaseBuildScriptTest.java`

**Interfaces:**
- `runReleaseBuild(SemVer)` invokes `spotlessCheck`, `test`, `packagingTest`, and `build`.
- The release commit stages exactly `gradle.properties` with a path separator.

- [ ] **Step 1: Add failing source-contract tests**

Create `ReleaseBuildScriptTest` that reads the repository scripts and asserts:

```java
assertFalse(releaseScript.contains("spotlessApply"));
assertTrue(releaseScript.contains("spotlessCheck"));
assertFalse(releaseScript.contains("git\", \"add\", \"-u"));
assertTrue(releaseScript.contains("git\", \"add\", \"--\", \"gradle.properties"));
assertFalse(spotlessScript.contains("indentWithSpaces"));
assertTrue(spotlessScript.contains("leadingTabsToSpaces(4)"));
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.build.ReleaseBuildScriptTest
```

Expected: failures identify the formatter mutation, broad staging, and deprecated Spotless method.

- [ ] **Step 3: Replace deprecated formatting configuration**

In the `.gitignore` format block replace `indentWithSpaces(4)` with `leadingTabsToSpaces(4)`. Run `spotlessCheck`; do not run `spotlessApply` as part of release automation.

- [ ] **Step 4: Restrict the release mutation**

Change `runReleaseBuild` to invoke these arguments in order:

```text
./gradlew spotlessCheck test packagingTest build --no-daemon
```

Replace broad staging with:

```kotlin
execOutput("git", "add", "--", "gradle.properties")
```

Keep the clean-worktree, branch, remote, duplicate-tag, artifact metadata, push, and annotated-tag checks unchanged.

- [ ] **Step 5: Verify GREEN and task configuration**

```bash
./gradlew test --tests com.horizonradio.build.ReleaseBuildScriptTest
./gradlew spotlessCheck tasks --group release
```

- [ ] **Step 6: Commit**

```bash
git add gtnhShared/spotless.gradle gradle/release.gradle.kts src/test/java/com/horizonradio/build/ReleaseBuildScriptTest.java
git commit -m "build: make release validation-only"
```

---

### Task 2: Publish a JaCoCo baseline without inventing a threshold

**Files:**
- Modify: `build.gradle.kts`
- Modify: `.github/workflows/build.yml`
- Create: `docs/QUALITY.md`

**Interfaces:**
- Gradle `jacocoTestReport` consumes ordinary and packaging-test execution data and emits XML plus HTML.
- Gradle `check` depends on `jacocoTestReport`.
- CI retains the HTML report as `horizonradio-jacoco`.

- [ ] **Step 1: Capture the pre-instrumentation state**

```bash
./gradlew tasks --all | rg '^jacocoTestReport\b'
test ! -e build/reports/jacoco/test/html/index.html
```

Expected: no configured report task/output exists yet.

- [ ] **Step 2: Configure JaCoCo 0.8.13**

Apply `jacoco` in `build.gradle.kts`, import `JacocoReport`, and configure:

```kotlin
jacoco {
    toolVersion = "0.8.13"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test, packagingTest)
    executionData(tasks.test, packagingTest)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestReport"))
}
```

If the packaging test has no execution data because it only inspects the JAR, use `executionData(files(tasks.test.map { it.extensions.getByType<JacocoTaskExtension>().destinationFile }))` and document that packaging remains a separate behavioral gate. Do not suppress a missing ordinary test execution file.

- [ ] **Step 3: Generate and inspect the baseline**

```bash
./gradlew clean test packagingTest jacocoTestReport
test -f build/reports/jacoco/test/jacocoTestReport.xml
test -f build/reports/jacoco/test/html/index.html
rg '<counter type="(BRANCH|LINE)"' build/reports/jacoco/test/jacocoTestReport.xml | tail -n 2
```

Record the measured line and branch missed/covered counters verbatim in `docs/QUALITY.md`. Explain that this is an observational baseline, not a pass percentage, and that touched components must retain or improve focused coverage.

- [ ] **Step 4: Upload the report from CI**

After the build step add:

```yaml
- name: Upload JaCoCo report
  if: always()
  uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
  with:
    name: horizonradio-jacoco
    path: build/reports/jacoco/test/html
    if-no-files-found: error
```

- [ ] **Step 5: Verify**

```bash
./gradlew spotlessCheck check
git diff --check
```

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts .github/workflows/build.yml docs/QUALITY.md
git commit -m "build: publish coverage baseline"
```

---

### Task 3: Add dependency locking and checksum verification

**Files:**
- Modify: `build.gradle.kts`
- Create: `gradle.lockfile`
- Create: `gradle/verification-metadata.xml`
- Modify: `docs/QUALITY.md`

**Interfaces:**
- Every resolvable project configuration activates Gradle dependency locking.
- Gradle dependency verification uses SHA-256 metadata committed to the repository.

- [ ] **Step 1: Confirm integrity files are absent or stale**

```bash
git status --short gradle.lockfile gradle/verification-metadata.xml
test -f gradle.lockfile && sed -n '1,40p' gradle.lockfile || true
test -f gradle/verification-metadata.xml && sed -n '1,40p' gradle/verification-metadata.xml || true
```

- [ ] **Step 2: Activate locking**

Add to `build.gradle.kts`:

```kotlin
configurations.configureEach {
    if (isCanBeResolved) {
        resolutionStrategy.activateDependencyLocking()
    }
}
```

- [ ] **Step 3: Generate lock and verification metadata from all quality lanes**

```bash
./gradlew test packagingTest build --write-locks --write-verification-metadata sha256
```

Review every generated component. Confirm project media libraries, JUnit/LWJGL test dependencies, Forge/GTNH build dependencies, JaCoCo, and their transitives are expected. Investigate unexpected repositories or coordinates before continuing.

- [ ] **Step 4: Prove offline/reproducible resolution from the populated cache**

```bash
./gradlew spotlessCheck test packagingTest build --offline
./gradlew dependencies --offline
```

Document in `docs/QUALITY.md` how to update locks/checksums intentionally and that a first build still requires access to the declared repositories.

- [ ] **Step 5: Prove tampering is rejected**

Copy `gradle/verification-metadata.xml` outside the worktree, alter one checksum in the worktree, run `./gradlew help` and assert dependency verification fails, then restore the exact saved file. Confirm `git diff -- gradle/verification-metadata.xml` is empty before proceeding.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts gradle.lockfile gradle/verification-metadata.xml docs/QUALITY.md
git commit -m "build: lock and verify dependencies"
```

---

### Task 4: Pin CI actions to immutable releases

**Files:**
- Modify: `.github/workflows/build.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `src/test/java/com/horizonradio/build/WorkflowPolicyTest.java`

**Interfaces:**
- Checkout is pinned to `3d3c42e5aac5ba805825da76410c181273ba90b1` (`v7.0.1`).
- Java setup is pinned to `b6effb05e454b25005698d916606bdc6ffcbf961` (`v5.7.0`).
- Artifact upload remains pinned to `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` (`v7.0.1`).

- [ ] **Step 1: Write a failing workflow policy test**

Read both YAML files and reject any non-local `uses:` value ending in a mutable major tag. Assert the three exact owner/repository/SHA combinations above and require the release comment beside each SHA.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.build.WorkflowPolicyTest
```

- [ ] **Step 3: Replace mutable action references**

Use these exact references in both workflows as applicable:

```yaml
uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
```

- [ ] **Step 4: Verify GREEN and YAML structure**

```bash
./gradlew test --tests com.horizonradio.build.WorkflowPolicyTest
ruby -e 'require "yaml"; ARGV.each { |path| YAML.load_file(path) }' .github/workflows/build.yml .github/workflows/release.yml
```

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/build.yml .github/workflows/release.yml src/test/java/com/horizonradio/build/WorkflowPolicyTest.java
git commit -m "ci: pin third-party actions"
```

---

### Task 5: Reconcile user, architecture, compatibility, and release documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/COMPATIBILITY.md`
- Modify: `docs/RELEASE.md`
- Modify: `docs/QUALITY.md`
- Modify: `src/test/java/com/horizonradio/build/DocumentationConsistencyTest.java`

**Interfaces:**
- Documentation names the 24 active messages without claiming historical packet counts.
- Requirements describe client-local media and no external media executable.
- Release instructions describe validation-only formatting, exact staging, packaging tests, lock/checksum updates, coverage output, and manual smoke gates.

- [ ] **Step 1: Add failing consistency checks**

Create tests that read the five documents and assert:

- no current-state text claims 36 active packets or IDs 0–35;
- no current requirement says the server contacts YouTube, Radio Browser, or stations;
- no current requirement names `yt-dlp`, `youtube-dl`, or `ffmpeg` as installed tools;
- architecture contains the `core`, `network`, `server`, `media`, and `client` boundaries;
- release and quality guides contain `packagingTest`, `jacocoTestReport`, `gradle.lockfile`, `verification-metadata.xml`, standalone Forge smoke, and GTNH smoke.

Limit negative external-tool matches to explicit historical migration notes or source/package audit descriptions.

- [ ] **Step 2: Verify RED**

```bash
./gradlew test --tests com.horizonradio.build.DocumentationConsistencyTest
```

- [ ] **Step 3: Rewrite current-state sections**

Update all five documents from the final implementation rather than preserving chronological migration notes as current facts. Remove machine-specific JDK paths, old hashes, obsolete test counts, server-media wording, old relay packet inventories, and configuration fields removed by the legacy plan. Keep release recovery advice and explicitly mark the two game-launch smoke tests as manual evidence.

- [ ] **Step 4: Add an exact quality runbook**

In `docs/QUALITY.md`, include:

```bash
./gradlew spotlessCheck test packagingTest jacocoTestReport build
./gradlew test packagingTest build --write-locks --write-verification-metadata sha256
```

Describe where reports/artifacts are found, how checksum changes are reviewed, which warnings are compatibility-justified, and how focused coverage comparisons are made for touched components.

- [ ] **Step 5: Verify GREEN**

```bash
./gradlew test --tests com.horizonradio.build.DocumentationConsistencyTest
rg -n '36 messages|IDs 0-35|youtubeCookiesFromBrowser|youtubeCookiesFile' README.md docs
git diff --check
```

Expected: the test passes; search hits exist only in intentionally retained historical design/plan material, never in current README/architecture/compatibility/release/quality guidance.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/ARCHITECTURE.md docs/COMPATIBILITY.md docs/RELEASE.md docs/QUALITY.md
git add src/test/java/com/horizonradio/build/DocumentationConsistencyTest.java
git commit -m "docs: reconcile modernization guidance"
```

---

### Task 6: Perform the final semantic audit and release-candidate verification

**Files:**
- Modify only files identified by fresh evidence.
- Modify: `docs/QUALITY.md`

**Interfaces:**
- Produces a reviewed reachability table for every production type and externally visible project-owned member.
- Produces full automated quality evidence plus an explicit manual-smoke status.

- [ ] **Step 1: Rebuild the production inventory**

```bash
rg --files src/main/java src/main/resources | sort > build/production-inventory.txt
rg -n 'public |protected |@Mod|@EventHandler|@SubscribeEvent|registerMessage|Class\.forName|ServiceLoader|Gson|fromBytes|toBytes' src/main/java > build/reachability-evidence.txt
rg -n 'yt-dlp|youtube-dl|ffmpeg|ProcessBuilder|Runtime\.getRuntime\(\)\.exec' src/main src/test README.md docs/ARCHITECTURE.md docs/COMPATIBILITY.md docs/RELEASE.md
```

For each apparent zero-caller type/member, inspect direct and semantic IDE usages plus Forge annotations, sided proxies, event registration, packet registration, serialization constructors, reflection, resources, Gradle packaging, and tests. Record retained framework/extension entry points and removed production-only-for-test paths in `docs/QUALITY.md`. Never delete from text-reference evidence alone.

- [ ] **Step 2: Run compiler and IDE inspections**

```bash
./gradlew clean compileJava compileTestJava --warning-mode all
./gradlew spotlessCheck
```

Triage dead code, deprecation, unchecked operations, ignored close/delete/move/termination results, nullability, constant conditions, and package-boundary violations. Fix correctness findings with a failing focused test first. Keep a warning only with an inline rationale tied to Forge/Java-8 compatibility and list it in the quality document.

- [ ] **Step 3: Run the complete automated gate**

```bash
./gradlew clean spotlessCheck test packagingTest jacocoTestReport build --no-daemon
```

Inspect XML results and require zero failures/errors and zero unexpected skips:

```bash
rg -n '<testsuite[^>]+(failures|errors)="[1-9]' build/test-results
rg -n '<testsuite[^>]+skipped="[1-9]' build/test-results
```

Both commands must return no matches unless a skip is explicitly justified in `docs/QUALITY.md`; packaging suites may never skip.

- [ ] **Step 4: Audit the deployable JAR**

```bash
artifact="build/libs/horizonradio-$(sed -n 's/^modVersion=//p' gradle.properties).jar"
test -f "$artifact"
unzip -t "$artifact"
unzip -l "$artifact" > build/jar-contents.txt
unzip -p "$artifact" mcmod.info | rg '"modid": "horizonradio"|"mcversion": "1.7.10"'
rg -n 'yt-dlp|youtube-dl|ffmpeg|org/lwjgl|gregtech|gtnhlib' build/jar-contents.txt
```

Expected: ZIP and metadata checks pass; the final search reports no forbidden bundled executable/native/GTNH payload.

- [ ] **Step 5: Review repository state and documentation evidence**

```bash
git diff --check
git status --short
git log --oneline --decorate -15
```

Update `docs/QUALITY.md` with the exact test totals, coverage counters, artifact name and checksum, inspection disposition, and whether standalone Forge/Java 8 and pinned GTNH/Java 17+ smoke tests were run. Leave unrun manual gates marked pending.

- [ ] **Step 6: Commit only evidence-driven corrections**

For every final correction, add a regression test, rerun its focused suite and Steps 3–5, and amend the responsible task commit. Commit the final measured quality evidence separately only if `docs/QUALITY.md` changed after the last implementation commit:

```bash
git add docs/QUALITY.md
git commit -m "docs: record modernization verification"
```

Do not create an empty commit, do not create or push a release tag, and do not mark either manual game-launch gate complete without its runtime evidence.
