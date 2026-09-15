package top.tangge233.netbridge.benchmark.transport

enum class FrameType(val wire: Int) {

    PING(1),
    DATA(2),
    REPORT_REQUEST(3),
    REPORT_RESPONSE(4),
    BYE(5),
    START_BIDI(6);

    companion object {

        fun fromWire(wire: Int): FrameType =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("unknown frame type: $wire")

    }

}
