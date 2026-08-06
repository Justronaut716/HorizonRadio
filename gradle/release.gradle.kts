import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.process.ExecOperations

interface InjectedExecOperations {
    @get:Inject
    val execOperations: ExecOperations
}

private val injectedExecOperations = project.objects.newInstance<InjectedExecOperations>()

private data class SemVer(val major: Int, val minor: Int, val patch: Int) {
    override fun toString(): String = "$major.$minor.$patch"
}

private val versionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

private fun parseVersion(raw: String): SemVer {
    require(versionPattern.matches(raw)) {
        "Invalid version '$raw'; expected MAJOR.MINOR.PATCH"
    }
    val parts = raw.split('.')
    val values = parts.map { part ->
        part.toIntOrNull() ?: error("Version component '$part' is not a valid integer")
    }
    return SemVer(values[0], values[1], values[2])
}

private fun nextVersion(current: SemVer, bump: String): SemVer = when (bump) {
    "patch" -> SemVer(current.major, current.minor, Math.addExact(current.patch, 1))
    "minor" -> SemVer(current.major, Math.addExact(current.minor, 1), 0)
    "major" -> SemVer(Math.addExact(current.major, 1), 0, 0)
    else -> error("Invalid bump '$bump'; expected patch, minor, or major")
}

private fun requestedVersion(current: SemVer): SemVer {
    val bump = project.findProperty("bump")?.toString()
    val newVersion = project.findProperty("newVersion")?.toString()
    require(bump == null || newVersion == null) {
        "Specify either bump or newVersion, not both"
    }

    val requested = when {
        newVersion != null -> parseVersion(newVersion)
        bump != null -> nextVersion(current, bump)
        project.gradle.taskGraph.hasTask(":release") -> nextVersion(current, "patch")
        else -> error("Specify -Pbump=patch|minor|major or -PnewVersion=MAJOR.MINOR.PATCH")
    }
    require(requested > current) {
        "Requested version $requested must be greater than current version $current"
    }
    return requested
}

private operator fun SemVer.compareTo(other: SemVer): Int = compareValuesBy(
    this,
    other,
    SemVer::major,
    SemVer::minor,
    SemVer::patch,
)

private val propertiesFile = project.file("gradle.properties")
private val versionLinePattern = Regex("(?m)^modVersion=[^\\r\\n]*")

private fun readCurrentVersion(): SemVer {
    val contents = propertiesFile.readText(Charsets.UTF_8)
    val matches = versionLinePattern.findAll(contents).toList()
    require(matches.size == 1) {
        "gradle.properties must contain exactly one modVersion= line (found ${matches.size})"
    }
    return parseVersion(matches.single().value.removePrefix("modVersion="))
}

private fun writeVersion(version: SemVer) {
    val contents = propertiesFile.readText(Charsets.UTF_8)
    val matches = versionLinePattern.findAll(contents).toList()
    require(matches.size == 1) {
        "gradle.properties must contain exactly one modVersion= line (found ${matches.size})"
    }
    val match = matches.single()
    val replacement = "modVersion=$version"
    propertiesFile.writeText(
        contents.replaceRange(match.range, replacement),
        Charsets.UTF_8,
    )
}

private fun execOutput(vararg arguments: String, allowFailure: Boolean = false): Pair<Int, String> {
    val output = ByteArrayOutputStream()
    val result = injectedExecOperations.execOperations.exec {
        commandLine(arguments.toList())
        standardOutput = output
        errorOutput = output
        isIgnoreExitValue = allowFailure
    }
    return result.exitValue to output.toString(Charsets.UTF_8.name()).trim()
}

private fun requireCleanWorktree() {
    val (exitCode, output) = execOutput("git", "status", "--porcelain")
    check(exitCode == 0 && output.isEmpty()) {
        "Release requires a clean worktree${if (output.isNotEmpty()) ":\n$output" else ""}"
    }
}

private fun currentBranch(): String {
    val (exitCode, output) = execOutput("git", "symbolic-ref", "--quiet", "--short", "HEAD", allowFailure = true)
    check(exitCode == 0 && output.isNotEmpty()) { "Release requires a checked-out branch" }
    return output
}

private fun requireRemote(remote: String) {
    val (exitCode, output) = execOutput("git", "remote", "get-url", remote, allowFailure = true)
    check(exitCode == 0 && output.isNotEmpty()) { "Configured Git remote '$remote' was not found" }
}

private fun requireNoExistingTag(version: SemVer, remote: String) {
    val tag = "v$version"
    val local = execOutput("git", "show-ref", "--verify", "--quiet", "refs/tags/$tag", allowFailure = true)
    check(local.first != 0) { "Local tag $tag already exists" }

    val remoteTag = execOutput(
        "git", "ls-remote", "--exit-code", "--tags", remote, "refs/tags/$tag", allowFailure = true,
    )
    check(remoteTag.first == 2) {
        if (remoteTag.first == 0) {
            "Remote tag $tag already exists on $remote"
        } else {
            "Could not verify remote tag $tag on $remote (git ls-remote exited ${remoteTag.first})"
        }
    }
}

private fun validateArtifact(version: SemVer) {
    val artifact = project.file("build/libs/horizonradio-$version.jar")
    check(artifact.isFile) { "Release artifact not found: ${artifact.path}" }
    ZipFile(artifact).use { zip ->
        val metadata = zip.getEntry("mcmod.info") ?: error("Release artifact does not contain mcmod.info")
        val text = zip.getInputStream(metadata).bufferedReader(Charsets.UTF_8).use { it.readText() }
        listOf(
            "\"modid\": \"horizonradio\"",
            "\"name\": \"HorizonRadio\"",
            "\"version\": \"$version\"",
            "\"mcversion\": \"1.7.10\"",
        ).forEach { required ->
            check(required in text) { "mcmod.info is missing $required" }
        }
    }
}

private fun runReleaseBuild(version: SemVer) {
    injectedExecOperations.execOperations.exec {
        workingDir(project.projectDir)
        environment("VERSION", version.toString())
        commandLine(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "gradlew.bat" else "./gradlew",
            "spotlessApply",
            "build",
            "--no-daemon",
        )
    }
}

tasks.register("bumpVersion") {
    group = "release"
    description = "Bump HorizonRadio modVersion without publishing"
    notCompatibleWithConfigurationCache("Writes gradle.properties during task execution")
    doLast {
        val current = readCurrentVersion()
        val target = requestedVersion(current)
        writeVersion(target)
        println("HorizonRadio version: $current -> $target")
    }
}

tasks.register("release") {
    group = "release"
    description = "Build, tag, and publish a validated HorizonRadio release"
    notCompatibleWithConfigurationCache("Writes source configuration and performs Git operations")
    doLast {
        val current = readCurrentVersion()
        val target = requestedVersion(current)
        val remote = project.findProperty("releaseRemote")?.toString() ?: "origin"
        requireCleanWorktree()
        val branch = currentBranch()
        requireRemote(remote)
        requireNoExistingTag(target, remote)

        writeVersion(target)
        runReleaseBuild(target)
        validateArtifact(target)

        execOutput("git", "add", "-u")
        execOutput("git", "commit", "-m", "release: prepare HorizonRadio $target")
        execOutput("git", "push", remote, branch)
        execOutput("git", "tag", "-a", "v$target", "-m", "HorizonRadio $target")
        execOutput("git", "push", remote, "v$target")
    }
}
