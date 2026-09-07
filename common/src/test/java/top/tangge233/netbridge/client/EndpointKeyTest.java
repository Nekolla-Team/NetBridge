package top.tangge233.netbridge.client;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class EndpointKeyTest {

    private static final String V4 = "203.0.113.9";
    private static final String V6 = "2001:db8::1";

    @Test
    void keysAreTextualAndDeterministic() {
        var a = EndpointKey.of(InetSocketAddress.createUnresolved(V4, 25565));
        assertEquals(
                new EndpointKey(V4, 25565),
                a
        );
        assertEquals(
                a,
                EndpointKey.of(InetSocketAddress.createUnresolved(V4, 25565))
        );
        assertEquals(
                a.hashCode(),
                new EndpointKey(V4, 25565).hashCode()
        );
    }

    @Test
    void sameHostDifferentPortDistinct() {
        var p1 = EndpointKey.of(InetSocketAddress.createUnresolved(V4, 25565));
        var p2 = EndpointKey.of(InetSocketAddress.createUnresolved(V4, 25566));
        assertNotEquals(
                p1,
                p2
        );
        assertNotEquals(
                p1.hashCode(),
                p2.hashCode()
        );
    }

    @Test
    void ipv4AndIpv6TextAreDistinctKeys() {
        var v4 = EndpointKey.of(InetSocketAddress.createUnresolved(V4, 25565));
        var v6 = EndpointKey.of(InetSocketAddress.createUnresolved(V6, 25565));
        var v6OtherPort = EndpointKey.of(InetSocketAddress.createUnresolved(V6, 25566));
        assertNotEquals(
                v4,
                v6
        );
        assertNotEquals(
                v6,
                v6OtherPort
        );
        assertEquals(
                v6,
                EndpointKey.of(InetSocketAddress.createUnresolved(V6, 25565))
        );
    }

    @Test
    void hostComparisonIsExactNoCaseFoldingAndNoDns() {
        var upper = EndpointKey.of(InetSocketAddress.createUnresolved("Example.Host", 25565));
        var lower = EndpointKey.of(InetSocketAddress.createUnresolved("example.host", 25565));
        assertNotEquals(upper, lower);
    }

    @Test
    void portOutOfRangeRejected() {
        IntStream.of(0, -1, 65536)
                .forEach(i -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new EndpointKey(V4, i)
                ));
    }

    @SuppressWarnings({"NullAway", "DataFlowIssue"})
    @Test
    void nullHostRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new EndpointKey(null, 25565)
        );
        assertThrows(
                NullPointerException.class,
                () -> EndpointKey.of(null)
        );
    }

}
