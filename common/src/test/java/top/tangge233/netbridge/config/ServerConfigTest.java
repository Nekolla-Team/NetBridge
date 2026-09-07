package top.tangge233.netbridge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.config.server.ServerSettings;
import top.tangge233.netbridge.config.server.ServerSettingsResolver;
import top.tangge233.netbridge.transport.KcpProfile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @Test
    void loadsDefaultsWhenFileDoesNotExist(@TempDir Path dir) {
        var serverFile = dir.resolve("server.toml");
        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertTrue(settings.quic().enabled());
        assertEquals(-1, settings.quic().port());
        assertNull(settings.quic().bindHost());
        assertEquals(256, settings.quic().maxConnections());

        assertFalse(settings.kcp().enabled(), "KCP 默认关闭");
        assertEquals(-1, settings.kcp().port());
        assertEquals(KcpProfile.BALANCE, settings.kcp().kcpProfile());

        assertTrue(Files.exists(serverFile), "加载时应自动从模板释放生成 server.toml");
    }

    @Test
    void readsCustomConfigurationCorrectly(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(
                serverFile,
                """
                        [quic]
                        enable = true
                        port = 0
                        bind = "127.0.0.1"
                        host = "quic.example.org"
                        max_connection = 64
                        
                        [kcp]
                        enable = true
                        port = 30000
                        host = ""
                        bind = "192.168.1.100"
                        max_connection = 8
                        profile = "aggressive"
                        """
        );

        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertTrue(settings.quic().enabled());
        assertEquals(0, settings.quic().port());
        assertEquals("127.0.0.1", settings.quic().bindHost());
        assertEquals("quic.example.org", settings.quic().advertisedHost());
        assertEquals(64, settings.quic().maxConnections());

        assertTrue(settings.kcp().enabled());
        assertEquals(30000, settings.kcp().port());
        assertNull(settings.kcp().advertisedHost(), "空串归一化为 null");
        assertEquals("192.168.1.100", settings.kcp().bindHost());
        assertEquals(8, settings.kcp().maxConnections());
        assertEquals(KcpProfile.AGGRESSIVE, settings.kcp().kcpProfile());
    }

    @Test
    void defaultsApplyForMissingFields(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(serverFile, "[quic]\n[kcp]\n");

        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertTrue(settings.quic().enabled());
        assertEquals(256, settings.quic().maxConnections());
        assertFalse(settings.kcp().enabled(), "缺 enable 默认 false");
        assertEquals(KcpProfile.BALANCE, settings.kcp().kcpProfile());
    }

    @Test
    void brokenConfigFallsBackToDefaults(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(serverFile, "this is not valid toml [[[[");

        var store = new ServerConfigStore(serverFile);
        assertDoesNotThrow(store::load);
        assertTrue(store.load().quic().enabled());
    }

    @Test
    void serverSettingsResolverResolvesPortsAndBindIp() {
        var settings = ServerSettings.defaults();
        var resolved = ServerSettingsResolver.resolve(
                settings,
                25565,
                "127.0.0.1"
        );

        // QUIC: enabled, port=-1 -> follows 25565
        assertTrue(resolved.quic().enabled());
        assertEquals(25565, resolved.quic().listenPort());
        assertEquals("127.0.0.1", resolved.quic().bindHost());

        // KCP: disabled by default
        assertFalse(resolved.kcp().enabled());

        // Explicit QUIC port override (injected parsed property)
        var overridden = ServerSettingsResolver.resolve(
                settings,
                25565,
                null,
                28888
        );
        assertEquals(28888, overridden.quic().listenPort());
        assertNull(overridden.quic().bindHost());

        // Test invalid mcPort with -1 follow
        var invalidMcPort = ServerSettingsResolver.resolve(
                settings,
                70000,
                null
        );
        assertFalse(invalidMcPort.quic().enabled(), "MC 端口越界时应禁用该传输");
    }

    @Test
    void wrongTypeInOneFieldDoesNotResetOthers(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(
                serverFile,
                """
                        [quic]
                        enable = "yes"
                        port = 20000
                        max_connection = 64
                        """
        );

        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertTrue(settings.quic().enabled(), "enable 非布尔仅回退默认 true，不影响其余字段");
        assertEquals(20000, settings.quic().port());
        assertEquals(64, settings.quic().maxConnections());
    }

    @Test
    void invalidPortOnlyDefaultsPortField(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(
                serverFile,
                """
                        [quic]
                        enable = true
                        port = 999999
                        max_connection = 32
                        """
        );

        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertEquals(-1, settings.quic().port(), "越界端口只回退该字段默认值");
        assertEquals(32, settings.quic().maxConnections(), "其它字段不受影响");
    }

    @Test
    void invalidMaxConnectionsOnlyDefaultsThatField(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(
                serverFile,
                """
                        [quic]
                        enable = true
                        port = 2443
                        max_connection = 0
                        """
        );

        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertEquals(
                2443,
                settings.quic().port()
        );
        assertEquals(
                256,
                settings.quic().maxConnections(),
                "max_connection<1 只回退默认值"
        );
    }

    @Test
    void unknownProfileOnlyDefaultsThatField(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(
                serverFile,
                """
                        [kcp]
                        enable = true
                        port = 30001
                        max_connection = 8
                        profile = "ultra_fast_nonexistent"
                        """
        );

        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertEquals(30001, settings.kcp().port());
        assertEquals(KcpProfile.BALANCE, settings.kcp().kcpProfile());
    }

    @Test
    void unknownTomlKeysAreIgnored(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(
                serverFile,
                """
                        [quic]
                        enable = true
                        port = 2443
                        some_future_key = "abc"
                        """
        );

        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertTrue(settings.quic().enabled());
        assertEquals(2443, settings.quic().port());
        assertEquals(256, settings.quic().maxConnections());
    }

}
