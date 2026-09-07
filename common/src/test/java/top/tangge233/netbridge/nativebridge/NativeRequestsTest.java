package top.tangge233.netbridge.nativebridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeRequestsTest {

    @Test
    void connectQuicFactoryBuildsQuicVariant() {
        var req = NativeConnectRequest.quic("127.0.0.1", 25565);
        assertInstanceOf(NativeConnectRequest.Quic.class, req);
        assertEquals(NativeTransportKind.QUIC, req.transport());
        assertEquals("127.0.0.1", req.host());
        assertEquals(25565, req.port());
    }

    @Test
    void connectKcpFactoryBuildsKcpVariantWithProfile() {
        var req = NativeConnectRequest.kcp(
                "127.0.0.1",
                25565,
                NativeKcpProfile.AGGRESSIVE
        );
        assertInstanceOf(NativeConnectRequest.Kcp.class, req);
        var kcp = req;
        assertEquals(NativeTransportKind.KCP, req.transport());
        assertEquals(NativeKcpProfile.AGGRESSIVE, kcp.profile());
    }

    @Test
    @SuppressWarnings({"NullAway", "DataFlowIssue"})
    void connectRejectsInvalidHostAndPort() {
        assertThrows(NullPointerException.class, () -> NativeConnectRequest.quic(null, 1));
        assertThrows(IllegalArgumentException.class, () -> NativeConnectRequest.quic("  ", 1));
        assertThrows(IllegalArgumentException.class, () -> NativeConnectRequest.quic("h", 0));
        assertThrows(IllegalArgumentException.class, () -> NativeConnectRequest.quic("h", 65536));
        assertThrows(IllegalArgumentException.class, () -> NativeConnectRequest.quic("h", -1));
    }

    @Test
    @SuppressWarnings({"NullAway", "DataFlowIssue"})
    void connectKcpRequiresProfile() {
        assertThrows(
                NullPointerException.class,
                () -> NativeConnectRequest.kcp("h", 1, null)
        );
    }

    @Test
    void serverQuicFactoryWithoutBindHostDefaultsNull() {
        var req = NativeServerRequest.quic(0, 16);
        assertInstanceOf(NativeServerRequest.Quic.class, req);
        assertEquals(NativeTransportKind.QUIC, req.transport());
        assertNull(req.bindHost());
        assertEquals(0, req.port());
        assertEquals(16, req.maxConnections());
    }

    @Test
    void serverQuicCarriesBindHostWhenGiven() {
        var req = NativeServerRequest.quic("0.0.0.0", 2443, 64);
        assertEquals("0.0.0.0", req.bindHost());
        assertEquals(2443, req.port());
    }

    @Test
    void serverKcpCarriesProfile() {
        var req = NativeServerRequest.kcp(0, 8, NativeKcpProfile.AGGRESSIVE);
        assertInstanceOf(NativeServerRequest.Kcp.class, req);
        var kcp = req;
        assertEquals(NativeTransportKind.KCP, req.transport());
        assertEquals(NativeKcpProfile.AGGRESSIVE, kcp.profile());
        assertNull(req.bindHost());
    }

    @Test
    void blankBindHostNormalizesToNull() {
        assertNull(NativeServerRequest.quic("  ", 1, 2).bindHost());
    }

    @Test
    void serverPortAllowsEphemeralZeroButRejectsOutOfRange() {
        assertDoesNotThrow(() -> NativeServerRequest.quic(0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeServerRequest.quic(-1, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeServerRequest.quic(65536, 1)
        );
    }

    @Test
    void serverRequiresPositiveMaxConnections() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeServerRequest.quic(1, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeServerRequest.quic(1, -1)
        );
    }

    @Test
    @SuppressWarnings({"NullAway", "DataFlowIssue"})
    void serverKcpRequiresProfile() {
        assertThrows(
                NullPointerException.class,
                () -> NativeServerRequest.kcp(1, 2, null)
        );
    }

}
