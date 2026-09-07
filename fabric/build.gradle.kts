import top.tangge233.netbridge.build.VerifyEmbeddedPackaging

plugins {
    id("netbridge.java-conventions")
    alias(libs.plugins.fabric.loom)
}

java {
    withSourcesJar()
}

sourceSets.named("main") {
    java.srcDir(rootProject.layout.projectDirectory.dir("minecraft/src/main/java"))
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)

    implementation(project(":common"))
    implementation(libs.bundles.netty)
}

val libBundle = configurations.create("libBundle") {
    isTransitive = false
}

dependencies {
    libBundle(libs.nightconfig.core)
    libBundle(libs.nightconfig.toml)
    libBundle(libs.jackson.core)
}

val embeddedExcludes = listOf(
    "META-INF/MANIFEST.MF",
    "META-INF/*.SF",
    "META-INF/*.RSA",
    "META-INF/*.DSA",
    "META-INF/maven/**"
)

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}

val cdylibDir = rootProject.layout.buildDirectory.dir("native")

tasks.named<Jar>("jar") {
    dependsOn(rootProject.tasks.named("buildCdylib"))
    dependsOn(rootProject.tasks.named("generateNativeManifest"))

    from(cdylibDir) {
        into("native/")
    }

    from(project(":common").the<SourceSetContainer>()["main"].output)

    from(
        libBundle.elements.map { elements ->
            elements.map { zipTree(it.asFile) }
        }
    ) {
        exclude(embeddedExcludes)
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val fabricJarConfig = configurations.create("fabricJarConfig") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

val remapJarTask = tasks.named<AbstractArchiveTask>("remapJar")

val verifyFabricPackaging = tasks.register<VerifyEmbeddedPackaging>("verifyFabricPackaging") {
    dependsOn(remapJarTask)

    jarFile.set(remapJarTask.flatMap { it.archiveFile })
    metadataEntry.set("fabric.mod.json")
}

tasks.named("check") {
    dependsOn(verifyFabricPackaging)
}

artifacts {
    add(
        fabricJarConfig.name,
        tasks.named("remapJar")
    )
}
