package team.nekolla.netbridge.client;

import team.nekolla.netbridge.transport.KcpProfile;

import java.net.InetSocketAddress;

import static java.util.Objects.requireNonNull;

public record KcpAttempt(
        InetSocketAddress endpoint,
        KcpProfile profile
) implements NativeAttempt {

    public KcpAttempt {
        requireNonNull(endpoint, "endpoint must be provided");
        requireNonNull(profile, "kcp profile must be provided");
    }

}
