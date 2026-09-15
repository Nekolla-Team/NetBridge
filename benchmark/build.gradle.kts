plugins {
    id("netbridge.benchmark-conventions")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":benchmark:model"))
    implementation(libs.bundles.netty)
    implementation(libs.clikt)
    compileOnly(libs.jspecify)

    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit.jupiter.params)

    // Standalone report/compare rendering runtime (finalizer tasks only).
    benchmarkReportRuntime(project(":benchmark:report"))
}
