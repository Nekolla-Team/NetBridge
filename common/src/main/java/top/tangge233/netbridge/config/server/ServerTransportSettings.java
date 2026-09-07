package top.tangge233.netbridge.config.server;

import top.tangge233.netbridge.transport.KcpProfile;

import org.jspecify.annotations.Nullable;

/**
 * Server-side configuration for a single transport protocol (unparsed state; port may be -1 or 0).
 *
 * @param enabled        Whether the protocol is enabled
 * @param bindHost       Listening IP literal (null or empty means follow server-ip / bind to all
 *                       interfaces)
 * @param advertisedHost Hostname/IP advertised in the Ping response (null means follow the
 *                       connecting host)
 * @param port           Configured port (-1 follows the MC port, 0 assigns a random port, 1..65535
 *                       uses a fixed port)
 * @param maxConnections Maximum number of active connections (>= 1)
 * @param kcpProfile     KCP parameter profile (KCP only)
 */
public record ServerTransportSettings(
        boolean enabled,
        @Nullable String bindHost,
        @Nullable String advertisedHost,
        int port,
        int maxConnections,
        @Nullable KcpProfile kcpProfile
) {

    public static final int DEFAULT_MAX_CONNECTIONS = 256;

    public ServerTransportSettings {
        if (port < -1 || port > 65535) {
            throw new IllegalArgumentException(
                    "port must be -1 (follow), 0 (ephemeral) or 1..65535, was " + port
            );
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException(
                    "maxConnections must be >= 1, was " + maxConnections
            );
        }
        bindHost = normalizeBlankToNull(bindHost);
        advertisedHost = normalizeBlankToNull(advertisedHost);
    }

    private static @Nullable String normalizeBlankToNull(@Nullable String value) {
        return value == null || value.isBlank()
                ? null
                : value;
    }

    public static ServerTransportSettings defaultQuic() {
        return new ServerTransportSettings(
                true,
                null,
                null,
                -1,
                DEFAULT_MAX_CONNECTIONS,
                null
        );
    }

    public static ServerTransportSettings defaultKcp() {
        return new ServerTransportSettings(
                false,
                null,
                null,
                -1,
                DEFAULT_MAX_CONNECTIONS,
                KcpProfile.BALANCE
        );
    }

}
