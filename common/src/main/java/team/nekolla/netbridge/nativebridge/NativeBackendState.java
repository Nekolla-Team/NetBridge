package team.nekolla.netbridge.nativebridge;

public enum NativeBackendState {

    NEW,
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
    INCOMPATIBLE,
    CLOSING,
    CLOSE_FAILED,
    CLOSED

}
