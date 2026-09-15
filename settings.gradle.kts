pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
        }
        maven {
            url = uri("https://maven.fabricmc.net")
        }
        maven {
            url = uri("https://maven.neoforged.net")
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
        }
        maven {
            url = uri("https://maven.fabricmc.net")
        }
        maven {
            url = uri("https://maven.neoforged.net")
        }
        mavenCentral()
    }
}

rootProject.name = "net-bridge"

includeBuild("build-logic")

include("common")
include("benchmark")
include("benchmark:model")
include("benchmark:report")
include("neoforge")
include("fabric")
