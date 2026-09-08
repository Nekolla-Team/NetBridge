package top.tangge233.netbridge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.netbridge.config.client.ClientConfigStore;
import top.tangge233.netbridge.config.client.ClientSettings;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientConfigTest {

    @Test
    void loadsDefaultsWhenFileDoesNotExist(@TempDir Path dir) {
        var clientFile = dir.resolve("client.toml");
        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(TransportMode.TCP, settings.mode());
        assertEquals(KcpProfile.BALANCE, settings.kcpProfile());
    }

    @Test
    void readsLegacyClientTomlFixture(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(
                clientFile,
                """
                        mode = "kcp"
                        [kcp]
                        profile = "aggressive"
                        """
        );

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(TransportMode.KCP, settings.mode());
        assertEquals(KcpProfile.AGGRESSIVE, settings.kcpProfile());
    }

    @Test
    void saveAndLoadRoundtrip(@TempDir Path dir) {
        var clientFile = dir.resolve("client.toml");
        var store = new ClientConfigStore(clientFile);

        var toSave = new ClientSettings(TransportMode.QUIC, KcpProfile.AGGRESSIVE);
        store.save(toSave);

        var loaded = store.load();
        assertEquals(TransportMode.QUIC, loaded.mode());
        assertEquals(KcpProfile.AGGRESSIVE, loaded.kcpProfile());
    }

    @Test
    void handlesMalformedConfigGracefully(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(clientFile, "not a valid toml = [[[[");

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(TransportMode.TCP, settings.mode());
        assertEquals(KcpProfile.BALANCE, settings.kcpProfile());
    }

    @Test
    void handlesUnknownKcpProfileGracefully(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(
                clientFile,
                """
                        mode = "quic"
                        [kcp]
                        profile = "ultra_fast_nonexistent"
                        """
        );

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(
                TransportMode.QUIC,
                settings.mode()
        );
        assertEquals(
                KcpProfile.BALANCE,
                settings.kcpProfile(),
                "Unknown profile should fall back to default BALANCE"
        );
    }

    @Test
    void clientSettingsServiceAppliesTransportOverrideAndPersistsUpdates(@TempDir Path dir) {
        var clientFile = dir.resolve("client.toml");
        var store = new ClientConfigStore(clientFile);

        var service = ClientSettingsService.create(store, TransportMode.KCP);

        assertEquals(TransportMode.KCP, service.current().mode());

        // Runtime UI update
        service.updateMode(TransportMode.QUIC);
        assertEquals(TransportMode.QUIC, service.current().mode());

        // Verify persisted to disk
        var reloaded = store.load();
        assertEquals(TransportMode.QUIC, reloaded.mode());
    }

    @Test
    void wrongTypeModeOnlyDefaultsModeField(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(
                clientFile,
                """
                        mode = 42
                        [kcp]
                        profile = "aggressive"
                        """
        );

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(
                TransportMode.TCP,
                settings.mode(),
                "Non-string mode should fall back only to default TCP"
        );
        assertEquals(
                KcpProfile.AGGRESSIVE,
                settings.kcpProfile(),
                "Invalid mode should not affect a valid profile"
        );
    }

    @Test
    void wrongTypeProfileOnlyDefaultsProfileField(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(
                clientFile,
                """
                        mode = "kcp"
                        [kcp]
                        profile = 7
                        """
        );

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(
                TransportMode.KCP,
                settings.mode()
        );
        assertEquals(
                KcpProfile.BALANCE,
                settings.kcpProfile(),
                "Invalid profile should fall back only to default BALANCE"
        );
    }

    @Test
    void unknownModeStringOnlyDefaultsModeField(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(
                clientFile,
                """
                        mode = "sctp"
                        [kcp]
                        profile = "aggressive"
                        """
        );

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(TransportMode.TCP, settings.mode());
        assertEquals(KcpProfile.AGGRESSIVE, settings.kcpProfile());
    }

    @Test
    void unknownTomlKeyIgnoredByClientStore(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(
                clientFile,
                """
                        mode = "kcp"
                        future_key = "abc"
                        [kcp]
                        profile = "aggressive"
                        """
        );

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(TransportMode.KCP, settings.mode());
        assertEquals(KcpProfile.AGGRESSIVE, settings.kcpProfile());
    }

    @Test
    void atomicSaveOverwritesExistingAndLeavesNoTempFiles(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        var store = new ClientConfigStore(clientFile);
        store.save(new ClientSettings(TransportMode.QUIC, KcpProfile.AGGRESSIVE));
        store.save(new ClientSettings(TransportMode.KCP, KcpProfile.BALANCE));

        var loaded = store.load();
        assertEquals(TransportMode.KCP, loaded.mode());
        assertEquals(KcpProfile.BALANCE, loaded.kcpProfile());

        try (var entries = Files.list(dir)) {
            var names = entries
                    .map(Path::getFileName)
                    .map(Object::toString)
                    .toList();
            assertEquals(
                    1,
                    names.size(),
                    "Only client.toml should remain in the directory after saving"
            );
            assertEquals(
                    "client.toml",
                    names.getFirst()
            );
        }
    }

}
