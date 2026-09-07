package top.tangge233.netbridge.client;

import java.net.InetSocketAddress;

import static java.util.Objects.requireNonNull;

public record QuicAttempt(
        InetSocketAddress endpoint
) implements NativeAttempt {

    public QuicAttempt {
        requireNonNull(endpoint, "endpoint must be provided");
    }

}
