package top.tangge233.netbridge.nativebridge;

import java.util.Arrays;

public enum NativeFailureReason {

    GENERIC(0, "generic failure"),
    DNS(1, "dns resolution failed"),
    SETUP(2, "socket setup/bind failed"),
    REFUSED(3, "connection refused/unreachable"),
    TIMEOUT(4, "connection timed out"),
    PROTOCOL(5, "protocol/data-plane error"),
    CANCELLED(6, "connection cancelled"),
    INTERNAL(7, "internal error");

    private final int code;
    private final String description;

    NativeFailureReason(
            int code,
            String description
    ) {
        this.code = code;
        this.description = description;
    }

    public static NativeFailureReason fromCode(long code) {
        return Arrays.stream(values())
                .filter(reason -> reason.code == (int) code)
                .findFirst()
                .orElse(GENERIC);
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

}
