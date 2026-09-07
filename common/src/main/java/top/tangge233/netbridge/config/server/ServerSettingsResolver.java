package top.tangge233.netbridge.config.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.transport.KcpProfile;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;

public final class ServerSettingsResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSettingsResolver.class);

    private ServerSettingsResolver() {
    }

    public static ResolvedServerSettings resolve(
            ServerSettings settings,
            int mcPort,
            @Nullable String mcBindIp
    ) {
        return resolve(settings, mcPort, mcBindIp, null);
    }

    public static ResolvedServerSettings resolve(
            ServerSettings settings,
            int mcPort,
            @Nullable String mcBindIp,
            @Nullable Integer quicPortOverride
    ) {
        var quicTargetPort = quicPortOverride != null
                ? quicPortOverride
                : settings.quic().port();
        var quic = resolveTransport(
                "quic",
                settings.quic(),
                mcPort,
                mcBindIp,
                quicTargetPort
        );
        var kcp = resolveTransport(
                "kcp",
                settings.kcp(),
                mcPort,
                mcBindIp,
                settings.kcp().port()
        );
        return new ResolvedServerSettings(quic, kcp);
    }

    private static ResolvedTransport resolveTransport(
            String name,
            ServerTransportSettings transport,
            int mcPort,
            @Nullable String mcBindIp,
            int targetPortConfig
    ) {
        var resolvedTransport = new ResolvedTransport(
                false,
                -1,
                null,
                null,
                transport.maxConnections(),
                transport.kcpProfile()
        );

        if (!transport.enabled()) {
            LOGGER.info("{} transport disabled by config", name);
            return resolvedTransport;
        }

        var listenPort = applyFollowSemantics(name, targetPortConfig, mcPort);
        if (listenPort < 0 || listenPort > 65535) {
            LOGGER.error(
                    "{} listen port {} out of range (-1/0/1..=65535): transport disabled",
                    name,
                    targetPortConfig
            );
            return resolvedTransport;
        }

        if (listenPort == 0) {
            LOGGER.info(
                    "{} listen port 0: random assignment",
                    name
            );
        } else {
            LOGGER.info(
                    "{} listen port {} (minecraft tcp port {})",
                    name,
                    listenPort,
                    mcPort
            );
        }

        var bind = transport.bindHost() != null && !transport.bindHost().isBlank()
                ? transport.bindHost()
                : (
                        mcBindIp != null && !mcBindIp.isBlank()
                                ? mcBindIp
                                : null
                );

        return new ResolvedTransport(
                true,
                listenPort,
                bind,
                transport.advertisedHost(),
                transport.maxConnections(),
                transport.kcpProfile()
        );
    }

    private static int applyFollowSemantics(
            String name,
            int configured,
            int mcPort
    ) {
        if (configured != -1) {
            return configured;
        }

        var target = name.equals("kcp")
                ? mcPort + 1
                : mcPort;
        if (target < 1 || target > 65535) {
            LOGGER.error(
                    "{} listen port -1 cannot follow minecraft tcp port {}: transport disabled",
                    name,
                    mcPort
            );
            return -2;
        }

        LOGGER.info("{} listen port -1: following minecraft tcp port {}", name, target);
        return target;
    }

    private static @Nullable String normalizeBlankToNull(@Nullable String value) {
        return value == null || value.isBlank()
                ? null
                : value;
    }

    public record ResolvedTransport(
            boolean enabled,
            int listenPort,
            @Nullable String bindHost,
            @Nullable String advertisedHost,
            int maxConnections,
            @Nullable KcpProfile kcpProfile
    ) {

        public ResolvedTransport {
            if (enabled
                    ? (listenPort < 0 || listenPort > 65535)
                    : listenPort != -1
            ) {
                throw new IllegalArgumentException(
                        "enabled=%s listenPort must be -1 (disabled) or 0..65535 (enabled), was %d".formatted(
                                enabled,
                                listenPort
                        )
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

    }

    public record ResolvedServerSettings(
            ResolvedTransport quic,
            ResolvedTransport kcp
    ) {

        public ResolvedServerSettings {
            requireNonNull(quic, "quic");
            requireNonNull(kcp, "kcp");
        }

    }

}
