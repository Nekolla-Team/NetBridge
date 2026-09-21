package team.nekolla.netbridge.client;

import team.nekolla.netbridge.transport.TransportMode;

import org.jspecify.annotations.Nullable;

public record ConnectionSnapshot(
        Phase phase,
        @Nullable TransportMode requestedMode,
        @Nullable String transportLine
) {

    public static ConnectionSnapshot idle() {
        return new ConnectionSnapshot(
                Phase.IDLE,
                null,
                null
        );
    }

    public enum Phase {
        IDLE,
        CONNECTING,
        FALLING_BACK,
        CONNECTED
    }

}
