package top.tangge233.netbridge.benchmark.transport

/**
 * Benchmark control/data frame types (transport protocol v2).
 *
 * <p>Control frames are correlated by a request/stream sequence number; data frames use a
 * separate per-direction sequence space. See [TransportPayloadCodec] for the wire layout.
 */
enum class FrameType(val wire: Int) {

    /** Client -> server RTT probe request (carries a generated payload). */
    PING(1),

    /** Server -> client RTT probe response (echoes the probe sequence and payload). */
    PONG(2),

    /** Stream payload frame. */
    DATA(3),

    /** Client -> server request for a [SessionStats] snapshot. */
    REPORT_REQUEST(4),

    /** Server -> client [SessionStats] snapshot. */
    REPORT_RESPONSE(5),

    /** Client -> server: start the server->client reverse stream. */
    STREAM_START(6),

    /** Client -> server: stop the server->client reverse stream. */
    STREAM_STOP(7),

    /** Server -> client: reverse stream stopped, carries final [SessionStats]. */
    STREAM_STOPPED(8),

    /** Client -> server: request a server-driven burst (payload, count, interval). */
    SERVER_SEND(10),

    /** Server -> client: server-driven burst finished (bytes, frames, failures). */
    SERVER_SEND_COMPLETE(11),

    /** Graceful shutdown. */
    BYE(9);

    companion object {

        fun fromWire(wire: Int): FrameType =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("unknown frame type: $wire")

    }

}
