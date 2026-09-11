package top.tangge233.netbridge.benchmark.transport

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender

object TransportPayloadCodec {

    const val HEADER_BYTES = 9
    const val MAX_FRAME_PAYLOAD = 65536

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

    fun encodeReportResponse(
        alloc: ByteBufAllocator,
        seq: Long,
        receivedBytes: Long,
    ): ByteBuf {
        val frame = alloc.buffer(
            HEADER_BYTES + 8,
            HEADER_BYTES + 8,
        )
        frame.writeByte(FrameType.REPORT_RESPONSE.wire)
        frame.writeLong(seq)
        frame.writeLong(receivedBytes)
        return frame
    }

    fun readReportBytes(frame: ByteBuf): Long =
        frame.getLong(frame.readerIndex() + HEADER_BYTES)

    fun encodeStartBidi(
        alloc: ByteBufAllocator,
        seq: Long,
        payloadBytes: Int
    ): ByteBuf {
        val frame = alloc.buffer(
            HEADER_BYTES + 4,
            HEADER_BYTES + 4,
        )
        frame.writeByte(FrameType.START_BIDI.wire)
        frame.writeLong(seq)
        frame.writeInt(payloadBytes)
        return frame
    }

    fun readBidiPayloadBytes(frame: ByteBuf): Int =
        frame.getInt(frame.readerIndex() + HEADER_BYTES)

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
