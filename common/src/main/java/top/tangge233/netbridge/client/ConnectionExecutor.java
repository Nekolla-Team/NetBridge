package top.tangge233.netbridge.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.channel.NativeChannel;
import top.tangge233.netbridge.nativebridge.NativeConnectRequest;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.nativebridge.NativeTransportKind;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.ConnectException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

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

    private static NativeConnectRequest buildRequest(
            ConnectionPlan.NativeAttemptPlan attempt
    ) {
        var kind = switch (attempt.mode()) {
            case QUIC -> NativeTransportKind.QUIC;
            case KCP -> NativeTransportKind.KCP;
            case TCP -> throw new IllegalStateException("tcp mode has no native attempt");
        };
        var profile = attempt.kcpProfile() == KcpProfile.AGGRESSIVE
                ? NativeConnectRequest.KcpProfileValue.AGGRESSIVE
                : NativeConnectRequest.KcpProfileValue.BALANCED;
        return new NativeConnectRequest(
                kind,
                attempt.endpoint().getHostString(),
                attempt.endpoint().getPort(),
                profile
        );
    }

    private static String transportLine(ConnectionPlan.NativeAttemptPlan attempt) {
        return "%s %s:%d".formatted(
                attempt.mode().name(),
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
        var attemptOpt = plan.nativeAttempt();
        if (backend == null || attemptOpt.isEmpty()) {
            return fallbackToTcp(plan, adapter);
        }

        var result = new DelegatingChannelFuture(adapter.eventLoopGroup());
        runAttempt(
                plan,
                backend,
                adapter,
                attemptOpt.get(),
                1,
                result
        );
        return result;
    }

    private void runAttempt(
            ConnectionPlan plan,
            NativeTransportBackend backend,
            ConnectionExecutorAdapter adapter,
            ConnectionPlan.NativeAttemptPlan attempt,
            int attemptNumber,
            DelegatingChannelFuture result
    ) {
        stateStore.connecting(attempt.mode());
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
                    plan,
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
        result.setDelegate(attemptFuture, false);
        attemptFuture.addListener(f -> {
            if (f.isSuccess()) {
                stateStore.connected(
                        attempt.mode(),
                        transportLine(attempt)
                );
                successCache.record(
                        plan.tcpAddress(),
                        new TransportTarget(
                                attempt.mode(),
                                attempt.endpoint()
                        )
                );
                result.setDelegate(attemptFuture, true);
                return;
            }
            handleAttemptFailure(
                    plan,
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
            ConnectionPlan plan,
            NativeTransportBackend backend,
            ConnectionExecutorAdapter adapter,
            ConnectionPlan.NativeAttemptPlan attempt,
            int attemptNumber,
            DelegatingChannelFuture result,
            @Nullable Throwable cause,
            @Nullable Channel failedChannel
    ) {
        var causeMessage = cause != null
                ? cause.getMessage()
                : "unknown error";
        NetBridge.LOGGER.warn(
                "Handshake to {} via {} failed (attempt {}/{}): {}",
                plan.tcpAddress(),
                attempt.mode(),
                attemptNumber,
                retryPolicy.maxAttempts(),
                causeMessage
        );
        if (failedChannel != null) {
            closeQuietly(failedChannel);
        }

        var retryable = retryPolicy.isRetryable(cause);
        if (retryable && attemptNumber < retryPolicy.maxAttempts()) {
            var delay = retryPolicy.retryBackoffMillisForAttempt(attemptNumber);
            var next = attemptNumber + 1;
            if (delay <= 0) {
                runAttempt(
                        plan,
                        backend,
                        adapter,
                        attempt,
                        next,
                        result
                );
            } else {
                var scheduled = adapter.eventLoopGroup().next().schedule(
                        () -> runAttempt(
                                plan,
                                backend,
                                adapter,
                                attempt,
                                next,
                                result
                        ),
                        delay,
                        TimeUnit.MILLISECONDS
                );
                result.registerScheduledTask(scheduled);
            }
            return;
        }

        if (!retryable) {
            NetBridge.LOGGER.warn(
                    "Transport error to {} is non-retryable ({}); falling back to TCP",
                    plan.tcpAddress(),
                    causeMessage
            );
        } else {
            NetBridge.LOGGER.warn(
                    "Transport {} to {} failed after {} attempts ({}), falling back to TCP",
                    attempt.mode(),
                    plan.tcpAddress(),
                    retryPolicy.maxAttempts(),
                    causeMessage
            );
        }

        if (result.isAttemptCancelled()) {
            return;
        }

        var tcpFuture = fallbackToTcp(plan, adapter);
        result.setDelegate(tcpFuture, true);
    }

    private ChannelFuture tryNativeAttempt(
            NativeTransportBackend backend,
            ConnectionPlan.NativeAttemptPlan attempt,
            ConnectionExecutorAdapter adapter,
            int attemptNumber,
            DelegatingChannelFuture result
    ) {
        var connection = backend.connect(buildRequest(attempt));
        var channel = new NativeChannel(connection);
        var bootstrap = new Bootstrap()
                .group(adapter.eventLoopGroup())
                .channelFactory(() -> channel)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        adapter.initNativeChannel(ch);
                    }
                });
        var future = bootstrap.connect(attempt.endpoint());
        var watchdog = future.channel().eventLoop().schedule(
                () -> {
                    if (!future.isDone()) {
                        channel.abortConnect(new ConnectException(
                                "handshake timeout after %d ms".formatted(
                                        retryPolicy.timeoutMillisForAttempt(attemptNumber)
                                )
                        ));
                    }
                },
                retryPolicy.timeoutMillisForAttempt(attemptNumber),
                TimeUnit.MILLISECONDS
        );
        result.registerScheduledTask(watchdog);
        future.addListener(_ -> watchdog.cancel(false));
        return future;
    }

    private ChannelFuture fallbackToTcp(
            ConnectionPlan plan,
            ConnectionExecutorAdapter adapter
    ) {
        stateStore.fallingBack();
        var tcp = adapter.openTcp(plan.tcpAddress());
        tcp.addListener(ignored -> stateStore.idle());
        return tcp;
    }

}
