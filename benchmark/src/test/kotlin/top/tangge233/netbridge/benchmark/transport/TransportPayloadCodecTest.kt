package top.tangge233.netbridge.benchmark.transport

import io.netty.buffer.ByteBuf
import io.netty.buffer.UnpooledByteBufAllocator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private inline fun <T> ByteBuf.use(block: (ByteBuf) -> T): T =
    try {
        block(this)
    } finally {
        release()
    }

class TransportPayloadCodecTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val frame = TransportPayloadCodec.encodeFrame(
            UnpooledByteBufAllocator.DEFAULT,
            FrameType.PING,
            7L,
            128
        )
        frame.use {
            assertEquals(FrameType.PING, TransportPayloadCodec.readType(frame))
            assertEquals(7L, TransportPayloadCodec.readSequence(frame))
            assertEquals(128, frame.readableBytes() - TransportPayloadCodec.HEADER_BYTES)
            assertTrue(TransportPayloadCodec.verifyPayload(frame, 7L))
            assertFalse(TransportPayloadCodec.verifyPayload(frame, 8L))
        }
    }

    @Test
    fun reportResponseCarriesByteCount() {
        val frame = TransportPayloadCodec.encodeReportResponse(
            UnpooledByteBufAllocator.DEFAULT,
            3L,
            987654L
        )
        frame.use {
            assertEquals(FrameType.REPORT_RESPONSE, TransportPayloadCodec.readType(frame))
            assertEquals(3L, TransportPayloadCodec.readSequence(frame))
            assertEquals(987654L, TransportPayloadCodec.readReportBytes(frame))
        }
    }

    @Test
    fun startBidiCarriesPayloadSize() {
        val frame = TransportPayloadCodec.encodeStartBidi(
            UnpooledByteBufAllocator.DEFAULT,
            5L,
            65536
        )
        frame.use {
            assertEquals(5L, TransportPayloadCodec.readSequence(frame))
            assertEquals(65536, TransportPayloadCodec.readBidiPayloadBytes(frame))
        }
    }

    @Test
    fun payloadRangeEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            TransportPayloadCodec.encodeFrame(
                UnpooledByteBufAllocator.DEFAULT,
                FrameType.DATA,
                1L,
                65537
            )
        }
    }

}
