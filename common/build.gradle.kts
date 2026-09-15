import top.tangge233.netbridge.build.NativePlatform

plugins {
    id("netbridge.java-conventions")
    id("netbridge.test-conventions")
}

dependencies {
    implementation(libs.slf4j)
    implementation(libs.jackson.core)

    implementation(libs.nightconfig.core)
    implementation(libs.nightconfig.toml)

    implementation(libs.bundles.netty)
}

val cdylibDir = rootProject.layout.buildDirectory.dir("native")

val nativeIntegrationTest = tasks.register<Test>("nativeIntegrationTest") {
    description = "Runs native integration tests requiring the compiled Rust cdylib."
    group = "verification"

    useJUnitPlatform()

    testClassesDirs = sourceSets
        .named("test")
        .get()
        .output
        .classesDirs

    classpath = sourceSets
        .named("test")
        .get()
        .runtimeClasspath

    dependsOn(
        rootProject.tasks.named("buildCdylib")
    )

    jvmArgs(
        "-Dnetbridge.native.path=${cdylibDir.get().asFile}/${NativePlatform.subdir}/${NativePlatform.cdylibName}",
        "--enable-native-access=ALL-UNNAMED",
        "--illegal-native-access=deny"
    )
}

tasks.named<Jar>("jar") {
    dependsOn(
        rootProject.tasks.named("buildCdylib")
    )
    dependsOn(
        rootProject.tasks.named("generateNativeManifest")
    )

    from(cdylibDir) {
        into("native/")
    }
}
