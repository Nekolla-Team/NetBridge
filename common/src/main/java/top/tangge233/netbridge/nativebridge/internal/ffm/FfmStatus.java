package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.NativeException;

public final class FfmStatus {

    public static final int NB_OK = 0;
    public static final int NB_WOULD_BLOCK = 1;
    public static final int NB_NOT_FOUND = 2;
    public static final int NB_CLOSED = 3;
    public static final int NB_INVALID_ARGUMENT = 4;
    public static final int NB_INVALID_STATE = 5;
    public static final int NB_ABI_MISMATCH = 6;
    public static final int NB_NATIVE_UNAVAILABLE = 7;
    public static final int NB_BIND_FAILED = 8;
    public static final int NB_DNS_FAILED = 9;
    public static final int NB_CONNECT_FAILED = 10;
    public static final int NB_SHUTTING_DOWN = 11;
    public static final int NB_TIMEOUT = 12;
    public static final int NB_INTERNAL = 13;
    public static final int NB_PANIC = 14;
    public static final int NB_UNSUPPORTED = 15;

    private FfmStatus() {
    }

    public static void checkStatus(int status, String operation) {
        if (status == NB_OK || status == NB_WOULD_BLOCK) {
            return;
        }

        throw new NativeException(
                "Native operation '%s' failed with status %d (%s)".formatted(
                        operation,
                        status,
                        describe(status)
                ),
                status
        );
    }

    public static String describe(int status) {
        return switch (status) {
            case NB_OK -> "NB_OK";
            case NB_WOULD_BLOCK -> "NB_WOULD_BLOCK";
            case NB_NOT_FOUND -> "NB_NOT_FOUND";
            case NB_CLOSED -> "NB_CLOSED";
            case NB_INVALID_ARGUMENT -> "NB_INVALID_ARGUMENT";
            case NB_INVALID_STATE -> "NB_INVALID_STATE";
            case NB_ABI_MISMATCH -> "NB_ABI_MISMATCH";
            case NB_NATIVE_UNAVAILABLE -> "NB_NATIVE_UNAVAILABLE";
            case NB_BIND_FAILED -> "NB_BIND_FAILED";
            case NB_DNS_FAILED -> "NB_DNS_FAILED";
            case NB_CONNECT_FAILED -> "NB_CONNECT_FAILED";
            case NB_SHUTTING_DOWN -> "NB_SHUTTING_DOWN";
            case NB_TIMEOUT -> "NB_TIMEOUT";
            case NB_INTERNAL -> "NB_INTERNAL";
            case NB_PANIC -> "NB_PANIC";
            case NB_UNSUPPORTED -> "NB_UNSUPPORTED";
            default -> "UNKNOWN(" + status + ")";
        };
    }

}
