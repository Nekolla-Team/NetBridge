package top.tangge233.netbridge.runtime;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.client.ClientRuntime;
import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.nativebridge.UnavailableNativeTransportBackend;
import top.tangge233.netbridge.nativebridge.internal.ffm.NativeResourceException;
import top.tangge233.netbridge.server.ServerRuntime;

import java.util.ArrayList;
import java.util.List;

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
        this.configPaths = configPaths;
        this.clientSettings = clientSettings;
        this.serverConfigStore = serverConfigStore;
        this.nativeBackend = nativeBackend;
        this.clientRuntime = new ClientRuntime(clientSettings, nativeBackend);
        this.serverRuntime = new ServerRuntime(nativeBackend, serverConfigStore);
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
            NetBridge.LOGGER.warn("Error closing server runtime: {}", t.getMessage());
            errors.add(t);
        }

        try {
            clientRuntime.close();
        } catch (Throwable t) {
            NetBridge.LOGGER.warn("Error closing client runtime: {}", t.getMessage());
            errors.add(t);
        }

        try {
            nativeBackend.close();
        } catch (Throwable t) {
            NetBridge.LOGGER.warn("Error closing native backend: {}", t.getMessage());
            errors.add(t);
        }

        if (!errors.isEmpty()) {
            state = State.CLOSE_FAILED;
            var primary = new NativeResourceException("Failed to close NetBridgeRuntime cleanly");
            for (var err : errors) {
                primary.addSuppressed(err);
            }
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
