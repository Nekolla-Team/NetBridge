import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
    `java-library`
    id("net.ltgt.errorprone")
    id("net.ltgt.nullaway")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    "compileOnly"(libs.findLibrary("jspecify").get())
    "testCompileOnly"(libs.findLibrary("jspecify").get())
    "errorprone"(libs.findLibrary("errorprone-core").get())
    "errorprone"(libs.findLibrary("nullaway").get())
}

nullaway {
    onlyNullMarked.set(true)
    jspecifyMode.set(true)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.errorprone {
        nullaway {
            error()
            excludedFieldAnnotations.add("org.spongepowered.asm.mixin.Shadow")
        }
        error("RequireExplicitNullMarking")
    }
}
