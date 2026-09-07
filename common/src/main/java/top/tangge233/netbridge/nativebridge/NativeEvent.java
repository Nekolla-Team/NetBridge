package top.tangge233.netbridge.nativebridge;

/**
 * Typed native event. Raw C callback parameters are decoded into semantic events at the FFM
 * internal upcall layer, so the application layer does not need to understand the meaning of
 * {@code arg0}/{@code arg1}.
 */
public sealed interface NativeEvent
        permits NativeEvent.ConnectionStateChanged,
        NativeEvent.DataAvailable,
        NativeEvent.Writable,
        NativeEvent.Accepted,
        NativeEvent.ServerStateChanged {

    record ConnectionStateChanged(
            long connectionId,
            NativeConnectionState state,
            NativeFailureReason reason
    ) implements NativeEvent {

    }

    record DataAvailable(
            long connectionId
    ) implements NativeEvent {

    }

    record Writable(
            long connectionId
    ) implements NativeEvent {

    }

    record Accepted(
            long serverId,
            long connectionId
    ) implements NativeEvent {

    }

    record ServerStateChanged(
            long serverId,
            NativeServerState state
    ) implements NativeEvent {

    }

}
