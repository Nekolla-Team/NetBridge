package top.tangge233.netbridge.nativebridge;

import java.net.ConnectException;

public class NativeConnectException extends ConnectException {

    private final NativeFailureReason reason;

    public NativeConnectException(
            NativeFailureReason reason,
            String message
    ) {
        super(message);
        this.reason = reason;
    }

    public NativeFailureReason reason() {
        return reason;
    }

}
