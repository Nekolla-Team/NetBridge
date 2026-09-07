package top.tangge233.netbridge.client;

import top.tangge233.netbridge.transport.AcceleratedTransport;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Bounded positive cache of endpoints that have been reached via an accelerated transport.
 *
 * <p>Entries expire after a {@link Duration} TTL. Elapsed time uses a monotonic ticker
 * (nanoseconds via {@link System#nanoTime()} by default) so expiration is immune to wall-clock
 * changes; a fake ticker is injected in tests.</p>
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>a lookup removes and misses an expired entry;</li>
 *   <li>a record for the same key overwrites the previous value and resets the TTL;</li>
 *   <li>capacity is bounded ({@code maxEntries}); when exceeded, expired entries are evicted first
 *       and then the entry with the oldest expiry is removed deterministically;</li>
 *   <li>{@code maxEntries <= 0} or a {@link Duration#ZERO} TTL disables the cache (record is a
 *       no-op, lookup always misses).</li>
 * </ul>
 */
public final class SuccessfulEndpointCache {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    public static final int DEFAULT_MAX_ENTRIES = 256;

    private final LongSupplier ticker;
    private final long ttlNanos;
    private final int maxEntries;
    private final Map<EndpointKey, Entry> entries = new ConcurrentHashMap<>();

    public SuccessfulEndpointCache() {
        this(
                System::nanoTime,
                DEFAULT_TTL,
                DEFAULT_MAX_ENTRIES
        );
    }

    public SuccessfulEndpointCache(
            LongSupplier ticker,
            Duration ttl,
            int maxEntries
    ) {
        this.ticker = requireNonNull(ticker, "ticker");
        requireNonNull(ttl, "ttl");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be non-negative: " + ttl);
        }
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = maxEntries;
    }

    public SuccessfulEndpointCache(
            LongSupplier ticker,
            Duration ttl
    ) {
        this(
                ticker,
                ttl,
                DEFAULT_MAX_ENTRIES
        );
    }

    public Optional<TransportTarget> lookup(
            @Nullable InetSocketAddress address,
            TransportMode mode
    ) {
        if (address == null) {
            return Optional.empty();
        }

        var key = EndpointKey.of(address);
        var entry = entries.get(key);

        if (entry == null) {
            return Optional.empty();
        }

        if (entry.expiry() - ticker.getAsLong() <= 0) {
            entries.remove(key);
            return Optional.empty();
        }

        if (entry.target().transport() != AcceleratedTransport.fromMode(mode)) {
            return Optional.empty();
        }

        return Optional.of(entry.target());
    }

    public void record(
            @Nullable InetSocketAddress address,
            @Nullable TransportTarget target
    ) {
        if (address == null || target == null || !enabled()) {
            return;
        }

        var now = ticker.getAsLong();
        var key = EndpointKey.of(address);

        entries.put(
                key,
                new Entry(target, now + ttlNanos)
        );

        if (entries.size() > maxEntries) {
            evictExpired(now);
            while (entries.size() > maxEntries) {
                var oldest = entries.entrySet().stream()
                        .min(
                                Map.Entry.comparingByValue(
                                        Comparator.comparingLong(Entry::expiry)
                                )
                        )
                        .orElse(null);
                if (oldest == null) {
                    break;
                }
                entries.remove(oldest.getKey());
            }
        }
    }

    private boolean enabled() {
        return maxEntries > 0 && ttlNanos > 0;
    }

    private void evictExpired(long now) {
        entries.entrySet().removeIf(e ->
                e.getValue().expiry() - now <= 0
        );
    }

    public void invalidate(@Nullable InetSocketAddress address) {
        if (address != null) {
            entries.remove(EndpointKey.of(address));
        }
    }

    private record Entry(
            TransportTarget target,
            long expiry
    ) {

    }

}
