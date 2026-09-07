package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.jspecify.annotations.Nullable;

public final class NativeResourceException extends RuntimeException {

    private final NativeResourceError error;

    public NativeResourceException(
            NativeResourceError error,
            String message
    ) {
        super(message);
        this.error = error;
    }

    public NativeResourceException(
            NativeResourceError error,
            String message,
            @Nullable Throwable cause
    ) {
        super(message, cause);
        this.error = error;
    }

    public NativeResourceError error() {
        return error;
    }

    public String code() {
        return error.name();
    }

}
