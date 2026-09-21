package team.nekolla.netbridge.client;

import java.net.InetSocketAddress;

public sealed interface ConnectionPlan permits TcpPlan, AcceleratedPlan {

    InetSocketAddress tcpAddress();

}
