package top.tangge233.netbridge.benchmark.transport

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import top.tangge233.netbridge.channel.NativeChannel
import top.tangge233.netbridge.nativebridge.NativeConnectRequest
import top.tangge233.netbridge.nativebridge.NativeKcpProfile
import top.tangge233.netbridge.nativebridge.internal.ffm.FfmNativeTransportBackend
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class BenchClient private constructor(
    val transport: TransportId,
    private val group: EventLoopGroup,
    private val nativeBackend: FfmNativeTransportBackend?,
    private val nativeLibrary: Path?
) : AutoCloseable {

    fun connect(
        host: String,
        port: Int,
        initializer: ChannelInitializer<Channel>
    ): ChannelFuture {
        if (transport.nativeTransport) {
            return connectNative(host, port, initializer)
        }

        return Bootstrap()
            .group(group)
            .channel(NioSocketChannel::class.java)
            .handler(initializer)
            .connect(host, port)

    }

    private fun connectNative(
        host: String,
        port: Int,
        initializer: ChannelInitializer<Channel>
    ): ChannelFuture {
        check(nativeBackend != null && nativeLibrary != null) {
            "native client not initialized"
        }

        val request = when (transport) {
            TransportId.QUIC -> NativeConnectRequest.quic(
                host,
                port
            )

            TransportId.KCP_BALANCED -> NativeConnectRequest.kcp(
                host,
                port,
                NativeKcpProfile.BALANCED
            )

            TransportId.KCP_AGGRESSIVE -> NativeConnectRequest.kcp(
                host,
                port,
                NativeKcpProfile.AGGRESSIVE
            )

            else -> error("native connect for $transport")
        }

        val connection = nativeBackend.connect(request)
        val channel = NativeChannel(connection)

        channel.setRemoteAddress(InetSocketAddress(host, port))
        channel.pipeline().addLast(initializer)

        return group.register(channel)
    }

    override fun close() {
        nativeBackend?.close()
        group.shutdownGracefully(
            0,
            5,
            TimeUnit.SECONDS
        )
    }

    companion object {

        fun open(
            transport: TransportId,
            workers: Int,
            nativeLibrary: Path?
        ): BenchClient {
            val threads = 1.coerceAtLeast(workers)
            if (transport.nativeTransport) {
                requireNotNull(nativeLibrary) {
                    "native transport $transport requires a native library path"
                }

                val backend = FfmNativeTransportBackend.load(
                    nativeLibrary,
                    threads
                )
                return BenchClient(
                    transport,
                    NioEventLoopGroup(threads),
                    backend,
                    nativeLibrary
                )
            }

            return BenchClient(
                transport,
                NioEventLoopGroup(threads),
                null,
                null
            )
        }

    }

}
