package top.tangge233.netbridge.ability;

import top.tangge233.netbridge.transport.AcceleratedTransport;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class NetworksAbility {

    private static final NetworksAbility EMPTY = new NetworksAbility(Map.of());
    private final Map<AcceleratedTransport, NetworksEntry> entries;

    private NetworksAbility(Map<AcceleratedTransport, NetworksEntry> entries) {
        var copy = new EnumMap<AcceleratedTransport, NetworksEntry>(AcceleratedTransport.class);
        entries.forEach((transport, entry) -> {
            if (transport != null && entry != null) {
                copy.put(transport, entry);
            }
        });
        this.entries = Collections.unmodifiableMap(copy);
    }

    public static NetworksAbility empty() {
        return EMPTY;
    }

    public static NetworksAbility of(
            @Nullable Map<AcceleratedTransport, NetworksEntry> entries
    ) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY;
        }

        var map = new EnumMap<AcceleratedTransport, NetworksEntry>(AcceleratedTransport.class);
        entries.forEach((transport, entry) -> {
            if (transport != null && entry != null) {
                map.put(transport, entry);
            }
        });
        return map.isEmpty()
                ? EMPTY
                : new NetworksAbility(map);
    }

    public Map<AcceleratedTransport, NetworksEntry> entries() {
        return entries;
    }

    public @Nullable NetworksEntry entry(AcceleratedTransport transport) {
        return entries.get(transport);
    }

    public boolean usable(AcceleratedTransport transport) {
        var entry = entries.get(transport);
        return entry != null && entry.usable();
    }

    public boolean hasUsableAccelerated() {
        return entries.values().stream().anyMatch(NetworksEntry::usable);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NetworksAbility other
                && entries.equals(other.entries);
    }

    @Override
    public String toString() {
        return entries.toString();
    }

}
