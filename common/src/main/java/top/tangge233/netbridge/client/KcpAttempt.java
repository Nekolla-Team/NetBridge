package top.tangge233.netbridge.client;

import top.tangge233.netbridge.transport.KcpProfile;

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
