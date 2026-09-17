package top.tangge233.netbridge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.transport.TransportMode;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Typed facade for runtime system properties (-Dnetbridge.*).
 *
 * <p>Centralizes key definitions, parsing, and validation strategies; a single {@link #load()}
 * call produces an immutable snapshot, which is injected into services/resolvers by the composition
 * root, avoiding scattered {@code System.getProperty} calls throughout the codebase.
 *
 * @param transportOverride    netbridge.transport (client acceleration mode override, null
 *                             indicates not configured)
 * @param quicPort             netbridge.quicPort (server-side QUIC listening port override, null
 *                             indicates not configured)
 * @param nativeLibraryPath    netbridge.native.path (explicit native library path override, null
 *                             indicates the packaged resource is used)
 * @param nativeCacheDirectory netbridge.native.cache.dir (native library cache directory override,
 *                             null indicates the default directory is used)
 * @param sharedIoMode         netbridge.native.sharedIo (auto/on/off direct data-plane selection)
 * @param sharedIoTxCapacity   netbridge.native.sharedIo.txCapacity (0 selects the native default)
 * @param sharedIoRxCapacity   netbridge.native.sharedIo.rxCapacity (0 selects the native default)
 */
public record NetBridgeProperties(
        @Nullable TransportMode transportOverride,
        @Nullable Integer quicPort,
        @Nullable Path nativeLibraryPath,
        @Nullable Path nativeCacheDirectory,
        SharedIoMode sharedIoMode,
        int sharedIoTxCapacity,
        int sharedIoRxCapacity
) {

    public static final String KEY_TRANSPORT = "netbridge.transport";
    public static final String KEY_QUIC_PORT = "netbridge.quicPort";
    public static final String KEY_NATIVE_PATH = "netbridge.native.path";
    public static final String KEY_NATIVE_CACHE_DIR = "netbridge.native.cache.dir";
    public static final String KEY_SHARED_IO = "netbridge.native.sharedIo";
    public static final String KEY_SHARED_IO_TX_CAPACITY = "netbridge.native.sharedIo.txCapacity";
    public static final String KEY_SHARED_IO_RX_CAPACITY = "netbridge.native.sharedIo.rxCapacity";

    /** Minimum accepted shared-ring capacity (matches the native ring constraint). */
    public static final int MIN_SHARED_IO_CAPACITY = 64 * 1024;
    /** Maximum accepted configured shared-ring capacity. */
    public static final int MAX_SHARED_IO_CAPACITY = 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(NetBridgeProperties.class);

    public NetBridgeProperties {
        if (sharedIoMode == null) {
            throw new IllegalArgumentException("sharedIoMode must not be null");
        }
        validateCapacity(sharedIoTxCapacity, KEY_SHARED_IO_TX_CAPACITY);
        validateCapacity(sharedIoRxCapacity, KEY_SHARED_IO_RX_CAPACITY);
    }

    /**
     * Performance configuration fails fast: a misconfigured capacity is far easier to diagnose at
     * startup than as an intermittent native context-creation failure.
     */
    private static void validateCapacity(int capacity, String key) {
        if (capacity == 0) {
            return;
        }
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "Invalid %s '%d': must be a power of two".formatted(key, capacity)
            );
        }
        if (capacity < MIN_SHARED_IO_CAPACITY || capacity > MAX_SHARED_IO_CAPACITY) {
            throw new IllegalArgumentException(
                    "Invalid %s '%d': must be within [%d, %d]".formatted(
                            key,
                            capacity,
                            MIN_SHARED_IO_CAPACITY,
                            MAX_SHARED_IO_CAPACITY
                    )
            );
        }
    }

    public static NetBridgeProperties defaults() {
        return new NetBridgeProperties(
                null,
                null,
                null,
                null,
                SharedIoMode.DEFAULT,
                0,
                0
        );
    }

    public static NetBridgeProperties load() {
        return new NetBridgeProperties(
                parseTransport(System.getProperty(KEY_TRANSPORT)),
                parseQuicPort(System.getProperty(KEY_QUIC_PORT)),
                parsePath(System.getProperty(KEY_NATIVE_PATH), KEY_NATIVE_PATH),
                parsePath(System.getProperty(KEY_NATIVE_CACHE_DIR), KEY_NATIVE_CACHE_DIR),
                parseSharedIoMode(System.getProperty(KEY_SHARED_IO)),
                parseSharedIoCapacity(
                        System.getProperty(KEY_SHARED_IO_TX_CAPACITY),
                        KEY_SHARED_IO_TX_CAPACITY
                ),
                parseSharedIoCapacity(
                        System.getProperty(KEY_SHARED_IO_RX_CAPACITY),
                        KEY_SHARED_IO_RX_CAPACITY
                )
        );
    }

    private static @Nullable TransportMode parseTransport(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        var parsed = TransportMode.parse(value);
        if (parsed == null) {
            LOGGER.warn(
                    "Invalid {} '{}': expected tcp/quic/kcp; ignoring",
                    KEY_TRANSPORT,
                    value
            );
            return null;
        }
        return parsed;
    }

    private static @Nullable Integer parseQuicPort(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn(
                    "Invalid {} '{}': not a number; ignoring",
                    KEY_QUIC_PORT,
                    value
            );
            return null;
        }
    }

    private static @Nullable Path parsePath(
            @Nullable String value,
            String key
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Path.of(value.trim());
        } catch (InvalidPathException e) {
            LOGGER.warn(
                    "Invalid {} '{}': not a path; ignoring",
                    key,
                    value
            );
            return null;
        }
    }

    private static SharedIoMode parseSharedIoMode(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return SharedIoMode.DEFAULT;
        }

        var parsed = SharedIoMode.parse(value);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "Invalid %s '%s': expected auto/on/off".formatted(KEY_SHARED_IO, value)
            );
        }
        return parsed;
    }

    private static int parseSharedIoCapacity(@Nullable String value, String key) {
        if (value == null || value.isBlank()) {
            return 0;
        }

        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid %s '%s': not a number".formatted(key, value)
            );
        }
        validateCapacity(parsed, key);
        return parsed;
    }

}
