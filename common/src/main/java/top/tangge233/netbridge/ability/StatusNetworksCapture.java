package top.tangge233.netbridge.ability;

import org.jspecify.annotations.Nullable;

/**
 * Thread-local staging slot for the networks capability captured while decoding a status packet.
 *
 * <p>ClientboundStatusResponsePacket is a record, so a mixin cannot attach an instance field. In
 * the decoding constructor, readJsonWithCodec runs before this(), which also requires the handler
 * to be static. The decoder therefore stores the value here, isolated by decode thread, and the
 * consumer takes it on the same thread after the packet object has been created.
 *
 * <p>A ThreadLocal is used instead of ConcurrentHashMap&lt;Thread,...&gt; because reads and writes
 * on the same thread need no concurrent map, and the entry disappears with the thread. A CHM would
 * keep dead Thread keys strongly referenced.
 */
public final class StatusNetworksCapture {

    private static final ThreadLocal<@Nullable NetworksAbility> LAST = new ThreadLocal<>();

    private StatusNetworksCapture() {
    }

    /**
     * Records the networks value parsed by the current decode thread. The slot must always be
     * written, including an empty value: it represents the most recent decode. Skipping an empty
     * result would let a stale value from the previous packet be incorrectly associated with a
     * later server packet that advertises no accelerated transport.
     */
    public static void capture(@Nullable NetworksAbility networks) {
        LAST.set(
                networks == null
                        ? NetworksAbility.empty()
                        : networks
        );
    }

    /**
     * Takes and clears the current thread's staged value while handling the packet; returns empty
     * if none exists.
     */
    public static NetworksAbility take() {
        var n = LAST.get();
        LAST.remove();
        return n == null
                ? NetworksAbility.empty()
                : n;
    }

}
