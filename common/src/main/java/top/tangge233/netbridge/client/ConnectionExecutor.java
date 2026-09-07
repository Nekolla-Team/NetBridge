package top.tangge233.netbridge.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.channel.NativeChannel;
import top.tangge233.netbridge.nativebridge.NativeConnectRequest;
import top.tangge233.netbridge.nativebridge.NativeKcpProfile;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.transport.AcceleratedTransport;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;

public final class ConnectionExecutor {

    private final SuccessfulEndpointCache successCache;
    private final ConnectionStateStore stateStore;
    private final NativeRetryPolicy retryPolicy;

    public ConnectionExecutor(
            SuccessfulEndpointCache successCache,
            ConnectionStateStore stateStore,
            NativeRetryPolicy retryPolicy
    ) {
        this.successCache = successCache;
        this.stateStore = stateStore;
        this.retryPolicy = retryPolicy;
    }

    private static TransportMode modeOf(NativeAttempt attempt) {
        return switch (attempt) {
            case QuicAttempt _ -> TransportMode.QUIC;
            case KcpAttempt _ -> TransportMode.KCP;
        };
    }

    private static NativeConnectRequest buildRequest(NativeAttempt attempt) {
        return switch (attempt) {
            case QuicAttempt quic -> NativeConnectRequest.quic(
                    quic.endpoint().getHostString(),
                    quic.endpoint().getPort()
            );
            case KcpAttempt kcp -> NativeConnectRequest.kcp(
                    kcp.endpoint().getHostString(),
                    kcp.endpoint().getPort(),
                    kcp.profile() == KcpProfile.AGGRESSIVE
                            ? NativeKcpProfile.AGGRESSIVE
                            : NativeKcpProfile.BALANCED
            );
        };
    }

    private static String transportLine(NativeAttempt attempt) {
        return "%s %s:%d".formatted(
                modeOf(attempt).name(),
                attempt.endpoint().getHostString(),
                attempt.endpoint().getPort()
        );
    }

    private static void closeQuietly(Channel channel) {
        try {
            var _ = channel.close();
        } catch (Throwable e) {
            NetBridge.LOGGER.debug("Failed to close channel quietly", e);
        }
    }

    public ChannelFuture execute(
            ConnectionPlan plan,
            @Nullable NativeTransportBackend backend,
            ConnectionExecutorAdapter adapter
    ) {
        var result = new DelegatingChannelFuture(adapter.eventLoopGroup());
        execute(
                plan,
                backend,
                adapter,
                result
        );
        return result;
    }

    public void execute(
            ConnectionPlan plan,
            @Nullable NativeTransportBackend backend,
            ConnectionExecutorAdapter adapter,
            DelegatingChannelFuture result
    ) {
        if (result.isCancelled()) {
            return;
        }

        switch (plan) {
            case TcpPlan tcp -> fallbackToTcp(tcp.tcpAddress(), adapter, result);
            case AcceleratedPlan accelerated -> {
                if (backend == null) {
                    fallbackToTcp(accelerated.tcpAddress(), adapter, result);
                    return;
                }
                runAttempt(
                        accelerated.tcpAddress(),
                        backend,
                        adapter,
                        accelerated.nativeAttempt(),
                        1,
                        result
                );
            }
        }
    }

    private void runAttempt(
            InetSocketAddress tcpAddress,
            NativeTransportBackend backend,
            ConnectionExecutorAdapter adapter,
            NativeAttempt attempt,
            int attemptNumber,
            DelegatingChannelFuture result
    ) {
        if (result.isCancelled()) {
            return;
        }

        stateStore.connecting(modeOf(attempt));
        ChannelFuture attemptFuture;
        try {
            attemptFuture = tryNativeAttempt(
                    backend,
                    attempt,
                    adapter,
                    attemptNumber,
                    result
            );
        } catch (Throwable t) {
            handleAttemptFailure(
                    tcpAddress,
                    backend,
                    adapter,
                    attempt,
                    attemptNumber,
                    result,
                    t,
                    null
            );
            return;
        }

        if (!result.registerAttempt(attemptFuture.channel(), attemptFuture)) {
            return;
        }

        attemptFuture.addListener(f -> {
            if (f.isSuccess()) {
                stateStore.connected(
                        modeOf(attempt),
                        transportLine(attempt)
                );
                successCache.record(
                        tcpAddress,
                        new TransportTarget(
                                requireNonNull(
                                        AcceleratedTransport.fromMode(modeOf(attempt)),
                                        "native attempt mode is accelerated"
                                ),
                                attempt.endpoint()
                        )
                );
                result.completeSuccess(attemptFuture.channel());
                return;
            }

            handleAttemptFailure(
                    tcpAddress,
                    backend,
                    adapter,
                    attempt,
                    attemptNumber,
                    result,
                    f.cause(),
                    attemptFuture.channel()
            );
        });
    }

    private void handleAttemptFailure(
            InetSocketAddress tcpAddress,
            NativeTransportBackend backend,
            ConnectionExecutorAdapter adapter,
            NativeAttempt attempt,
            int attemptNumber,
            DelegatingChannelFuture result,
            @Nullable Throwable cause,
            @Nullable Channel failedChannel
    ) {
        var mode = modeOf(attempt);
        var causeMessage = cause != null
                ? cause.getMessage()
                : "unknown error";
        NetBridge.LOGGER.warn(
                "Handshake to {} via {} failed (attempt {}/{}): {}",
                tcpAddress,
                mode,
                attemptNumber,
                retryPolicy.maxAttempts(),
                causeMessage
        );
        if (failedChannel != null) {
            closeQuietly(failedChannel);
        }

        if (result.isCancelled()) {
            return;
        }

        var retryable = retryPolicy.isRetryable(cause);
        if (retryable && attemptNumber < retryPolicy.maxAttempts()) {
            var delay = retryPolicy.retryBackoffForAttempt(attemptNumber);
            var next = attemptNumber + 1;
            if (delay.isZero() || delay.isNegative()) {
                runAttempt(
                        tcpAddress,
                        backend,
                        adapter,
                        attempt,
                        next,
                        result
                );
            } else {
                var scheduled = adapter.eventLoopGroup().next().schedule(
                        () -> runAttempt(
                                tcpAddress,
                                backend,
                                adapter,
                                attempt,
                                next,
                                result
                        ),
                        delay.toMillis(),
                        TimeUnit.MILLISECONDS
                );
                result.registerScheduledTask(scheduled);
            }
            return;
        }

        if (!retryable) {
            NetBridge.LOGGER.warn(
                    "Transport error to {} is non-retryable ({}); falling back to TCP",
                    tcpAddress,
                    causeMessage
            );
        } else {
            NetBridge.LOGGER.warn(
                    "Transport {} to {} failed after {} attempts ({}), falling back to TCP",
                    mode,
                    tcpAddress,
                    retryPolicy.maxAttempts(),
                    causeMessage
            );
        }

        fallbackToTcp(tcpAddress, adapter, result);
    }

    private ChannelFuture tryNativeAttempt(
            NativeTransportBackend backend,
            NativeAttempt attempt,
            ConnectionExecutorAdapter adapter,
            int attemptNumber,
            DelegatingChannelFuture result
    ) {
        var connection = backend.connect(buildRequest(attempt));
        NativeChannel channel = null;
        try {
            channel = new NativeChannel(connection);
            final var finalChannel = channel;
            var bootstrap = new Bootstrap()
                    .group(adapter.eventLoopGroup())
                    .channelFactory(() -> finalChannel)
                    .handler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            adapter.initNativeChannel(ch);
                        }
                    });
            var future = bootstrap.connect(attempt.endpoint());
            var timeout = retryPolicy.timeoutForAttempt(attemptNumber);
            var timeoutMillis = timeout.toMillis();
            var watchdog = future.channel().eventLoop().schedule(
                    () -> {
                        if (!future.isDone()) {
                            finalChannel.abortConnect(new ConnectException(
                                    "handshake timeout after %d ms".formatted(timeoutMillis)
                            ));
                        }
                    },
                    timeoutMillis,
                    TimeUnit.MILLISECONDS
            );
            result.registerScheduledTask(watchdog);
            future.addListener(_ -> watchdog.cancel(false));
            return future;
        } catch (Throwable t) {
            if (channel != null) {
                closeQuietly(channel);
            } else {
                try {
                    connection.close();
                } catch (Throwable _) {
                    // ignore
                }
            }
            throw t;
        }
    }

    private void fallbackToTcp(
            InetSocketAddress tcpAddress,
            ConnectionExecutorAdapter adapter,
            DelegatingChannelFuture result
    ) {
        if (result.isCancelled()) {
            return;
        }

        stateStore.fallingBack();
        ChannelFuture tcp;
        try {
            tcp = adapter.openTcp(tcpAddress);
        } catch (Throwable t) {
            stateStore.idle();
            result.completeFailure(t, null);
            return;
        }

        if (!result.registerAttempt(tcp.channel(), tcp)) {
            stateStore.idle();
            return;
        }

        tcp.addListener(f -> {
            stateStore.idle();
            if (f.isSuccess()) {
                result.completeSuccess(tcp.channel());
            } else {
                result.completeFailure(f.cause(), tcp.channel());
            }
        });
    }

}
