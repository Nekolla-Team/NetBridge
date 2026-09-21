package team.nekolla.netbridge.server;

import team.nekolla.netbridge.nativebridge.NativeConnection;

public interface NativeConnectionAdopter {

    default void adopt(NativeConnection connection) {
        adopt(connection, 0L);
    }

    void adopt(NativeConnection connection, long sessionGeneration);

}
