package team.nekolla.netbridge.benchmark.transport

import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BenchServerHandlerTest {

    private fun <T> withChannel(block: (EmbeddedChannel) -> T): T {
        val channel = EmbeddedChannel(BenchServerHandler())
        try {
            return block(channel)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `server reports corrupt client payloads in its stats`() {
        withChannel {
            val seq = 1L
            val frame: ByteBuf = TransportPayloadCodec.encodeFrame(
                it.alloc(),
                FrameType.DATA,
                seq,
                64
            )
            val payloadIndex = TransportPayloadCodec.HEADER_BYTES
            frame.setByte(
                payloadIndex,
                frame.getByte(payloadIndex).toInt().xor(0xFF)
            )
            it.writeInbound(frame)

            it.writeInbound(
                TransportPayloadCodec.encodeFrame(
                    it.alloc(),
                    FrameType.REPORT_REQUEST,
                    2L,
                    0
                )
            )

            val response = checkNotNull(it.readOutbound<ByteBuf>())
            assertEquals(
                FrameType.REPORT_RESPONSE,
                TransportPayloadCodec.readType(response)
            )
            val stats = TransportPayloadCodec.readStats(response)
            assertTrue(
                stats.corruptFrames > 0,
                "server did not count the corrupt frame: $stats"
            )
            response.release()
        }
    }

    @Test
    fun `server echoes ping as pong and counts interleaved data`() {
        withChannel {
            it.writeInbound(
                TransportPayloadCodec.encodeFrame(
                    it.alloc(),
                    FrameType.PING,
                    1L,
                    32
                )
            )
            val pong = checkNotNull(it.readOutbound<ByteBuf>())
            assertEquals(
                FrameType.PONG,
                TransportPayloadCodec.readType(pong)
            )
            pong.release()

            repeat(3) { index ->
                it.writeInbound(
                    TransportPayloadCodec.encodeFrame(
                        it.alloc(),
                        FrameType.DATA,
                        (index + 2).toLong(),
                        128
                    )
                )
            }
            it.writeInbound(
                TransportPayloadCodec.encodeFrame(
                    it.alloc(),
                    FrameType.REPORT_REQUEST,
                    10L,
                    0
                )
            )
            val response = checkNotNull(it.readOutbound<ByteBuf>())
            val stats = TransportPayloadCodec.readStats(response)
            assertEquals(3L, stats.framesReceived)
            assertEquals(3L * 128, stats.bytesReceived)
            assertEquals(0L, stats.corruptFrames)
            response.release()
        }
    }

}
