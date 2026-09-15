package top.tangge233.netbridge.benchmark.channel

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.nio.NioEventLoopGroup
import org.junit.jupiter.api.Test
import top.tangge233.netbridge.channel.NativeChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.assertEquals

class NativeChannelReadLoopTest {

    private fun runReadLoop(autoRead: Boolean) {
        val group = NioEventLoopGroup(1)
        try {
            val connection = FakeNativeConnection(1)
            val channel = NativeChannel(connection)
            val delivered = AtomicLong()
            channel.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    val buf = msg as ByteBuf
                    delivered.addAndGet(buf.readableBytes().toLong())
                    buf.release()
                }
            })
            group.register(channel).awaitUninterruptibly()
            connection.connect()

            val activeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!channel.isActive) {
                check(System.nanoTime() < activeDeadline) { "channel never became active" }
                Thread.onSpinWait()
            }
            if (!autoRead) {
                channel.eventLoop().submit { channel.config().setAutoRead(false) }.syncUninterruptibly()
            }
            channel.eventLoop().submit { channel.read() }.syncUninterruptibly()

            val chunk = ByteArray(64)
            val iterations = 20_000
            repeat(iterations) { i ->
                val before = delivered.get()
                connection.push(chunk)
                channel.eventLoop().submit { channel.read() }.syncUninterruptibly()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (delivered.get() < before + chunk.size) {
                    check(System.nanoTime() < deadline) {
                        "autoRead=$autoRead iteration $i timed out: before=$before delivered=${delivered.get()}"
                    }
                    Thread.onSpinWait()
                }
            }
            assertEquals((iterations * chunk.size).toLong(), delivered.get())

            channel.close().awaitUninterruptibly()
        } finally {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
        }
    }

    @Test
    fun explicitReadDeliversEveryPush() {
        runReadLoop(autoRead = true)
    }

    @Test
    fun manualReadDeliversEveryPush() {
        runReadLoop(autoRead = false)
    }

}
