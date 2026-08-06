# HorizonRadio release guide

This project uses Java 25 to build releases. Git push access to the configured
remote is also required for the remote release path. Java 25 is a build
prerequisite only; it is not the runtime requirement for the published Forge
1.7.10 target.

## Local versioning and builds

Start from a clean worktree when preparing a release. Check the branch and
remote before changing the version:

```bash
git status --short
git branch --show-current
git remote -v
```

For normal development, use:

```bash
./gradlew build
```

To update `gradle.properties` without publishing, use one of the following:

```bash
./gradlew bumpVersion -Pbump=patch
./gradlew bumpVersion -Pbump=minor
./gradlew bumpVersion -Pbump=major
./gradlew bumpVersion -PnewVersion=1.2.3
```

The versioned release build uses `VERSION=<version>` inside the release task
and produces one deployable, plain reobfuscated artifact:
`build/libs/horizonradio-<version>.jar`. Do not deploy `-dev`, `-sources`, or
other auxiliary outputs.

## Remote release flow

The one-command remote path is:

```bash
./gradlew release -Pbump=patch
```

Use `-Pbump=minor`, `-Pbump=major`, or `-PnewVersion=1.2.3` when appropriate.
The task validates the requested version and existing tags, builds with
`VERSION=<version>`, validates `mcmod.info` and the artifact, then commits the
version change, pushes the current branch, creates annotated tag `v<version>`,
and pushes that tag. It therefore has external side effects: `release` commits,
pushes, tags, and causes a GitHub Release through the tag workflow. Run it only
when those side effects are intended.

After the tag is pushed, GitHub Actions builds with Java 25, verifies the
versioned plain JAR, writes its SHA-256 checksum to
`horizonradio-<version>.jar.sha256`, and creates the GitHub Release with the
JAR, checksum, and generated release notes. The artifact selection rule is
always the plain reobfuscated `horizonradio-<version>.jar`; the checksum file is
for verification, not a replacement artifact.

## Recovery and retry

If the build or artifact validation fails before the commit, fix the problem,
return to a clean worktree, and rerun the command. If branch push fails after
the release commit, inspect the commit and push the current branch manually,
then continue with the tag only after confirming the intended commit is on the
remote. If tag creation or tag push fails, check both local and remote state:

```bash
git status --short
git log -1 --oneline
git tag --list 'v<version>'
git ls-remote --tags origin 'refs/tags/v<version>'
```

Push an existing local tag with `git push origin v<version>` only after
verifying it points at the intended release commit. If the remote tag already
exists, do not recreate or force-move it; investigate the GitHub Actions run and
retry the workflow or repair the release using the GitHub repository controls.
Do not rerun `release` against an already-created version: its duplicate-tag
guards are intentional.

## Runtime targets

The same `horizonradio-<version>.jar` is intended for both ordinary Forge
1.7.10 and GTNH. Ordinary Forge targets a Java 8-compatible runtime. GTNH
targets Java 17 or newer. Java 25 is the build JVM required by the convention
build, not a claim that either game runtime must use Java 25. HorizonRadio has
no hard GTNH or GregTech dependency.

Runtime smoke tests for standalone Forge 1.7.10 and a GTNH pack remain pending
until those environments are actually launched. A successful Java 25 Gradle
build does not prove a game launch or runtime compatibility.

See [`docs/COMPATIBILITY.md`](COMPATIBILITY.md) for the evidence matrix and
pending verification gates.
