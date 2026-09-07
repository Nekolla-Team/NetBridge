package top.tangge233.netbridge.client;

import java.net.InetSocketAddress;

import static java.util.Objects.requireNonNull;

public record TcpPlan(
        InetSocketAddress tcpAddress
) implements ConnectionPlan {

    public TcpPlan {
        requireNonNull(tcpAddress, "tcpAddress must be provided");
    }

}
