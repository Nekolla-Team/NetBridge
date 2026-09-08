package top.tangge233.netbridge.transport;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * KCP parameter profile. Two presets are provided; custom values are not supported.
 *
 * <ul>
 *   <li>{@link #BALANCE}: nodelay=0/interval=40/resend=0/nc=0, bandwidth-friendly.</li>
 *   <li>{@link #AGGRESSIVE}: nodelay=1/interval=10/resend=2/nc=1, trading bandwidth for latency on lossy paths.</li>
 * </ul>
 */
public enum KcpProfile {

    BALANCE,
    AGGRESSIVE;

    /**
     * Parses a configuration value. Canonical names are {@code balance}/{@code aggressive}; the
     * legacy alias {@code balanced} is also accepted. Invalid values return null so the caller can
     * warn and use the default.
     */
    public static @Nullable KcpProfile parse(@Nullable String value) {
        return value == null
                ? null
                : switch (value.trim().toLowerCase(Locale.ROOT)) {
                    case "balance", "balanced" -> BALANCE;
                    case "aggressive" -> AGGRESSIVE;
                    default -> null;
                };
    }

    /** Canonical string used in configuration files. */
    public String configValue() {
        return this == AGGRESSIVE
                ? "aggressive"
                : "balance";
    }

}
