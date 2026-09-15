package top.tangge233.netbridge.benchmark.transport

import java.nio.file.Path
import java.util.*

data class TransportConfig(
    val transports: List<TransportId>,
    val cases: EnumSet<Case>,
    val host: String,
    val port: Int,
    val workers: Int,
    val nativeLibrary: Path?,
    val rttPayloads: List<Int>,
    val throughputDurationMillis: Long,
    val connectIterations: Int,
    val rttMeasuredIterations: Int,
    val startServer: Boolean,
    val runTimeoutMillis: Long,
    val repetitions: Int,
    val seed: Long,
) {

    fun withTransport(raw: String?): TransportConfig =
        copy(transports = parseTransports(raw))

    fun withCase(raw: String?): TransportConfig =
        copy(cases = Case.parseAll(raw, cases))

    fun withRttPayloads(raw: String?): TransportConfig =
        copy(rttPayloads = parsePayloads(raw))

    fun withRepetitions(value: Int?): TransportConfig =
        copy(repetitions = (value ?: repetitions).coerceAtLeast(1))

    fun withSeed(value: Long?): TransportConfig =
        copy(seed = value ?: seed)

    enum class Case {

        CONNECT,
        RTT,
        THROUGHPUT,
        BIDIRECTIONAL,
        LOADED_LATENCY;

        val arg: String
            get() = name
                    .lowercase(Locale.ROOT)
                    .replace('_', '-')

        companion object {

            fun parseAll(raw: String?, defaults: EnumSet<Case>): EnumSet<Case> =
                when {
                    raw.isNullOrBlank() -> defaults

                    raw.equals("all", ignoreCase = true) -> EnumSet.allOf(Case::class.java)

                    else -> raw
                            .split(",")
                            .mapTo(EnumSet.noneOf(Case::class.java)) {
                                parse(it.trim())
                            }
                }

            fun parse(value: String): Case =
                valueOf(
                    value.uppercase(Locale.ROOT).replace('-', '_')
                )

        }

    }

    companion object {

        val DEFAULT_RTT_PAYLOADS: List<Int> = listOf(64, 256, 1024, 4096)

        fun defaults(): TransportConfig = TransportConfig(
            transports = parseTransports(null),
            cases = EnumSet.of(
                Case.CONNECT,
                Case.RTT,
                Case.THROUGHPUT,
                Case.LOADED_LATENCY
            ),
            host = "127.0.0.1",
            port = 0,
            workers = 2,
            nativeLibrary = null,
            rttPayloads = DEFAULT_RTT_PAYLOADS,
            throughputDurationMillis = 10_000L,
            connectIterations = 20,
            rttMeasuredIterations = 100,
            startServer = true,
            runTimeoutMillis = 60_000L,
            repetitions = 1,
            seed = 0L,
        )

        fun parseTransports(raw: String?): List<TransportId> =
            if (raw.isNullOrBlank()
                || raw.equals("all", ignoreCase = true)
            ) {
                listOf(
                    TransportId.TCP,
                    TransportId.QUIC,
                    TransportId.KCP_BALANCED,
                    TransportId.KCP_AGGRESSIVE,
                )
            } else raw
                    .split(",")
                    .mapNotNull { TransportId.parse(it.trim()) }
                    .distinct()
                    .also {
                        require(it.isNotEmpty()) {
                            "no valid transport in: $raw"
                        }
                    }

        fun parsePayloads(raw: String?): List<Int> {
            if (raw.isNullOrBlank()) {
                return DEFAULT_RTT_PAYLOADS
            }

            val values = raw
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map {
                        it.toIntOrNull()
                            ?: throw IllegalArgumentException("invalid payload size: $it")
                    }
            require(values.isNotEmpty()) { "no valid payload sizes in: $raw" }
            require(values.all { it in 1..TransportPayloadCodec.MAX_FRAME_PAYLOAD }) {
                "payload sizes must be within 1..${TransportPayloadCodec.MAX_FRAME_PAYLOAD}"
            }
            return values.distinct()
        }

    }

}
