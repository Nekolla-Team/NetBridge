package top.tangge233.netbridge.client;

import top.tangge233.netbridge.nativebridge.NativeConnectException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

public record NativeRetryPolicy(
        int maxAttempts,
        long firstAttemptTimeoutMillis,
        long subsequentAttemptTimeoutMillis
) {

    public static final int DEFAULT_MAX_ATTEMPTS = 2;
    public static final long FIRST_ATTEMPT_TIMEOUT_MILLIS = 10_000L;
    public static final long SUBSEQUENT_ATTEMPT_TIMEOUT_MILLIS = 20_000L;

    public static NativeRetryPolicy defaults() {
        return new NativeRetryPolicy(
                DEFAULT_MAX_ATTEMPTS,
                FIRST_ATTEMPT_TIMEOUT_MILLIS,
                SUBSEQUENT_ATTEMPT_TIMEOUT_MILLIS
        );
    }

    public long timeoutMillisForAttempt(int attempt) {
        return attempt <= 1
                ? firstAttemptTimeoutMillis
                : subsequentAttemptTimeoutMillis;
    }

    public long retryBackoffMillisForAttempt(int attempt) {
        return attempt <= 1
                ? 0L
                : 100L;
    }

    public boolean isRetryable(@Nullable Throwable cause) {
        if (cause == null) {
            return true;
        }

        if (cause instanceof NativeConnectException nce) {
            return switch (nce.reason()) {
                case DNS, SETUP, CANCELLED -> false;
                case REFUSED, TIMEOUT, PROTOCOL, INTERNAL, GENERIC -> true;
            };
        }

        return (cause instanceof IOException || cause instanceof TimeoutException);
    }

}
