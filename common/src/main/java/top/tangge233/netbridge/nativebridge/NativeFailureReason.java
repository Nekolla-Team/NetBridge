package top.tangge233.netbridge.nativebridge;

public enum NativeFailureReason {

    GENERIC("generic failure"),
    DNS("dns resolution failed"),
    SETUP("socket setup/bind failed"),
    REFUSED("connection refused/unreachable"),
    TIMEOUT("connection timed out"),
    PROTOCOL("protocol/data-plane error"),
    CANCELLED("connection cancelled"),
    INTERNAL("internal error");

    private final String description;

    NativeFailureReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

}
