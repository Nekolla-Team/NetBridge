package top.tangge233.netbridge.transport;

import java.net.InetSocketAddress;

import static java.util.Objects.requireNonNull;

/**
 * Recent client accelerated connection target: the accelerated transport and its resolved transport
 * endpoint.
 *
 * <p>Only accelerated transports (QUIC/KCP) are supported; the invalid state
 * {@code TransportTarget(TCP, ...)} is impossible at the type level. TCP fallback is built into the
 * acceleration mode and has no separate toggle field; the endpoint address is composed from the
 * server-advertised entry and the ping target.
 */
public record TransportTarget(
        AcceleratedTransport transport,
        InetSocketAddress address
) {

    public TransportTarget {
        requireNonNull(transport, "transport");
        requireNonNull(address, "address");
    }

}
