package top.tangge233.netbridge.runtime;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.client.ClientRuntime;
import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.nativebridge.UnavailableNativeTransportBackend;
import top.tangge233.netbridge.server.ServerRuntime;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class NetBridgeRuntime implements AutoCloseable {

    private final ConfigPaths configPaths;
    private final ClientSettingsService clientSettings;
    private final ServerConfigStore serverConfigStore;
    private final NativeTransportBackend nativeBackend;
    private final ClientRuntime clientRuntime;
    private final ServerRuntime serverRuntime;
    private volatile State state = State.OPEN;

    public NetBridgeRuntime(
            ConfigPaths configPaths,
            ClientSettingsService clientSettings,
            ServerConfigStore serverConfigStore,
            NativeTransportBackend nativeBackend
    ) {
        this(
                configPaths,
                clientSettings,
                serverConfigStore,
                nativeBackend,
                null
        );
    }

    public NetBridgeRuntime(
            ConfigPaths configPaths,
            ClientSettingsService clientSettings,
            ServerConfigStore serverConfigStore,
            NativeTransportBackend nativeBackend,
            @Nullable Integer quicPortOverride
    ) {
        this.configPaths = configPaths;
        this.clientSettings = clientSettings;
        this.serverConfigStore = serverConfigStore;
        this.nativeBackend = nativeBackend;
        this.clientRuntime = new ClientRuntime(clientSettings, nativeBackend);
        this.serverRuntime = new ServerRuntime(nativeBackend, serverConfigStore, quicPortOverride);
    }

    public State state() {
        return state;
    }

    public ConfigPaths configPaths() {
        return configPaths;
    }

    public ClientSettingsService clientSettings() {
        return clientSettings;
    }

    public ServerConfigStore serverConfigStore() {
        return serverConfigStore;
    }

    public boolean nativeAvailable() {
        return state == State.OPEN
                && !(nativeBackend instanceof UnavailableNativeTransportBackend)
                && nativeBackend.availability().available();
    }

    public ClientRuntime clientRuntime() {
        return clientRuntime;
    }

    public ServerRuntime serverRuntime() {
        return serverRuntime;
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }

        state = State.CLOSING;
        List<Throwable> errors = new ArrayList<>();

        try {
            serverRuntime.close();
        } catch (Throwable t) {
            NetBridge.LOGGER.warn("Error closing server runtime", t);
            errors.add(t);
        }

        try {
            clientRuntime.close();
        } catch (Throwable t) {
            NetBridge.LOGGER.warn("Error closing client runtime", t);
            errors.add(t);
        }

        try {
            nativeBackend.close();
        } catch (Throwable t) {
            NetBridge.LOGGER.warn("Error closing native backend", t);
            errors.add(t);
        }

        if (!errors.isEmpty()) {
            state = State.CLOSE_FAILED;
            var primary = new RuntimeException("Failed to close NetBridgeRuntime cleanly");
            errors.forEach(primary::addSuppressed);
            throw primary;
        }

        state = State.CLOSED;
    }

    public enum State {

        OPEN,
        CLOSING,
        CLOSED,
        CLOSE_FAILED

    }

}
