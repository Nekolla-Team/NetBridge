package top.tangge233.netbridge.transport;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The three client transport modes.
 *
 * <p>quic/kcp include built-in TCP fallback after two failed handshakes; tcp is plain TCP
 * and has no fallback concept. There is no separate *-fallback-tcp mode.
 */
public enum TransportMode {

    TCP,
    QUIC,
    KCP;

    /**
     * Parses a configuration value. Unknown values return null so the caller can fall back to the
     * default TCP mode. Legacy values such as quic-only/quic-fallback are not migrated and are
     * treated as unset.
     */
    public static @Nullable TransportMode parse(@Nullable String value) {
        return value == null
                ? null
                : switch (value.trim().toLowerCase(Locale.ROOT)) {
                    case "tcp" -> TCP;
                    case "quic" -> QUIC;
                    case "kcp" -> KCP;
                    default -> null;
                };
    }

    /** Lowercase identifier used by configuration files and system properties. */
    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

}
