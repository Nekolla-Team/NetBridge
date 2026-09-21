package team.nekolla.netbridge.client;

import team.nekolla.netbridge.transport.TransportMode;

import org.jspecify.annotations.Nullable;

public final class ConnectionStateStore {

    private volatile ConnectionSnapshot current = ConnectionSnapshot.idle();

    public void connecting(TransportMode mode) {
        current = new ConnectionSnapshot(
                ConnectionSnapshot.Phase.CONNECTING,
                mode,
                null
        );
    }

    public void connected(TransportMode mode, String transportLine) {
        current = new ConnectionSnapshot(
                ConnectionSnapshot.Phase.CONNECTED,
                mode,
                transportLine
        );
    }

    public void fallingBack() {
        current = new ConnectionSnapshot(
                ConnectionSnapshot.Phase.FALLING_BACK,
                null,
                null
        );
    }

    public void idle() {
        current = ConnectionSnapshot.idle();
    }

    public ConnectionSnapshot snapshot() {
        return current;
    }

    public @Nullable String transportLine() {
        return current.transportLine();
    }

}
