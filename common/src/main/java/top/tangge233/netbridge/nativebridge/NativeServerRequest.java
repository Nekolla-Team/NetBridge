package top.tangge233.netbridge.nativebridge;

import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;

public sealed interface NativeServerRequest
        permits NativeServerRequest.Quic, NativeServerRequest.Kcp {

    static Quic quic(
            int port,
            int maxConnections
    ) {
        return new Quic(null, port, maxConnections);
    }

    static Quic quic(
            @Nullable String bindHost,
            int port,
            int maxConnections
    ) {
        return new Quic(bindHost, port, maxConnections);
    }

    static Kcp kcp(
            int port,
            int maxConnections,
            NativeKcpProfile profile
    ) {
        return new Kcp(null, port, maxConnections, profile);
    }

    static Kcp kcp(
            @Nullable String bindHost,
            int port,
            int maxConnections,
            NativeKcpProfile profile
    ) {
        return new Kcp(bindHost, port, maxConnections, profile);
    }

    private static @Nullable String normalizeBindHost(@Nullable String bindHost) {
        return bindHost == null || bindHost.isBlank()
                ? null
                : bindHost;
    }

    private static void requirePort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in 0..65535: " + port);
        }
    }

    private static void requireMaxConnections(int maxConnections) {
        if (maxConnections < 1) {
            throw new IllegalArgumentException(
                    "maxConnections must be >= 1: " + maxConnections
            );
        }
    }

    NativeTransportKind transport();

    @Nullable String bindHost();

    int port();

    int maxConnections();

    record Quic(
            @Nullable String bindHost,
            int port,
            int maxConnections
    ) implements NativeServerRequest {

        public Quic {
            bindHost = normalizeBindHost(bindHost);
            requirePort(port);
            requireMaxConnections(maxConnections);
        }

        @Override
        public NativeTransportKind transport() {
            return NativeTransportKind.QUIC;
        }

    }

    record Kcp(
            @Nullable String bindHost,
            int port,
            int maxConnections,
            NativeKcpProfile profile
    ) implements NativeServerRequest {

        public Kcp {
            bindHost = normalizeBindHost(bindHost);
            requirePort(port);
            requireMaxConnections(maxConnections);
            requireNonNull(profile, "kcp profile must be provided");
        }

        @Override
        public NativeTransportKind transport() {
            return NativeTransportKind.KCP;
        }

    }

}
