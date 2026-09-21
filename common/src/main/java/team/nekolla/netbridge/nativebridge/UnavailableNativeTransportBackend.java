package team.nekolla.netbridge.nativebridge;

public final class UnavailableNativeTransportBackend implements NativeTransportBackend {

    private final String reason;

    public UnavailableNativeTransportBackend(String reason) {
        this.reason = reason;
    }

    @Override
    public NativeBackendAvailability availability() {
        return NativeBackendAvailability.unavailable(reason);
    }

    @Override
    public NativeConnection connect(NativeConnectRequest request) {
        throw new NativeException("native backend unavailable: " + reason);
    }

    @Override
    public NativeServer startServer(NativeServerRequest request) {
        throw new NativeException("native backend unavailable: " + reason);
    }

    @Override
    public void close() {
    }

}
