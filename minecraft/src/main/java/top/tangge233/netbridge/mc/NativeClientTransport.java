package top.tangge233.netbridge.mc;

import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import top.tangge233.netbridge.client.AccelerationInterceptionScope;
import top.tangge233.netbridge.client.ConnectionExecutorAdapter;
import top.tangge233.netbridge.runtime.NetBridgeServices;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeClientTransport {

    private NativeClientTransport() {
    }

    public static ChannelFuture connectWithFallback(
            InetSocketAddress tcpAddress,
            boolean useEpoll,
            Connection connection
    ) {
        return NetBridgeServices.clientRuntime().connect(
                tcpAddress,
                new MinecraftAdapter(connection, useEpoll)
        );
    }

    private static final class MinecraftAdapter implements ConnectionExecutorAdapter {

        private final Connection connection;
        private final boolean useEpoll;
        private final AtomicBoolean pipelineAttached = new AtomicBoolean(false);

        MinecraftAdapter(
                Connection connection,
                boolean useEpoll
        ) {
            this.connection = connection;
            this.useEpoll = useEpoll;
        }

        @Override
        public EventLoopGroup eventLoopGroup() {
            return useEpoll && Epoll.isAvailable()
                    ? Connection.NETWORK_EPOLL_WORKER_GROUP.get()
                    : Connection.NETWORK_WORKER_GROUP.get();
        }

        @Override
        public void initNativeChannel(Channel channel) {
            var pipeline = channel.pipeline();
            pipeline.addLast(
                    "timeout",
                    new ReadTimeoutHandler(30)
            );
            pipeline.addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    if (pipelineAttached.compareAndSet(false, true)) {
                        var p = ctx.pipeline();
                        Connection.configureSerialization(
                                p,
                                PacketFlow.CLIENTBOUND,
                                false,
                                null
                        );
                        connection.configurePacketHandler(p);
                    }
                    ctx.fireChannelActive();
                }
            });
        }

        @Override
        public ChannelFuture openTcp(InetSocketAddress address) {
            return AccelerationInterceptionScope.callWithVanillaConnectBypass(
                    () -> Connection.connect(
                            address,
                            useEpoll,
                            connection
                    )
            );
        }

    }

}
