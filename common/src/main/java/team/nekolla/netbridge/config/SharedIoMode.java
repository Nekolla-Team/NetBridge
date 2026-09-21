package team.nekolla.netbridge.config;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Selects how the Java data plane talks to a native connection.
 *
 * <p>{@link #AUTO} uses the direct shared-ring data plane whenever the native backend advertises
 * support and silently falls back to the legacy {@code connection_write}/{@code connection_read}
 * ABI otherwise. {@link #ON} requires the direct data plane and fails fast when it is unavailable.
 * {@link #OFF} forces the legacy ABI path, which is the documented rollback switch for the shared
 * ring data plane. The shared-ring data plane is stable; {@link #AUTO} is the default (ADR-0013).
 */
public enum SharedIoMode {

    AUTO,
    ON,
    OFF;

    /** Default mode when the property is unset or blank. */
    public static final SharedIoMode DEFAULT = AUTO;

    /**
     * Parses a configuration value. Unknown values return {@code null} so the caller decides
     * whether to fall back to the default or reject the configuration.
     */
    public static @Nullable SharedIoMode parse(@Nullable String value) {
        return value == null
                ? null
                : switch (value.trim().toLowerCase(Locale.ROOT)) {
                    case "auto" -> AUTO;
                    case "on" -> ON;
                    case "off" -> OFF;
                    default -> null;
                };
    }

    /** Lowercase identifier used by configuration files and system properties. */
    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

}
