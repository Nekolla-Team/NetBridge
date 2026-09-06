package top.tangge233.netbridge.nativebridge;

import org.jspecify.annotations.Nullable;

public class NativeException extends RuntimeException {

    private final int statusCode;

    public NativeException(String message) {
        this(message, -1, null);
    }

    public NativeException(
            String message,
            int statusCode,
            @Nullable Throwable cause
    ) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public NativeException(
            String message,
            int statusCode
    ) {
        this(message, statusCode, null);
    }

    public NativeException(
            String message,
            @Nullable Throwable cause
    ) {
        this(message, -1, cause);
    }

    public int statusCode() {
        return statusCode;
    }

}
