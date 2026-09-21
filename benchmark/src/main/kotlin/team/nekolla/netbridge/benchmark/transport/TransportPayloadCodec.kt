package team.nekolla.netbridge.benchmark.transport

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender

/**
 * Wire codec for the benchmark transport protocol v2.
 *
 * Frame layout: `[type:1][seq:8][payload...]`. Control frames carry a request / stream sequence; data
 * frames carry a per-direction payload sequence. Statistics frames append a fixed 11x long
 * [SessionStats] block.
 */
object TransportPayloadCodec {

    const val HEADER_BYTES = 9
    const val MAX_FRAME_PAYLOAD = 65536
    const val STATS_LONGS = 11

    private val GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15UL.toLong()
    private val FMIX64_MULTIPLIER_1 = 0xFF51AFD7ED558CCDUL.toLong()
    private val FMIX64_MULTIPLIER_2 = 0xC4CEB9FE1A85EC53UL.toLong()

    fun encodeFrame(
        alloc: ByteBufAllocator,
        type: FrameType,
        seq: Long,
        payloadBytes: Int,
    ): ByteBuf {
        require(payloadBytes in 0..MAX_FRAME_PAYLOAD) {
            "payload out of range: $payloadBytes"
        }

        val frame = alloc.buffer(
            HEADER_BYTES + payloadBytes,
            HEADER_BYTES + payloadBytes,
        )
        frame.writeByte(type.wire)
        frame.writeLong(seq)
        repeat(payloadBytes) {
            frame.writeByte(
                patternByte(seq, it).toInt()
            )
        }
        return frame
    }

    fun patternByte(seq: Long, offset: Int): Byte {
        var x = seq * GOLDEN_RATIO_64 + offset * 31L + 7L
        x = (x xor (x ushr 33)) * FMIX64_MULTIPLIER_1
        x = (x xor (x ushr 33)) * FMIX64_MULTIPLIER_2
        x = x xor (x ushr 33)
        return (x and 0xFFL).toByte()
    }

    fun readType(frame: ByteBuf): FrameType =
        FrameType.fromWire(frame.getByte(frame.readerIndex()).toInt())

    fun verifyPayload(frame: ByteBuf, seq: Long): Boolean {
        val start = frame.readerIndex() + HEADER_BYTES
        val end = frame.writerIndex()
        return (start until end)
                .all {
                    frame.getByte(it) == patternByte(seq, it - start)
                }
    }

    /** Rewrites a PING probe in place to a PONG response (same seq/payload). */
    fun rewritePingAsPong(frame: ByteBuf) {
        frame.setByte(frame.readerIndex(), FrameType.PONG.wire)
    }

    fun encodeStats(
        alloc: ByteBufAllocator,
        type: FrameType,
        seq: Long,
        stats: SessionStats,
    ): ByteBuf {
        val frame = alloc.buffer(
            HEADER_BYTES + STATS_LONGS * 8,
            HEADER_BYTES + STATS_LONGS * 8
        )
        frame.writeByte(type.wire)
        frame.writeLong(seq)
        frame.writeLong(stats.bytesReceived)
        frame.writeLong(stats.framesReceived)
        frame.writeLong(stats.bytesSent)
        frame.writeLong(stats.framesSent)
        frame.writeLong(stats.corruptFrames)
        frame.writeLong(stats.disorderEvents)
        frame.writeLong(stats.firstSequence)
        frame.writeLong(stats.lastSequence)
        frame.writeLong(stats.startNanos)
        frame.writeLong(stats.endNanos)
        frame.writeLong(stats.failedWrites)
        return frame
    }

    fun encodeIntPayload(
        alloc: ByteBufAllocator,
        type: FrameType,
        seq: Long,
        value: Int,
    ): ByteBuf {
        val frame = alloc.buffer(HEADER_BYTES + 4, HEADER_BYTES + 4)
        frame.writeByte(type.wire)
        frame.writeLong(seq)
        frame.writeInt(value)
        return frame
    }

    fun readIntPayload(frame: ByteBuf): Int =
        frame.getInt(frame.readerIndex() + HEADER_BYTES)

    fun encodeServerSend(
        alloc: ByteBufAllocator,
        seq: Long,
        payloadBytes: Int,
        count: Int,
        intervalNanos: Long,
    ): ByteBuf {
        val frame = alloc.buffer(HEADER_BYTES + 16, HEADER_BYTES + 16)
        frame.writeByte(FrameType.SERVER_SEND.wire)
        frame.writeLong(seq)
        frame.writeInt(payloadBytes)
        frame.writeInt(count)
        frame.writeLong(intervalNanos)
        return frame
    }

    fun readServerSend(frame: ByteBuf): ServerSendSpec {
        val base = frame.readerIndex() + HEADER_BYTES
        return ServerSendSpec(
            payloadBytes = frame.getInt(base),
            count = frame.getInt(base + 4),
            intervalNanos = frame.getLong(base + 8)
        )
    }

    fun encodeServerSendComplete(
        alloc: ByteBufAllocator,
        seq: Long,
        bytesSent: Long,
        framesSent: Long,
        failures: Long,
    ): ByteBuf {
        val frame = alloc.buffer(HEADER_BYTES + 24, HEADER_BYTES + 24)
        frame.writeByte(FrameType.SERVER_SEND_COMPLETE.wire)
        frame.writeLong(seq)
        frame.writeLong(bytesSent)
        frame.writeLong(framesSent)
        frame.writeLong(failures)
        return frame
    }

    fun readServerSendComplete(frame: ByteBuf): ServerSendResult {
        val base = frame.readerIndex() + HEADER_BYTES
        return ServerSendResult(
            bytesSent = frame.getLong(base),
            framesSent = frame.getLong(base + 8),
            failures = frame.getLong(base + 16)
        )
    }

    fun readStats(frame: ByteBuf): SessionStats {
        val base = frame.readerIndex() + HEADER_BYTES
        return SessionStats(
            bytesReceived = frame.getLong(base),
            framesReceived = frame.getLong(base + 8),
            bytesSent = frame.getLong(base + 16),
            framesSent = frame.getLong(base + 24),
            corruptFrames = frame.getLong(base + 32),
            disorderEvents = frame.getLong(base + 40),
            firstSequence = frame.getLong(base + 48),
            lastSequence = frame.getLong(base + 56),
            startNanos = frame.getLong(base + 64),
            endNanos = frame.getLong(base + 72),
            failedWrites = frame.getLong(base + 80)
        )
    }

    fun newDecoder(): LengthFieldBasedFrameDecoder {
        val maxWireFrame = 4 + HEADER_BYTES + MAX_FRAME_PAYLOAD
        return LengthFieldBasedFrameDecoder(
            maxWireFrame,
            0,
            4,
            0,
            4,
        )
    }

    fun newPrepender(): LengthFieldPrepender =
        LengthFieldPrepender(4)

    fun readSequence(frame: ByteBuf): Long =
        frame.getLong(frame.readerIndex() + 1)

}

/**
 * Client-specified server-driven burst: the server emits exactly [count] frames of [payloadBytes] bytes,
 * spaced by [intervalNanos] using an absolute-deadline schedule.
 */
data class ServerSendSpec(
    val payloadBytes: Int,
    val count: Int,
    val intervalNanos: Long,
)

/** Server report for a finished [ServerSendSpec]. */
data class ServerSendResult(
    val bytesSent: Long,
    val framesSent: Long,
    val failures: Long,
)

/**
 * Closed-window session statistics exchanged over the control channel. `firstSequence` / `lastSequence`
 * use `-1` when no data frames were observed. `startNanos` / `endNanos` are `System.nanoTime()` values
 * (or `-1` when not applicable) describing the local producer window so both endpoints can report the
 * exact interval their counters belong to.
 */
data class SessionStats(
    val bytesReceived: Long,
    val framesReceived: Long,
    val bytesSent: Long,
    val framesSent: Long,
    val corruptFrames: Long,
    val disorderEvents: Long,
    val firstSequence: Long,
    val lastSequence: Long,
    val startNanos: Long,
    val endNanos: Long,
    val failedWrites: Long,
) {

    val integrityError: Boolean
        get() = corruptFrames > 0 || disorderEvents > 0 || failedWrites > 0

    companion object {

        val EMPTY: SessionStats = SessionStats(
            bytesReceived = 0,
            framesReceived = 0,
            bytesSent = 0,
            framesSent = 0,
            corruptFrames = 0,
            disorderEvents = 0,
            firstSequence = -1,
            lastSequence = -1,
            startNanos = -1,
            endNanos = -1,
            failedWrites = 0
        )

    }

}
