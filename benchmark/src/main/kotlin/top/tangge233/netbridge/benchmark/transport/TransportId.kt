package top.tangge233.netbridge.benchmark.transport

enum class TransportId(
    val label: String,
    private val nativeKind: Int?,
) {

    TCP("tcp", null),
    QUIC("quic", 1),
    KCP_BALANCED("kcp-balanced", 2),
    KCP_AGGRESSIVE("kcp-aggressive", 2);

    val nativeTransport: Boolean
        get() = nativeKind != null

    companion object {

        fun parse(value: String?): TransportId? =
            if (value.isNullOrBlank()
                || value.equals("all", ignoreCase = true)
            ) {
                null
            } else {
                entries.firstOrNull {
                    it.label.equals(
                        value,
                        ignoreCase = true
                    )
                } ?: throw IllegalArgumentException("unknown transport: $value")
            }

    }

}
