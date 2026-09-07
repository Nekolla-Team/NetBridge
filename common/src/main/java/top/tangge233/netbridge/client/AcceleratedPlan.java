package top.tangge233.netbridge.client;

import java.net.InetSocketAddress;

import static java.util.Objects.requireNonNull;

public record AcceleratedPlan(
        InetSocketAddress tcpAddress,
        NativeAttempt nativeAttempt
) implements ConnectionPlan {

    public AcceleratedPlan {
        requireNonNull(tcpAddress, "tcpAddress must be provided");
        requireNonNull(nativeAttempt, "nativeAttempt must be provided");
    }

}
