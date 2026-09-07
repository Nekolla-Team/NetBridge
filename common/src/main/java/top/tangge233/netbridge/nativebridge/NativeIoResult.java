package top.tangge233.netbridge.nativebridge;

import static java.util.Objects.requireNonNull;

/**
 * Result of a native read/write operation.
 *
 * <p>Invariants: {@code bytes >= 0}; {@link NativeIoStatus#WOULD_BLOCK} and
 * {@link NativeIoStatus#CLOSED} always report 0 bytes. {@link NativeIoStatus#PROGRESSED} may report
 * 0 bytes (e.g. when a write is issued with an empty buffer and the backend reports it as
 * "processed"). Callers should check the combination of {@link #progressed()} and
 * {@code bytes() > 0}, rather than assuming that {@code progressed} must always be positive.
 */
public record NativeIoResult(
        NativeIoStatus status,
        int bytes
) {

    public static final NativeIoResult WOULD_BLOCK = new NativeIoResult(
            NativeIoStatus.WOULD_BLOCK,
            0
    );
    public static final NativeIoResult CLOSED = new NativeIoResult(
            NativeIoStatus.CLOSED,
            0
    );

    public NativeIoResult {
        requireNonNull(status, "status");
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be >= 0, was " + bytes);
        }
        if (status == NativeIoStatus.WOULD_BLOCK
                || status == NativeIoStatus.CLOSED
        ) {
            if (bytes != 0) {
                throw new IllegalArgumentException(
                        status + " result must carry 0 bytes, was " + bytes
                );
            }
        }
    }

    public static NativeIoResult progressed(int bytes) {
        return new NativeIoResult(NativeIoStatus.PROGRESSED, bytes);
    }

    public boolean progressed() {
        return status == NativeIoStatus.PROGRESSED;
    }

    public boolean wouldBlock() {
        return status == NativeIoStatus.WOULD_BLOCK;
    }

    public boolean closed() {
        return status == NativeIoStatus.CLOSED;
    }

}
