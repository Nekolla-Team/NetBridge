package top.tangge233.netbridge.benchmark.transport

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

class TcpServerEndpoint(
    workers: Int,
    bindPort: Int
) : BenchServerEndpoint {

    private val group = NioEventLoopGroup(1.coerceAtLeast(workers))
    private val serverChannel: Channel
    override val port: Int

    init {
        val bound = ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(TransportPayloadCodec.newDecoder())
                        .addLast(TransportPayloadCodec.newPrepender())
                        .addLast(BenchServerHandler())
                }
            })
            .bind(bindPort)
            .syncUninterruptibly()
            .channel()
        serverChannel = bound
        port = (bound.localAddress() as InetSocketAddress).port
    }

    override fun close() {
        try {
            serverChannel.close().await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS)
    }

}
