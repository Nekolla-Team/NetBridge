package top.tangge233.netbridge.benchmarkreport

import java.nio.file.Files
import java.nio.file.Path

internal object Fixtures {

    fun path(name: String): Path {
        val url = checkNotNull(
            Fixtures::class.java.classLoader.getResource(
                "fixtures/$name"
            )
        ) {
            "fixture $name not on test classpath"
        }
        return Path.of(url.toURI())
    }

    fun text(name: String): String = Files.readString(path(name))

    fun transportJson(): Path = path("transport.json")
    fun channelJson(): Path = path("channel.json")
    fun minecraftShapedJson(): Path = path("minecraft-shaped.json")
    fun minecraftSessionJson(): Path = path("minecraft-session.json")
    fun jmhJson(): Path = path("jmh.json")

}
