import top.tangge233.netbridge.build.VerifyEmbeddedPackaging

plugins {
    id("netbridge.java-conventions")
    alias(libs.plugins.neoforge.moddev)
}

dependencies {
    implementation(project(":common"))
    implementation(libs.mixin)
}

val embeddedLibraries = configurations.create("embeddedLibraries") {
    isTransitive = false
}

dependencies {
    embeddedLibraries(libs.nightconfig.core)
    embeddedLibraries(libs.nightconfig.toml)
    embeddedLibraries(libs.jackson.core)
}

val embeddedExcludes = listOf(
    "META-INF/MANIFEST.MF",
    "META-INF/*.SF",
    "META-INF/*.RSA",
    "META-INF/*.DSA",
    "META-INF/maven/**"
)

sourceSets.named("main") {
    java.srcDir(rootProject.layout.projectDirectory.dir("minecraft/src/main/java"))
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(mapOf("version" to project.version))
    }
}

neoForge {
    version.set(libs.versions.neoforge.asProvider())
    validateAccessTransformers.set(true)

    mods {
        create("netbridge") {
            sourceSet(sourceSets.named("main").get())
        }
    }
}

val cdylibDir = rootProject.layout.buildDirectory.dir("native")

val jarNeoForge = tasks.register<Jar>("jarNeoForge") {
    dependsOn(rootProject.tasks.named("buildCdylib"))
    dependsOn(rootProject.tasks.named("generateNativeManifest"))

    archiveFileName.set("net-bridge-neoforge-${project.version}.jar")

    from(sourceSets.named("main").map { it.output })
    from(project(":common").the<SourceSetContainer>()["main"].output)

    from(
        embeddedLibraries.elements.map { elements ->
            elements.map { zipTree(it.asFile) }
        }
    ) {
        exclude(embeddedExcludes)
    }

    from(cdylibDir) {
        into("native/")
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val neoforgeJarConfig = configurations.create("neoforgeJarConfig") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

val verifyNeoForgePackaging = tasks.register<VerifyEmbeddedPackaging>("verifyNeoForgePackaging") {
    dependsOn(jarNeoForge)

    jarFile.set(jarNeoForge.flatMap { it.archiveFile })
    metadataEntry.set("META-INF/neoforge.mods.toml")
}

tasks.named("check") {
    dependsOn(verifyNeoForgePackaging)
}

artifacts {
    add(neoforgeJarConfig.name, jarNeoForge)
}
