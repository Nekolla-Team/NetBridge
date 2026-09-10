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
    val rttPayloadBytes: Long,
    val throughputDurationMillis: Long,
    val connectIterations: Int,
    val rttMeasuredIterations: Int,
    val startServer: Boolean,
    val runTimeoutMillis: Long,
) {

    fun withTransport(raw: String?): TransportConfig =
        copy(transports = parseTransports(raw))

    fun withCase(raw: String?): TransportConfig =
        copy(cases = Case.parseAll(raw, cases))

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

            fun parseAll(raw: String?, defaults: EnumSet<Case>): EnumSet<Case> {
                if (raw.isNullOrBlank()
                    || raw.equals("all", ignoreCase = true)
                ) {
                    return defaults
                }
                return raw
                    .split(",")
                    .mapTo(EnumSet.noneOf(Case::class.java)) {
                        parse(it.trim())
                    }
            }

            fun parse(value: String): Case =
                valueOf(
                    value
                        .uppercase(Locale.ROOT)
                        .replace('-', '_')
                )

        }

    }

    companion object {

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
            rttPayloadBytes = 1024L,
            throughputDurationMillis = 10_000L,
            connectIterations = 20,
            rttMeasuredIterations = 100,
            startServer = true,
            runTimeoutMillis = 60_000L,
        )

        fun parseTransports(raw: String?): List<TransportId> {
            if (raw.isNullOrBlank()
                || raw.equals("all", ignoreCase = true)
            ) {
                return listOf(
                    TransportId.TCP,
                    TransportId.QUIC,
                    TransportId.KCP_BALANCED,
                    TransportId.KCP_AGGRESSIVE,
                )
            }
            return raw
                .split(",")
                .mapNotNull { TransportId.parse(it.trim()) }
                .distinct()
                .also {
                    require(it.isNotEmpty()) {
                        "no valid transport in: $raw"
                    }
                }
        }

    }

}
