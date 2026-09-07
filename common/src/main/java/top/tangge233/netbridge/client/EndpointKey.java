package top.tangge233.netbridge.client;

import java.net.InetSocketAddress;

import static java.util.Objects.requireNonNull;

/**
 * Identity key for a remote endpoint used by the client-side caches.
 *
 * <p>Identity is deliberately <em>textual and exact</em>: the host component is compared as the
 * string supplied by {@link InetSocketAddress#getHostString()} with no case folding and no
 * normalization, and equality never triggers DNS resolution. This avoids IPv6 textual ambiguity
 * from string concatenation (host + ":" + port) while keeping keys cheap and deterministic.</p>
 *
 * @param host textual host (numeric address or hostname) as reported by the address
 * @param port TCP/UDP port in {@code 1..65535}
 */
public record EndpointKey(
        String host,
        int port
) {

    public EndpointKey {
        requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    /**
     * Builds the key for {@code address} using its textual host form. Never resolves DNS.
     */
    public static EndpointKey of(InetSocketAddress address) {
        requireNonNull(address, "address");
        return new EndpointKey(hostOf(address), address.getPort());
    }

    private static String hostOf(InetSocketAddress address) {
        var host = address.getHostString();
        if (host != null) {
            return host;
        }

        var inet = address.getAddress();
        return inet != null
                ? inet.getHostAddress()
                : address.getHostName();
    }

}
