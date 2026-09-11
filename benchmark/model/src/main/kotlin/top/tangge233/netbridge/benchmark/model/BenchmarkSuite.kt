package top.tangge233.netbridge.benchmark.model

/**
 * Persisted benchmark suites. Serialized as the same lowercase ids used by the
 * current JSON result files.
 */
enum class BenchmarkSuite(val id: String) {

    TRANSPORT("transport"),
    CHANNEL("channel"),
    MINECRAFT_SHAPED("minecraft-shaped"),
    MINECRAFT("minecraft");

    companion object {

        fun fromId(id: String): BenchmarkSuite? =
            entries.firstOrNull { it.id == id }

    }

}
