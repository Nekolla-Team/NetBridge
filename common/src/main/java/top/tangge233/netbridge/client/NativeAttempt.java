package top.tangge233.netbridge.client;

import java.net.InetSocketAddress;

public sealed interface NativeAttempt permits QuicAttempt, KcpAttempt {

    InetSocketAddress endpoint();

}
