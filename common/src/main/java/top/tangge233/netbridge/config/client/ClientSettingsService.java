package top.tangge233.netbridge.config.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import org.jspecify.annotations.Nullable;

public final class ClientSettingsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSettingsService.class);

    private final ClientConfigStore store;
    private volatile ClientSettings current;

    public ClientSettingsService(
            ClientConfigStore store,
            ClientSettings initial
    ) {
        this.store = store;
        this.current = initial;
    }

    public static ClientSettingsService create(ClientConfigStore store) {
        return create(store, null);
    }

    public static ClientSettingsService create(
            ClientConfigStore store,
            @Nullable TransportMode transportOverride
    ) {
        var loaded = store.load();
        if (transportOverride != null) {
            LOGGER.info("Transport mode override: {}", transportOverride);
            loaded = new ClientSettings(transportOverride, loaded.kcpProfile());
        }
        return new ClientSettingsService(store, loaded);
    }

    public ClientSettings current() {
        return current;
    }

    public void updateMode(TransportMode mode) {
        current = new ClientSettings(mode, current.kcpProfile());
        LOGGER.info("Transport mode updated to {}", mode);
        store.save(current);
    }

    public void updateKcpProfile(KcpProfile profile) {
        current = new ClientSettings(current.mode(), profile);
        LOGGER.info("KCP profile updated to {}", profile);
        store.save(current);
    }

}
