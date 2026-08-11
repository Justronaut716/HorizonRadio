import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

apply(from = "gradle/release.gradle.kts")

val horizonradioMediaRuntime = configurations.named("horizonradioMediaRuntime")
val packagingTestArtifact = providers.systemProperty("horizonradio.test.artifact")

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

tasks.withType<Test>().configureEach {
    inputs.property("horizonradio.test.artifact", packagingTestArtifact.orNull ?: "")
    packagingTestArtifact.orNull?.let { artifact ->
        systemProperty("horizonradio.test.artifact", artifact)
    }
}
