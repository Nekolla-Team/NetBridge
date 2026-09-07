package top.tangge233.netbridge.client;

import top.tangge233.netbridge.nativebridge.NativeConnectException;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Retry policy for native accelerated connection attempts.
 *
 * <p>Timeouts are expressed as {@link Duration}; conversion to primitive milliseconds happens only
 * at the scheduling leaf in {@link ConnectionExecutor}.</p>
 */
public record NativeRetryPolicy(
        int maxAttempts,
        Duration firstAttemptTimeout,
        Duration subsequentAttemptTimeout
) {

    public static final int DEFAULT_MAX_ATTEMPTS = 2;
    public static final Duration FIRST_ATTEMPT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration SUBSEQUENT_ATTEMPT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(100);

    public NativeRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        requireNonNull(firstAttemptTimeout, "firstAttemptTimeout");
        requireNonNull(subsequentAttemptTimeout, "subsequentAttemptTimeout");
        requirePositive(firstAttemptTimeout, "firstAttemptTimeout");
        requirePositive(subsequentAttemptTimeout, "subsequentAttemptTimeout");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive: " + duration);
        }
    }

    public static NativeRetryPolicy defaults() {
        return new NativeRetryPolicy(
                DEFAULT_MAX_ATTEMPTS,
                FIRST_ATTEMPT_TIMEOUT,
                SUBSEQUENT_ATTEMPT_TIMEOUT
        );
    }

    public Duration timeoutForAttempt(int attempt) {
        return attempt <= 1
                ? firstAttemptTimeout
                : subsequentAttemptTimeout;
    }

    public Duration retryBackoffForAttempt(int attempt) {
        return attempt <= 1
                ? Duration.ZERO
                : RETRY_BACKOFF;
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
