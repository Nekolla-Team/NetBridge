package top.tangge233.netbridge.client;

import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.config.client.ClientSettings;
import top.tangge233.netbridge.transport.AcceleratedTransport;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public final class ConnectionPlanner {

    public ConnectionPlan plan(
            InetSocketAddress tcpAddress,
            ClientSettings settings,
            NetworksAbility advertised,
            Optional<TransportTarget> recentSuccess,
            boolean nativeAvailable
    ) {
        var mode = settings.mode();
        if (mode == TransportMode.TCP || !nativeAvailable) {
            return new TcpPlan(tcpAddress);
        }

        var transport = requireNonNull(
                AcceleratedTransport.fromMode(mode),
                "accelerated transport requested for non-TCP mode"
        );

        if (recentSuccess.isPresent() && recentSuccess.get().transport() == transport) {
            return new AcceleratedPlan(
                    tcpAddress,
                    attemptFor(
                            mode,
                            settings,
                            recentSuccess.get().address()
                    )
            );
        }

        var entry = advertised.entry(transport);
        if (entry == null || !entry.usable()) {
            return new TcpPlan(tcpAddress);
        }

        var host = entry.host() != null
                ? entry.host()
                : tcpAddress.getHostString();
        var endpoint = InetSocketAddress.createUnresolved(
                host,
                entry.port()
        );
        return new AcceleratedPlan(
                tcpAddress,
                attemptFor(mode, settings, endpoint)
        );
    }

    private static NativeAttempt attemptFor(
            TransportMode mode,
            ClientSettings settings,
            InetSocketAddress endpoint
    ) {
        return mode == TransportMode.KCP
                ? new KcpAttempt(endpoint, settings.kcpProfile())
                : new QuicAttempt(endpoint);
    }

}
