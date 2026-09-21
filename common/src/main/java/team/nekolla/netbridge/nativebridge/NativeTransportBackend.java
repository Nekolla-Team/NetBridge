package team.nekolla.netbridge.nativebridge;

public interface NativeTransportBackend extends AutoCloseable {

    NativeBackendAvailability availability();

    NativeConnection connect(NativeConnectRequest request) throws NativeException;

    NativeServer startServer(NativeServerRequest request) throws NativeException;

    @Override
    void close();

}
