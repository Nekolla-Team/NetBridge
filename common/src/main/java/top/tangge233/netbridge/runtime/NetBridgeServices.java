package top.tangge233.netbridge.runtime;

import top.tangge233.netbridge.client.ClientRuntime;
import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.config.NetBridgeProperties;
import top.tangge233.netbridge.config.client.ClientConfigStore;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.nativebridge.UnavailableNativeTransportBackend;
import top.tangge233.netbridge.nativebridge.internal.ffm.FfmNativeTransportBackend;
import top.tangge233.netbridge.nativebridge.internal.ffm.NativeLibraryResolver;
import top.tangge233.netbridge.server.ServerRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public final class NetBridgeServices {

    private static volatile @Nullable NetBridgeRuntime runtime;

    private NetBridgeServices() {
    }

    public static synchronized NetBridgeRuntime bootstrap(ConfigPaths paths) {
        var existing = runtime;
        if (existing != null) {
            throw new IllegalStateException("NetBridge has already been bootstrapped");
        }

        var properties = NetBridgeProperties.load();
        var clientSettings = ClientSettingsService.create(
                new ClientConfigStore(paths.clientFile()),
                properties.transportOverride()
        );
        var serverConfigStore = new ServerConfigStore(paths.serverFile());
        var backend = createNativeBackend(properties);
        var created = new NetBridgeRuntime(
                paths,
                clientSettings,
                serverConfigStore,
                backend,
                properties.quicPort()
        );
        runtime = created;
        return created;
    }

    private static NativeTransportBackend createNativeBackend(NetBridgeProperties properties) {
        try {
            var libraryPath = resolveLibraryPath(properties);
            return FfmNativeTransportBackend.load(libraryPath, 4);
        } catch (RuntimeException e) {
            return new UnavailableNativeTransportBackend(String.valueOf(e.getMessage()));
        }
    }

    private static Path resolveLibraryPath(NetBridgeProperties properties) {
        var overridePath = properties.nativeLibraryPath();
        if (overridePath != null) {
            if (!Files.exists(overridePath)) {
                throw new IllegalStateException(
                        NetBridgeProperties.KEY_NATIVE_PATH + " points to missing library: "
                                + overridePath
                );
            }
            return overridePath;
        }
        return NativeLibraryResolver.extractPackagedLibrary(properties.nativeCacheDirectory());
    }

    public static synchronized void close() {
        resetForTest();
    }

    public static synchronized void resetForTest() {
        var existing = runtime;
        if (existing != null) {
            existing.close();
            runtime = null;
        }
    }

    public static boolean nativeAvailable() {
        return runtime().nativeAvailable();
    }

    public static NetBridgeRuntime runtime() {
        var r = runtime;
        if (r == null) {
            throw new IllegalStateException("NetBridge has not been bootstrapped");
        }
        return r;
    }

    public static ConfigPaths configPaths() {
        return runtime().configPaths();
    }

    public static ClientSettingsService clientSettings() {
        return runtime().clientSettings();
    }

    public static ServerConfigStore serverConfigStore() {
        return runtime().serverConfigStore();
    }

    public static ClientRuntime clientRuntime() {
        return runtime().clientRuntime();
    }

    public static ServerRuntime serverRuntime() {
        return runtime().serverRuntime();
    }

}
