package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.nativebridge.NativeEvent;
import top.tangge233.netbridge.nativebridge.NativeEventListener;

import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

public final class NativeEventDispatcher {

    private final CopyOnWriteArrayList<NativeEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(NativeEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(NativeEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Target method invoked by the FFM upcall stub.
     *
     * <p>Raw callback parameters are immediately decoded into a typed event via
     * {@link FfmAbiCodec}; invalid or unknown shapes are rejected at the boundary (logged and
     * discarded) and are never propagated as partially valid events. All {@link Throwable}s are
     * caught internally, ensuring that no exception ever crosses the FFM boundary.
     */
    public void onNativeEvent(
            int eventKind,
            long objectId,
            long arg0,
            long arg1
    ) {
        try {
            var event = FfmAbiCodec.decodeEvent(
                    eventKind,
                    objectId,
                    arg0,
                    arg1
            );
            listeners.forEach(listener -> dispatch(listener, event));
        } catch (Throwable t) {
            NetBridge.LOGGER.error(
                    "Dropping malformed native event kind={} objectId={} arg0={} arg1={}: {}",
                    eventKind,
                    objectId,
                    arg0,
                    arg1,
                    safeMessage(t)
            );
        }
    }

    private void dispatch(
            NativeEventListener listener,
            NativeEvent event
    ) {
        try {
            listener.onEvent(event);
        } catch (Throwable t) {
            NetBridge.LOGGER.error(
                    "Error in native event listener: {}",
                    safeMessage(t),
                    t
            );
        }
    }

    private static @Nullable String safeMessage(Throwable t) {
        return t.getMessage();
    }

}
