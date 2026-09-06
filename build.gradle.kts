import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

apply(from = "gradle/release.gradle.kts")

val horizonradioMediaRuntime = configurations.named("horizonradioMediaRuntime")

tasks.named<Jar>("jar") {
    dependsOn(horizonradioMediaRuntime)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        horizonradioMediaRuntime.get().resolve().sortedBy { it.name }.map { zipTree(it) }
    }) {
        exclude(
            "META-INF/INDEX.LIST",
            "META-INF/MANIFEST.MF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.SF",
            "META-INF/services/**",
            "META-INF/maven/**",
        )
    }
}

tasks.named("reobfJar") {
    dependsOn(tasks.named("jar"))
}

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
