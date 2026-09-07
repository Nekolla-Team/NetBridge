package top.tangge233.netbridge.client;

import top.tangge233.netbridge.ability.NetworksAbility;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Bounded access-order LRU cache of ping-announced {@link NetworksAbility} per server endpoint.
 *
 * <p>Keys are typed {@link EndpointKey}s (textual host + port). Entries are evicted in
 * least-recently-used order once the capacity ({@code maxEntries}) is exceeded.
 * {@code maxEntries <= 0} disables the cache (record is a no-op, get returns empty).</p>
 */
public final class ServerCapabilityCache {

    public static final int DEFAULT_MAX_ENTRIES = 256;

    private final boolean enabled;
    private final Map<EndpointKey, NetworksAbility> networks;

    public ServerCapabilityCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public ServerCapabilityCache(int maxEntries) {
        this.enabled = maxEntries > 0;
        this.networks = Collections.synchronizedMap(
                new LinkedHashMap<>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<EndpointKey, NetworksAbility> eldest
                    ) {
                        return size() > maxEntries;
                    }
                }
        );
    }

    public void record(
            @Nullable InetSocketAddress address,
            @Nullable NetworksAbility ability
    ) {
        if (address == null || ability == null || !enabled) {
            return;
        }
        networks.put(EndpointKey.of(address), ability);
    }

    public NetworksAbility get(@Nullable InetSocketAddress address) {
        if (address == null || !enabled) {
            return NetworksAbility.empty();
        }
        return networks.getOrDefault(
                EndpointKey.of(address),
                NetworksAbility.empty()
        );
    }

    public void clear() {
        networks.clear();
    }

}
