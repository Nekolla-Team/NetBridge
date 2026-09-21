package team.nekolla.netbridge.benchmark.workload

/**
 * Built-in Minecraft-shaped traffic patterns.
 *
 * <p>All timings are expressed relative to a Minecraft server tick (50&nbsp;ms at 20&nbsp;TPS)
 * so the numbers have an unambiguous real-world interpretation instead of arbitrary
 * nanosecond/microsecond delays.
 */
object BuiltinMinecraftWorkloads {

    private const val TICK_NANOS = 50_000_000L

    /** Handshake/login burst: small client requests interleaved with a few 512&nbsp;B responses. */
    val LOGIN: MinecraftWorkload = MinecraftWorkload(
        "login",
        listOf(
            WorkloadStep.clientToServer(18, 0L, 1),
            WorkloadStep.clientToServer(64, TICK_NANOS / 20, 1),
            WorkloadStep.serverToClient(512, TICK_NANOS / 5, 4),
            WorkloadStep.clientToServer(256, TICK_NANOS / 10, 2),
            WorkloadStep.clientToServer(64, 0L, 1)
        )
    )

    /** One second of movement: client packets once per tick, server corrections every other tick. */
    val MOVEMENT: MinecraftWorkload = MinecraftWorkload(
        "movement",
        listOf(
            WorkloadStep.clientToServer(12, TICK_NANOS, 20),
            WorkloadStep.serverToClient(16, 2 * TICK_NANOS, 10)
        )
    )

    /** Server-driven chunk burst, then 128 double-size chunks, with client acks. */
    val CHUNK_BURST: MinecraftWorkload = MinecraftWorkload(
        "chunk_burst",
        listOf(
            WorkloadStep.serverToClient(32768, 0L, 256),
            WorkloadStep.serverToClient(65536, 0L, 128),
            WorkloadStep.clientToServer(32, TICK_NANOS / 10, 256)
        )
    )

    /** One second of entity updates at one update per tick, both directions. */
    val ENTITY_BURST: MinecraftWorkload = MinecraftWorkload(
        "entity_burst",
        listOf(
            WorkloadStep.serverToClient(200, TICK_NANOS, 20),
            WorkloadStep.clientToServer(64, TICK_NANOS, 20)
        )
    )

    /** Mixed play: movement, entity updates, periodic chunks and interaction packets. */
    val MIXED_PLAY: MinecraftWorkload = MinecraftWorkload(
        "mixed_play",
        listOf(
            WorkloadStep.clientToServer(12, TICK_NANOS, 20),
            WorkloadStep.serverToClient(200, TICK_NANOS, 20),
            WorkloadStep.serverToClient(32768, 20 * TICK_NANOS, 32),
            WorkloadStep.clientToServer(64, 5 * TICK_NANOS, 20)
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
        ALL.firstOrNull {
            it.name.equals(name, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "unknown workload: $name (expected one of ${names()})"
        )

    fun names(): String = ALL.joinToString(",") { it.name }

    fun all(): List<MinecraftWorkload> = ALL

}
