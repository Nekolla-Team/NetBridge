package top.tangge233.netbridge.mc;

import io.netty.channel.EventLoopGroup;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.RateKickingConnection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.channel.NativeChannel;
import top.tangge233.netbridge.nativebridge.NativeConnection;
import top.tangge233.netbridge.runtime.NetBridgeServices;

import java.net.InetSocketAddress;

@SuppressWarnings("ConstantValue")
public final class NativeServerTransport {

    private NativeServerTransport() {
    }

    public static void adopt(
            MinecraftServer server,
            NativeConnection connection
    ) {
        adopt(server, connection, 0L);
    }

    public static void adopt(
            MinecraftServer server,
            NativeConnection connection,
            long sessionGeneration
    ) {
        if (!NetBridgeServices.serverRuntime().isSessionValid(sessionGeneration)) {
            NetBridge.LOGGER.warn(
                    "Rejecting adoption before server thread: session {} is stale",
                    sessionGeneration
            );
            connection.close();
            return;
        }

        server.execute(() -> {
            if (!NetBridgeServices.serverRuntime().isSessionValid(sessionGeneration)) {
                NetBridge.LOGGER.warn(
                        "Rejecting adoption on server thread entry: session {} is stale",
                        sessionGeneration
                );
                connection.close();
                return;
            }

            InetSocketAddress remoteAddr;
            try {
                remoteAddr = connection.remoteAddress();
                if (remoteAddr == null) {
                    throw new IllegalStateException(
                            "Remote address unavailable for connection " + connection.id()
                    );
                }
            } catch (Throwable t) {
                NetBridge.LOGGER.warn(
                        "Rejecting connection {}: failed to obtain remote address: {}",
                        connection.id(),
                        t.getMessage()
                );
                connection.close();
                return;
            }

            var channel = new NativeChannel(connection);
            channel.setRemoteAddress(remoteAddr);

            var pipeline = channel.pipeline();
            pipeline.addLast(
                    "timeout",
                    new ReadTimeoutHandler(30)
            );

            Connection.configureSerialization(
                    pipeline,
                    PacketFlow.SERVERBOUND,
                    false,
                    null
            );

            var rateLimit = server.getRateLimitPacketsPerSecond();
            var mcConnection = rateLimit > 0
                    ? new RateKickingConnection(rateLimit)
                    : new Connection(PacketFlow.SERVERBOUND);

            mcConnection.configurePacketHandler(pipeline);
            mcConnection.setListenerForServerboundHandshake(
                    new ServerHandshakePacketListenerImpl(server, mcConnection)
            );

            var serverConnection = server.getConnection();
            if (serverConnection == null
                    || !server.isRunning()
                    || !NetBridgeServices.serverRuntime().isSessionValid(sessionGeneration)
            ) {
                var _ = channel.close();
                return;
            }

            var group = (EventLoopGroup) ServerConnectionListener.SERVER_EVENT_GROUP.get();
            var regFuture = group.register(channel);
            regFuture.addListener(f -> {
                if (f.isSuccess()
                        && NetBridgeServices.serverRuntime().isSessionValid(sessionGeneration)
                ) {
                    server.execute(() -> {
                        var sc = server.getConnection();
                        if (sc != null
                                && server.isRunning()
                                && channel.isOpen()
                                && NetBridgeServices.serverRuntime()
                                .isSessionValid(sessionGeneration)
                        ) {
                            sc.getConnections().add(mcConnection);
                            NetBridge.LOGGER.info(
                                    "Connection {} adopted into server pipeline (channel {}, session {})",
                                    connection.id(),
                                    channel.connId(),
                                    sessionGeneration
                            );
                        } else {
                            var _ = channel.close();
                        }
                    });
                } else {
                    var _ = channel.close();
                }
            });
        });
    }

}
