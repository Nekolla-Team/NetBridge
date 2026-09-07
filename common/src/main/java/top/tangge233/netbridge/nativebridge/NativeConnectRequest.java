package top.tangge233.netbridge.nativebridge;

import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Native outbound connection request (typed: QUIC cannot carry a KCP tier, while KCP must carry a
 * tier).
 */
public sealed interface NativeConnectRequest
        permits NativeConnectRequest.Quic, NativeConnectRequest.Kcp {

    static Quic quic(
            String host,
            int port
    ) {
        return new Quic(host, port);
    }

    static Kcp kcp(
            String host,
            int port,
            NativeKcpProfile profile
    ) {
        return new Kcp(host, port, profile);
    }

    private static String requireHost(@Nullable String host) {
        var value = requireNonNull(host, "host must be provided");
        if (value.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        return value;
    }

    private static void requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in 1..65535: " + port);
        }
    }

    /**
     * The semantic transport type of this request (always identical to the concrete subtype,
     * provided for read-only access and backend logging convenience).
     */
    NativeTransportKind transport();

    String host();

    int port();

    record Quic(
            String host,
            int port
    ) implements NativeConnectRequest {

        public Quic {
            host = requireHost(host);
            requirePort(port);
        }

        @Override
        public NativeTransportKind transport() {
            return NativeTransportKind.QUIC;
        }

    }

    record Kcp(
            String host,
            int port,
            NativeKcpProfile profile
    ) implements NativeConnectRequest {

        public Kcp {
            host = requireHost(host);
            requirePort(port);
            requireNonNull(profile, "kcp profile must be provided");
        }

        @Override
        public NativeTransportKind transport() {
            return NativeTransportKind.KCP;
        }

    }

}
