package top.tangge233.netbridge.nativebridge;

public interface NativeConnectionListener {

    default void onStateChanged(
            NativeConnectionState state,
            NativeFailureReason reason
    ) {
        onStateChanged(state);
    }

    default void onStateChanged(NativeConnectionState state) {
    }

    default void onDataAvailable() {
    }

    default void onWritable() {
    }

}
