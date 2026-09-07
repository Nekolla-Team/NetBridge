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
 */
public record NetBridgeProperties(
        @Nullable TransportMode transportOverride,
        @Nullable Integer quicPort,
        @Nullable Path nativeLibraryPath,
        @Nullable Path nativeCacheDirectory
) {

    public static final String KEY_TRANSPORT = "netbridge.transport";
    public static final String KEY_QUIC_PORT = "netbridge.quicPort";
    public static final String KEY_NATIVE_PATH = "netbridge.native.path";
    public static final String KEY_NATIVE_CACHE_DIR = "netbridge.native.cache.dir";
    private static final Logger LOGGER = LoggerFactory.getLogger(NetBridgeProperties.class);

    public static NetBridgeProperties defaults() {
        return new NetBridgeProperties(
                null,
                null,
                null,
                null
        );
    }

    public static NetBridgeProperties load() {
        return new NetBridgeProperties(
                parseTransport(System.getProperty(KEY_TRANSPORT)),
                parseQuicPort(System.getProperty(KEY_QUIC_PORT)),
                parsePath(System.getProperty(KEY_NATIVE_PATH), KEY_NATIVE_PATH),
                parsePath(System.getProperty(KEY_NATIVE_CACHE_DIR), KEY_NATIVE_CACHE_DIR)
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

}
