package top.tangge233.netbridge.ability;

import org.jspecify.annotations.Nullable;

public record NetworksEntry(
        boolean enabled,
        @Nullable String host,
        int port
) {

    public NetworksEntry {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in range 1..65535: " + port);
        }
        if (host != null && host.isBlank()) {
            host = null;
        }
    }

    public boolean usable() {
        return enabled;
    }

}
