package top.tangge233.netbridge.benchmark.transport

import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import top.tangge233.netbridge.channel.NativeChannel
import top.tangge233.netbridge.nativebridge.*
import top.tangge233.netbridge.nativebridge.internal.ffm.FfmNativeTransportBackend
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class NativeServerEndpoint(
    private val transport: TransportId,
    nativeLibrary: Path,
    workers: Int,
    bindPort: Int,
    maxConnections: Int
) : BenchServerEndpoint {

    private val group: EventLoopGroup
    private val backend: FfmNativeTransportBackend
    private val nativeServer: NativeServer
    override val port: Int
    private val connectionInitializer: ChannelInitializer<Channel>

    init {
        require(transport.nativeTransport) {
            "native server requested for $transport"
        }

        group = NioEventLoopGroup(1.coerceAtLeast(workers))
        backend = FfmNativeTransportBackend.load(
            nativeLibrary,
            1.coerceAtLeast(workers)
        )
        val initializer = object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline()
                    .addLast(TransportPayloadCodec.newDecoder())
                    .addLast(TransportPayloadCodec.newPrepender())
                    .addLast(BenchServerHandler())
            }
        }
        connectionInitializer = initializer

        val request = when (transport) {
            TransportId.QUIC -> NativeServerRequest.quic(bindPort, maxConnections)

            TransportId.KCP_BALANCED -> NativeServerRequest.kcp(
                bindPort,
                maxConnections,
                NativeKcpProfile.BALANCED
            )

            TransportId.KCP_AGGRESSIVE -> NativeServerRequest.kcp(
                bindPort,
                maxConnections,
                NativeKcpProfile.AGGRESSIVE
            )

            else -> error("native server for $transport")
        }

        val server = backend.startServer(request)
        nativeServer = server
        server.setListener(object : NativeServerListener {
            override fun onAccepted(connection: NativeConnection) {
                adopt(connection)
            }
        })
        port = server.localPort()
    }

    private fun adopt(connection: NativeConnection) {
        val channel = NativeChannel(connection)
        channel.pipeline().addLast(connectionInitializer)
        group.register(channel)
    }

    override fun close() {
        try {
            nativeServer.close()
        } finally {
            try {
                backend.close()
            } finally {
                group.shutdownGracefully(0, 5, TimeUnit.SECONDS)
            }
        }
    }

}
