package top.tangge233.netbridge.client;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.transport.AcceleratedTransport;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ClientCachesAndStateTest {

    @Test
    void capabilityCacheEvictsLru() {
        var cache = new ServerCapabilityCache();
        var advertised = NetworksAbility.of(Map.of(
                AcceleratedTransport.QUIC, new NetworksEntry(true, null, 2443)
        ));
        IntStream.rangeClosed(1, 300)
                .forEachOrdered(i -> cache.record(
                        addr(1000 + i),
                        advertised
                ));
        assertTrue(
                cache.get(addr(1001)).entries().isEmpty(),
                "LRU should evict the least recently used entry"
        );
        assertFalse(cache.get(addr(1300)).entries().isEmpty());
        cache.clear();
        assertTrue(cache.get(addr(1300)).entries().isEmpty());
    }

    private static InetSocketAddress addr(int port) {
        return new InetSocketAddress("203.0.113.7", port);
    }

    @Test
    void successCacheTtlBoundaryAndDisabledSemantics() {
        var now = new AtomicLong(0);
        var cache = new SuccessfulEndpointCache(
                now::get,
                Duration.ofNanos(100)
        );
        var key = addr(25566);

        cache.record(key, quicTarget());
        assertTrue(
                cache.lookup(key, TransportMode.QUIC).isPresent(),
                "before expiry the entry must be present"
        );

        now.addAndGet(99);
        assertTrue(
                cache.lookup(key, TransportMode.QUIC).isPresent(),
                "just before the boundary the entry must be present"
        );

        now.addAndGet(1);
        assertTrue(
                cache.lookup(key, TransportMode.QUIC).isEmpty(),
                "at the exact expiry boundary the entry is expired (<= semantics)"
        );

        var zeroTtl = new SuccessfulEndpointCache(
                now::get,
                Duration.ZERO
        );
        zeroTtl.record(key, quicTarget());
        assertTrue(
                zeroTtl.lookup(key, TransportMode.QUIC).isEmpty(),
                "zero TTL disables caching"
        );

        var noCapacity = new SuccessfulEndpointCache(
                now::get,
                Duration.ofMinutes(1),
                0
        );
        noCapacity.record(key, quicTarget());
        assertTrue(
                noCapacity.lookup(key, TransportMode.QUIC).isEmpty(),
                "maxEntries <= 0 disables caching"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new SuccessfulEndpointCache(
                        now::get,
                        Duration.ofMillis(-1)
                ),
                "negative TTL is rejected at construction"
        );
    }

    private static TransportTarget quicTarget() {
        return new TransportTarget(
                AcceleratedTransport.QUIC,
                new InetSocketAddress("1.2.3.4", 9999)
        );
    }

    @Test
    void successCacheEvictsOldestExpiryWhenOverCapacity() {
        var now = new AtomicLong(0);
        var cache = new SuccessfulEndpointCache(
                now::get,
                Duration.ofNanos(1000),
                2
        );

        cache.record(addr(1), quicTarget());
        now.addAndGet(1);
        cache.record(addr(2), quicTarget());
        now.addAndGet(1);
        cache.record(addr(3), quicTarget());

        assertTrue(
                cache.lookup(addr(1), TransportMode.QUIC).isEmpty(),
                "over-capacity evicts the entry with the oldest expiry"
        );
        assertTrue(cache.lookup(addr(2), TransportMode.QUIC).isPresent());
        assertTrue(cache.lookup(addr(3), TransportMode.QUIC).isPresent());
    }

    @Test
    void successCacheEvictsExpiredEntriesBeforeCapacity() {
        var now = new AtomicLong(0);
        var cache = new SuccessfulEndpointCache(
                now::get,
                Duration.ofNanos(100),
                1
        );

        cache.record(addr(1), quicTarget());
        now.addAndGet(500);
        cache.record(addr(2), quicTarget());

        assertTrue(
                cache.lookup(addr(1), TransportMode.QUIC).isEmpty(),
                "expired entries are removed before capacity eviction"
        );
        assertTrue(cache.lookup(addr(2), TransportMode.QUIC).isPresent());
    }

    @Test
    void successCacheRecordResetsTtlAndOverwritesValue() {
        var now = new AtomicLong(0);
        var cache = new SuccessfulEndpointCache(
                now::get,
                Duration.ofNanos(100),
                1
        );
        var key = addr(25567);

        cache.record(key, quicTarget());
        now.addAndGet(90);
        cache.record(key, quicTarget());
        now.addAndGet(90);
        assertTrue(
                cache.lookup(key, TransportMode.QUIC).isPresent(),
                "re-recording resets the TTL window"
        );
    }

    @Test
    void capabilityCacheCapacityAndDisabledSemantics() {
        var advertised = NetworksAbility.of(Map.of(
                AcceleratedTransport.QUIC, new NetworksEntry(true, null, 2443)
        ));

        var small = new ServerCapabilityCache(2);
        small.record(addr(1), advertised);
        small.record(addr(2), advertised);
        small.record(addr(3), advertised);
        assertTrue(
                small.get(addr(1)).entries().isEmpty(),
                "LRU evicts the least recently used entry"
        );
        assertFalse(small.get(addr(3)).entries().isEmpty());

        var disabled = new ServerCapabilityCache(0);
        disabled.record(addr(1), advertised);
        assertTrue(
                disabled.get(addr(1)).entries().isEmpty(),
                "maxEntries <= 0 disables the capability cache"
        );
    }

    @Test
    void successCacheModeScopedAndExpiring() {
        var now = new AtomicLong(1000);
        var cache = new SuccessfulEndpointCache(now::get, Duration.ofNanos(5000));
        var key = addr(25565);
        var target = new TransportTarget(
                AcceleratedTransport.QUIC,
                new InetSocketAddress("1.2.3.4", 9999)
        );

        assertTrue(cache.lookup(key, TransportMode.QUIC).isEmpty());
        cache.record(key, target);
        assertEquals(
                9999,
                cache.lookup(key, TransportMode.QUIC).orElseThrow().address().getPort()
        );

        assertTrue(
                cache.lookup(key, TransportMode.KCP).isEmpty(),
                "Different modes must not hit the same entry"
        );

        now.addAndGet(5001);
        assertTrue(
                cache.lookup(key, TransportMode.QUIC).isEmpty(),
                "Entry should no longer hit after TTL expiry"
        );
    }

    @Test
    void successCacheOverwriteAndInvalidate() {
        var cache = new SuccessfulEndpointCache();
        var key = addr(25565);
        var first = new TransportTarget(
                AcceleratedTransport.QUIC,
                new InetSocketAddress("1.2.3.4", 1111)
        );
        var second = new TransportTarget(
                AcceleratedTransport.QUIC,
                new InetSocketAddress("5.6.7.8", 2222)
        );
        cache.record(
                key,
                first
        );
        cache.record(
                key,
                second
        );
        assertEquals(
                2222,
                cache.lookup(key, TransportMode.QUIC).orElseThrow().address().getPort(),
                "Later writes should overwrite earlier writes"
        );
        cache.invalidate(key);
        assertTrue(cache.lookup(key, TransportMode.QUIC).isEmpty());
    }

    @Test
    void successCacheBounded() {
        var cache = new SuccessfulEndpointCache();
        var target = new TransportTarget(
                AcceleratedTransport.QUIC,
                new InetSocketAddress("1.2.3.4", 9999)
        );
        IntStream.rangeClosed(1, 1000)
                .forEachOrdered(i -> cache.record(
                        addr(i),
                        target
                ));
        assertTrue(cache.lookup(addr(1000), TransportMode.QUIC).isPresent());
    }

    @Test
    void stateStoreTransitions() {
        var store = new ConnectionStateStore();
        assertEquals(
                ConnectionSnapshot.Phase.IDLE,
                store.snapshot().phase()
        );
        store.connecting(TransportMode.QUIC);
        assertEquals(
                ConnectionSnapshot.Phase.CONNECTING,
                store.snapshot().phase()
        );
        assertEquals(
                TransportMode.QUIC,
                store.snapshot().requestedMode()
        );
        assertNull(store.snapshot().transportLine());
        store.connected(
                TransportMode.QUIC,
                "QUIC 1.2.3.4:25565"
        );
        assertEquals(
                ConnectionSnapshot.Phase.CONNECTED,
                store.snapshot().phase()
        );
        assertEquals(
                "QUIC 1.2.3.4:25565",
                store.transportLine()
        );
        store.fallingBack();
        assertEquals(
                ConnectionSnapshot.Phase.FALLING_BACK,
                store.snapshot().phase()
        );
        store.idle();
        assertEquals(
                ConnectionSnapshot.Phase.IDLE,
                store.snapshot().phase()
        );
    }

}
