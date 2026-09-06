package top.tangge233.netbridge.server;

import top.tangge233.netbridge.nativebridge.NativeConnection;

public interface NativeConnectionAdopter {

    default void adopt(NativeConnection connection) {
        adopt(connection, 0L);
    }

    void adopt(NativeConnection connection, long sessionGeneration);

}
