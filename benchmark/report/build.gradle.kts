plugins {
    id("netbridge.kotlin-tooling-conventions")
    alias(libs.plugins.jte)
}

description = "Standalone NetBridge benchmark report rendering, indexing and comparison."

dependencies {
    implementation(project(":benchmark:model"))

    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)
    implementation(libs.clikt)
    implementation(libs.jte)

    compileOnly(libs.jte.kotlin)

    testImplementation(libs.jsoup)
}

jte {
    precompile()
}

sourceSets.named("main") {
    output.dir(layout.projectDirectory.dir("jte-classes"))
}

tasks.named("jar") {
    dependsOn("precompileJte")
}
