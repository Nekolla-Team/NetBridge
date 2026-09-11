plugins {
    `kotlin-dsl`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("errorprone-gradle").get())
    implementation(libs.findLibrary("nullaway-gradle").get())
    implementation(libs.findLibrary("kotlin-gradle-plugin").get())
    implementation(libs.findLibrary("jmh-gradle-plugin").get())
}
