package top.tangge233.netbridge.benchmark.workload

object BuiltinMinecraftWorkloads {

    val LOGIN: MinecraftWorkload = MinecraftWorkload(
        "login",
        listOf(
            WorkloadStep.clientToServer(18, 0L, 1),
            WorkloadStep.clientToServer(64, 500_000L, 1),
            WorkloadStep.serverToClient(512, 200_000L, 4),
            WorkloadStep.clientToServer(256, 300_000L, 2),
            WorkloadStep.clientToServer(64, 0L, 1)
        )
    )

    val MOVEMENT: MinecraftWorkload = MinecraftWorkload(
        "movement",
        listOf(
            WorkloadStep.clientToServer(12, 20_000L, 5000),
            WorkloadStep.serverToClient(16, 20_000L, 2500)
        )
    )

    val CHUNK_BURST: MinecraftWorkload = MinecraftWorkload(
        "chunk_burst",
        listOf(
            WorkloadStep.serverToClient(32768, 0L, 256),
            WorkloadStep.serverToClient(65536, 0L, 128),
            WorkloadStep.clientToServer(32, 0L, 256)
        )
    )

    val ENTITY_BURST: MinecraftWorkload = MinecraftWorkload(
        "entity_burst",
        listOf(
            WorkloadStep.serverToClient(200, 30_000L, 4000),
            WorkloadStep.clientToServer(64, 30_000L, 2000)
        )
    )

    val MIXED_PLAY: MinecraftWorkload = MinecraftWorkload(
        "mixed_play",
        listOf(
            WorkloadStep.clientToServer(12, 30_000L, 1500),
            WorkloadStep.serverToClient(200, 30_000L, 1200),
            WorkloadStep.serverToClient(32768, 200_000L, 32),
            WorkloadStep.clientToServer(64, 100_000L, 400)
        )
    )

    private val ALL: List<MinecraftWorkload> = listOf(
        LOGIN,
        MOVEMENT,
        CHUNK_BURST,
        ENTITY_BURST,
        MIXED_PLAY
    )

    fun byName(name: String): MinecraftWorkload =
        ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "unknown workload: $name (expected one of ${names()})"
            )

    fun names(): String = ALL.joinToString(",") { it.name }

    fun all(): List<MinecraftWorkload> = ALL

}
