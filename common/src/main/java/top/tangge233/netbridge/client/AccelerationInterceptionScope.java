package top.tangge233.netbridge.client;

import java.util.function.Supplier;

/**
 * Dynamic-scope flags guarding the Minecraft {@code net.minecraft.network.Connection.connect}
 * interception performed by the NetBridge mixin layer.
 *
 * <p>The interception entry point runs on the caller's stack. A guard is needed so a vanilla
 * connect is never recursively re-intercepted:</p>
 *
 * <ul>
 *   <li><em>vanilla bypass</em> — bound at the exact call site that deliberately opens a plain
 *       TCP {@code Connection.connect} during fallback. The binding is created where the
 *       asynchronous fallback eventually calls vanilla connect, so it is valid even when that
 *       call happens later on a Netty event-loop thread.</li>
 *   <li><em>accelerated connect in progress</em> — bound around the whole synchronous
 *       NetBridge-managed connect attempt, so any nested vanilla connect on the same call stack
 *       (outside a bypass) is not intercepted a second time.</li>
 * </ul>
 *
 * <p>Both flags use {@link ScopedValue}, so bindings are lexical/dynamic, automatically unwind on
 * exceptions, and nest naturally. There is no manual {@code set}/{@code remove} lifecycle and no
 * state leaks across asynchronous scheduling (each vanilla fallback binds at its own call site).</p>
 */
public final class AccelerationInterceptionScope {

    private static final ScopedValue<Boolean> VANILLA_CONNECT_BYPASS = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> ACCELERATED_CONNECT_IN_PROGRESS = ScopedValue.newInstance();

    private AccelerationInterceptionScope() {
    }

    /**
     * True when the current stack is opening a vanilla TCP {@code Connection.connect} fallback.
     */
    public static boolean isVanillaConnectBypass() {
        return VANILLA_CONNECT_BYPASS.isBound();
    }

    /**
     * True when the current stack is already inside a NetBridge-managed accelerated connect.
     */
    public static boolean isAcceleratedConnectInProgress() {
        return ACCELERATED_CONNECT_IN_PROGRESS.isBound();
    }

    /**
     * Runs {@code operation} with the vanilla-connect bypass bound. Nested bypass calls remain
     * bypassed; the binding unwinds even when {@code operation} throws.
     */
    public static <T> T callWithVanillaConnectBypass(Supplier<T> operation) {
        return ScopedValue.where(
                VANILLA_CONNECT_BYPASS,
                Boolean.TRUE
        ).call(operation::get);
    }

    /**
     * Runs {@code operation} with the accelerated-connect-in-progress flag bound. Nested calls
     * remain flagged; the binding unwinds even when {@code operation} throws.
     */
    public static <T> T callWithAcceleratedConnectInProgress(Supplier<T> operation) {
        return ScopedValue.where(
                ACCELERATED_CONNECT_IN_PROGRESS,
                Boolean.TRUE
        ).call(operation::get);
    }

}
