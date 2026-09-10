package top.tangge233.netbridge.build

import java.util.*

object NativePlatform {

    val osName: String = System.getProperty("os.name", "").lowercase(Locale.ROOT)
    val archName: String = System.getProperty("os.arch", "").lowercase(Locale.ROOT)

    val os: String = when {
        osName.contains("win") -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        else -> "linux"
    }

    val arch: String = when (archName) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> archName
    }

    val cdylibName: String = cdylibNameFor("net_bridge_native")

    fun cdylibNameFor(libraryBaseName: String): String =
        when (os) {
            "windows" -> "$libraryBaseName.dll"
            "macos" -> "lib$libraryBaseName.dylib"
            else -> "lib$libraryBaseName.so"
        }

    val subdir: String = "$os-$arch"

}
