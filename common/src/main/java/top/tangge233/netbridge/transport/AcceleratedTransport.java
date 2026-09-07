package top.tangge233.netbridge.transport;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Accelerated transport (accelerated protocols only; excludes TCP).
 *
 * <p>Serves as the key for the transport domain model and has two wire-level responsibilities:
 * the entry key in the top-level {@code networks} object of the status JSON
 * ({@code quic}/{@code kcp}), and the application-layer protocol version string for the
 * corresponding transport. This enum does not contain any C ABI values; ABI mapping is performed
 * only within {@code nativebridge.internal.ffm}.
 *
 * <p>{@link TransportMode} remains the user/configuration selection enum (including TCP);
 * this enum represents only the negotiable accelerated protocols.
 */
public enum AcceleratedTransport {

    /** QUIC plaintext transport v1. */
    QUIC("quic", "net-bri-quic/1"),
    /** KCP transport v1. */
    KCP("kcp", "net-bri-kcp/1");

    private final String key;
    private final String protocol;

    AcceleratedTransport(
            String key,
            String protocol
    ) {
        this.key = key;
        this.protocol = protocol;
    }

    /**
     * Looks up the transport by its status JSON entry key; unknown keys return {@code null}
     * (discarded at the JSON boundary).
     */
    public static @Nullable AcceleratedTransport fromKey(@Nullable String key) {
        return key == null
                ? null
                : Arrays.stream(values())
                        .filter(transport -> transport.key.equals(key))
                        .findFirst()
                        .orElse(null);
    }

    /**
     * Maps the user-selected transport mode to an accelerated transport; TCP (no acceleration)
     * returns {@code null}.
     */
    public static @Nullable AcceleratedTransport fromMode(TransportMode mode) {
        return switch (mode) {
            case TCP -> null;
            case QUIC -> QUIC;
            case KCP -> KCP;
        };
    }

    /**
     * Entry key (wire name) in the {@code networks} object of the status JSON.
     */
    public String key() {
        return key;
    }

    /**
     * Application-layer protocol version string (the {@code protocol} field of this transport entry
     * on the wire).
     */
    public String protocol() {
        return protocol;
    }

}
