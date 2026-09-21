package team.nekolla.netbridge.nativebridge;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public interface NativeConnection extends AutoCloseable {

    long id();

    NativeTransportKind transport();

    NativeConnectionState state();

    InetSocketAddress remoteAddress() throws NativeException;

    NativeIoResult write(ByteBuffer source) throws NativeException;

    NativeIoResult read(ByteBuffer target) throws NativeException;

    void setListener(NativeConnectionListener listener);

    @Override
    void close();

}
