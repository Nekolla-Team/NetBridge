package top.tangge233.netbridge.nativebridge;

import org.jspecify.annotations.Nullable;

/**
 * Snapshot of native backend availability.
 */
public record NativeBackendAvailability(
        NativeBackendState state,
        @Nullable String reason
) {

    public static final NativeBackendAvailability UNAVAILABLE =
            new NativeBackendAvailability(NativeBackendState.UNAVAILABLE, null);

    public static NativeBackendAvailability unavailable(String reason) {
        return new NativeBackendAvailability(NativeBackendState.UNAVAILABLE, reason);
    }

    public boolean available() {
        return state == NativeBackendState.AVAILABLE;
    }

}
