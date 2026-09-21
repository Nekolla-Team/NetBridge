package team.nekolla.netbridge.benchmark.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ClientSessionTest {

    private fun <T> withSession(block: (ClientSession) -> T): T {
        TcpServerEndpoint(
            1,
            0
        ).use { server ->
            BenchClient.open(
                TransportId.TCP,
                1,
                null
            ).use { client ->
                ClientSession.open(
                    client,
                    "127.0.0.1",
                    server.port,
                    10_000
                ).use {
                    return block(it)
                }
            }
        }
    }

    @Test
    fun `ping round trip returns a positive sample`() {
        withSession {
            val rtt = it.ping(64)
            assertTrue(rtt > 0, "expected positive RTT, got $rtt")
        }
    }

    @Test
    fun `stream lifecycle closes its window and reports integrity`() {
        withSession {
            val outcome = it.startStreaming(4_096)
            Thread.sleep(200)
            it.stopStreaming()
            val stream = outcome.get(30, TimeUnit.SECONDS)

            assertFalse(stream.failed, "stream failed: ${stream.error}")
            assertTrue(stream.bytesWritten > 0, "no bytes written")
            assertTrue(stream.wallNanos > 0, "window not measured")

            val stats = it.requestReport().get(30, TimeUnit.SECONDS)
            assertEquals(stream.bytesWritten, stats.bytesReceived)
            assertFalse(stats.integrityError, "server reported integrity error: $stats")
            assertEquals(0, it.corruptInboundFrames)
        }
    }

    @Test
    fun `bidirectional server stream reports both directions`() {
        withSession {
            it.startServerStream(4_096)
            val outcome = it.startStreaming(4_096)
            Thread.sleep(200)
            it.stopStreaming()
            val stream = outcome.get(30, TimeUnit.SECONDS)
            val stats = it.stopServerStream().get(30, TimeUnit.SECONDS)

            assertFalse(stream.failed, "client stream failed: ${stream.error}")
            assertTrue(stats.bytesSent > 0, "server sent no bytes")
            assertTrue(stats.bytesReceived > 0, "server received no bytes")
            assertFalse(stats.integrityError, "server reported integrity error: $stats")
        }
    }

    @Test
    fun `server send burst completes with exact frame count`() {
        withSession {
            val result = it.serverSend(
                64,
                5,
                1_000_000
            ).get(30, TimeUnit.SECONDS)
            assertEquals(5, result.framesSent)
            assertTrue(result.bytesSent > 0, "no bytes sent by server burst")
            assertEquals(0, result.failures)
        }
    }

}
