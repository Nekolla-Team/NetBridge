package top.tangge233.netbridge.config;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.config.client.ClientSettings;
import top.tangge233.netbridge.config.server.ServerSettingsResolver.ResolvedServerSettings;
import top.tangge233.netbridge.config.server.ServerSettingsResolver.ResolvedTransport;
import top.tangge233.netbridge.config.server.ServerTransportSettings;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("DataFlowIssue")
class ConfigValueInvariantTest {

    @Test
    @SuppressWarnings("NullAway")
    void configPathsRequireDirectory() {
        assertThrows(
                NullPointerException.class,
                () -> new ConfigPaths(null)
        );
    }

    @Test
    @SuppressWarnings("NullAway")
    void clientSettingsRequireModeAndProfile() {
        assertThrows(
                NullPointerException.class,
                () -> new ClientSettings(null, KcpProfile.BALANCE)
        );
        assertThrows(
                NullPointerException.class,
                () -> new ClientSettings(TransportMode.TCP, null)
        );
    }

    @Test
    void serverTransportSettingsPortRangeAcceptedAtBoundaries() {
        assertEquals(
                -1,
                new ServerTransportSettings(
                        true,
                        null,
                        null,
                        -1,
                        1,
                        null
                ).port()
        );
        assertEquals(
                0,
                new ServerTransportSettings(
                        true,
                        null,
                        null,
                        0,
                        1,
                        null
                ).port()
        );
        assertEquals(
                65535,
                new ServerTransportSettings(
                        true,
                        null,
                        null,
                        65535,
                        1,
                        null
                ).port()
        );
    }

    @Test
    void serverTransportSettingsPortRangeRejectsOutOfRange() {
        IntStream.of(-2, 65536)
                .forEach(i -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ServerTransportSettings(
                                true,
                                null,
                                null,
                                i,
                                1,
                                null
                        )
                ));
    }

    @Test
    void serverTransportSettingsRejectsMaxConnectionsBelowOne() {
        IntStream.of(0, -5)
                .forEach(i -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ServerTransportSettings(
                                true,
                                null,
                                null,
                                -1,
                                i,
                                null
                        )
                ));
    }

    @Test
    void serverTransportSettingsNormalizesBlankHostsToNull() {
        var settings = new ServerTransportSettings(
                true,
                "  ",
                "\t",
                25565,
                64,
                KcpProfile.BALANCE
        );
        assertNull(settings.bindHost());
        assertNull(settings.advertisedHost());
    }

    @Test
    @SuppressWarnings("NullAway")
    void resolvedServerSettingsRequireBothTransports() {
        var quic = resolved(false, -1);
        var kcp = resolved(false, -1);
        assertThrows(
                NullPointerException.class,
                () -> new ResolvedServerSettings(null, kcp)
        );
        assertThrows(
                NullPointerException.class,
                () -> new ResolvedServerSettings(quic, null)
        );
    }

    private static ResolvedTransport resolved(boolean enabled, int listenPort) {
        return new ResolvedTransport(
                enabled,
                listenPort,
                null,
                null,
                64,
                null
        );
    }

    @Test
    void resolvedTransportListenPortMatchesEnabledFlag() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolved(true, -1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> resolved(false, 0)
        );
        assertEquals(
                -1,
                resolved(false, -1).listenPort()
        );
        assertEquals(
                0,
                resolved(true, 0).listenPort()
        );
        assertEquals(
                65535,
                resolved(true, 65535).listenPort()
        );
    }

    @Test
    void resolvedTransportRejectsOutOfRangeListenPortAndMaxConnections() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolved(true, 65536)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResolvedTransport(
                        true,
                        25565,
                        null,
                        null,
                        0,
                        null
                )
        );
    }

    @Test
    void resolvedTransportAccessorsWork() {
        var resolved = resolved(true, 2443);
        assertEquals(2443, resolved.listenPort());
        assertEquals(64, resolved.maxConnections());
        assertNull(resolved.bindHost());
        assertNull(resolved.advertisedHost());
        assertNull(resolved.kcpProfile());
    }

}
