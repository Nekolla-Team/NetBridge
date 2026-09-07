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

sourceSets.create("benchmark")

configurations.getByName("benchmarkImplementation") {
    extendsFrom(
        configurations.implementation.get()
    )
}

configurations.getByName("benchmarkCompileOnly") {
    extendsFrom(
        configurations.compileOnly.get()
    )
}

sourceSets.named("benchmark") {
    val mainOutput = sourceSets
            .named("main")
            .map { it.output }

    compileClasspath += mainOutput.get()
    runtimeClasspath += mainOutput.get()
}

tasks.register<JavaExec>("ffmBenchmark") {
    description = "Runs the FFM micro/end-to-end benchmark harness (repeatable, not part of test)."
    group = "verification"

    dependsOn(
        rootProject.tasks.named("buildCdylib")
    )

    mainClass.set(
        "top.tangge233.netbridge.benchmark.FfmBenchmark"
    )

    classpath(
        sourceSets.named("benchmark").map { it.output },
        sourceSets.named("main").map { it.output },
        configurations.getByName("benchmarkRuntimeClasspath")
    )

    jvmArgs(
        "-Dnetbridge.native.path=${cdylibDir.get().asFile}/${NativePlatform.subdir}/${NativePlatform.cdylibName}",
        "--enable-native-access=ALL-UNNAMED"
    )

    argumentProviders.add {
        listOf(
            cdylibDir.get()
                    .asFile
                    .resolve(NativePlatform.subdir)
                    .resolve(NativePlatform.cdylibName)
                    .absolutePath
        )
    }
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
