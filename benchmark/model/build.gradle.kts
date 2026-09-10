plugins {
    id("netbridge.kotlin-tooling-conventions")
}

description = "Typed persisted schema for NetBridge benchmark runs (Jackson data mapping only)."

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)
}
