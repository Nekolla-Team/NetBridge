package team.nekolla.netbridge.nativebridge;

public interface NativeServerListener {

    default void onAccepted(NativeConnection connection) {
    }

    default void onStateChanged(NativeServerState state) {
    }

}
