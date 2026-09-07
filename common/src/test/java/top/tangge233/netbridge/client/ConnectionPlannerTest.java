package top.tangge233.netbridge.client;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.config.client.ClientSettings;
import top.tangge233.netbridge.transport.AcceleratedTransport;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionPlannerTest {

    private final ConnectionPlanner planner = new ConnectionPlanner();

    @Test
    void tcpModeNeverPlansNative() {
        var settings = new ClientSettings(
                TransportMode.TCP,
                KcpProfile.BALANCE
        );
        var plan = planner.plan(
                addr(25565),
                settings,
                NetworksAbility.empty(),
                Optional.empty(),
                true
        );
        assertInstanceOf(TcpPlan.class, plan);
    }

    private static InetSocketAddress addr(int port) {
        return new InetSocketAddress("203.0.113.1", port);
    }

    @Test
    void nativeUnavailablePlansTcpOnly() {
        var settings = new ClientSettings(
                TransportMode.QUIC,
                KcpProfile.BALANCE
        );
        var plan = planner.plan(
                addr(25565),
                settings,
                NetworksAbility.empty(),
                Optional.empty(),
                false
        );
        assertInstanceOf(TcpPlan.class, plan);
    }

    @Test
    void unadvertisedOrUnusableServerPlansTcp() {
        var settings = new ClientSettings(
                TransportMode.QUIC,
                KcpProfile.BALANCE
        );
        assertInstanceOf(
                TcpPlan.class, planner.plan(
                        addr(25565),
                        settings,
                        NetworksAbility.empty(),
                        Optional.empty(),
                        true
                )
        );

        // Transport advertised but explicitly disabled -> not usable.
        var disabled = NetworksAbility.of(Map.of(
                AcceleratedTransport.QUIC, new NetworksEntry(false, null, 25565)
        ));
        assertInstanceOf(
                TcpPlan.class, planner.plan(
                        addr(25565),
                        settings,
                        disabled,
                        Optional.empty(),
                        true
                )
        );
    }

    @Test
    void advertisedEndpointFollowsAddressHostWhenMissing() {
        var settings = new ClientSettings(
                TransportMode.QUIC,
                KcpProfile.BALANCE
        );
        var advertised = quicAt(2443);
        var plan = planner.plan(
                addr(25565),
                settings,
                advertised,
                Optional.empty(),
                true
        );
        assertInstanceOf(AcceleratedPlan.class, plan);
        var attempt = ((AcceleratedPlan) plan).nativeAttempt();
        assertInstanceOf(QuicAttempt.class, attempt);
        var quic = (QuicAttempt) attempt;
        assertEquals(
                2443,
                quic.endpoint().getPort()
        );
        assertTrue(quic.endpoint().getHostString().startsWith("203.0.113."));
    }

    private static NetworksAbility quicAt(int port) {
        return NetworksAbility.of(Map.of(
                AcceleratedTransport.QUIC, new NetworksEntry(true, null, port)
        ));
    }

    @Test
    void kcpModeUsesKcpEntryOnly() {
        var settings = new ClientSettings(
                TransportMode.KCP,
                KcpProfile.BALANCE
        );
        var advertised = NetworksAbility.of(Map.of(
                AcceleratedTransport.QUIC, new NetworksEntry(true, null, 2443),
                AcceleratedTransport.KCP, new NetworksEntry(true, null, 2444)
        ));
        var plan = planner.plan(
                addr(25565),
                settings,
                advertised,
                Optional.empty(),
                true
        );
        assertInstanceOf(AcceleratedPlan.class, plan);
        var attempt = ((AcceleratedPlan) plan).nativeAttempt();
        assertInstanceOf(KcpAttempt.class, attempt);
        assertEquals(
                2444,
                attempt.endpoint().getPort()
        );
    }

    @Test
    void recentSuccessTakesPriorityOverAdvertisement() {
        var settings = new ClientSettings(
                TransportMode.QUIC,
                KcpProfile.BALANCE
        );
        var advertised = quicAt(2443);
        var recent = Optional.of(new TransportTarget(
                AcceleratedTransport.QUIC,
                new InetSocketAddress("1.2.3.4", 9999)
        ));
        var plan = planner.plan(
                addr(25565),
                settings,
                advertised,
                recent,
                true
        );
        assertInstanceOf(AcceleratedPlan.class, plan);
        var attempt = ((AcceleratedPlan) plan).nativeAttempt();
        assertInstanceOf(QuicAttempt.class, attempt);
        var quic = (QuicAttempt) attempt;
        assertEquals(
                9999,
                quic.endpoint().getPort()
        );
        assertEquals(
                "1.2.3.4",
                quic.endpoint().getHostString()
        );
    }

    @Test
    void recentSuccessIgnoredWhenTransportDiffers() {
        var settings = new ClientSettings(
                TransportMode.KCP,
                KcpProfile.BALANCE
        );
        var advertised = kcpAt(2444);
        var recent = Optional.of(new TransportTarget(
                AcceleratedTransport.QUIC,
                new InetSocketAddress("1.2.3.4", 9999)
        ));
        var plan = planner.plan(
                addr(25565),
                settings,
                advertised,
                recent,
                true
        );
        assertInstanceOf(AcceleratedPlan.class, plan);
        var attempt = ((AcceleratedPlan) plan).nativeAttempt();
        assertInstanceOf(KcpAttempt.class, attempt);
        assertEquals(
                2444,
                attempt.endpoint().getPort()
        );
    }

    private static NetworksAbility kcpAt(int port) {
        return NetworksAbility.of(Map.of(
                AcceleratedTransport.KCP, new NetworksEntry(true, null, port)
        ));
    }

    @Test
    void kcpAttemptCarriesProfile() {
        var settings = new ClientSettings(
                TransportMode.KCP,
                KcpProfile.AGGRESSIVE
        );
        var advertised = kcpAt(2444);
        var plan = planner.plan(
                addr(25565),
                settings,
                advertised,
                Optional.empty(),
                true
        );
        assertInstanceOf(AcceleratedPlan.class, plan);
        var attempt = ((AcceleratedPlan) plan).nativeAttempt();
        assertInstanceOf(KcpAttempt.class, attempt);
        assertEquals(
                KcpProfile.AGGRESSIVE,
                ((KcpAttempt) attempt).profile()
        );
    }

    @Test
    void modeParsingAcceptsThreeValuesOnly() {
        assertEquals(
                TransportMode.TCP,
                TransportMode.parse("tcp")
        );
        assertEquals(
                TransportMode.QUIC,
                TransportMode.parse("QUIC")
        );
        assertEquals(
                TransportMode.KCP,
                TransportMode.parse(" kcp ")
        );
        assertNull(TransportMode.parse("quic-fallback"));
        assertNull(TransportMode.parse(null));
    }

    @Test
    void profileParsingWithAlias() {
        assertEquals(
                KcpProfile.BALANCE,
                KcpProfile.parse("balance")
        );
        assertEquals(
                KcpProfile.BALANCE,
                KcpProfile.parse("balanced")
        );
        assertEquals(
                KcpProfile.AGGRESSIVE,
                KcpProfile.parse(" Aggressive ")
        );
        assertNull(KcpProfile.parse("turbo"));
        assertEquals(
                "balance",
                KcpProfile.BALANCE.configValue()
        );
    }

}
