package top.tangge233.netbridge.config.server;

import com.electronwill.nightconfig.core.NullObject;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.transport.KcpProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ServerConfigStore(
        Path file
) {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConfigStore.class);
    private static final String TEMPLATE_RESOURCE = "/net-bridge/server-default.toml";
    private static final Object NO_VALUE = NullObject.NULL_OBJECT;

    private static List<String> append(
            List<String> prefix,
            String key
    ) {
        return List.of(prefix.getFirst(), key);
    }

    public ServerSettings load() {
        try {
            var parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ensureTemplateExists();
            try (var config = CommentedFileConfig.builder(file).build()) {
                config.load();
                return new ServerSettings(
                        readSection(config, "quic", ServerTransportSettings.defaultQuic()),
                        readSection(config, "kcp", ServerTransportSettings.defaultKcp())
                );
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load server config {}: {}", file, e.toString());
            return ServerSettings.defaults();
        }
    }

    public void ensureTemplateExists() {
        if (Files.exists(file)) {
            return;
        }

        try (var in = ServerConfigStore.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                LOGGER.warn(
                        "Built-in server config template {} not found on mod classpath",
                        TEMPLATE_RESOURCE
                );
                return;
            }
            Files.copy(in, file);
            LOGGER.info("Generated default server config {}", file);
        } catch (Exception e) {
            LOGGER.warn("Failed to write default server config {}: {}", file, e.toString());
        }
    }

    private ServerTransportSettings readSection(
            CommentedFileConfig config,
            String section,
            ServerTransportSettings defaults
    ) {
        var prefix = List.of(section);

        var enabled = readBoolean(
                config,
                prefix,
                "enable",
                defaults.enabled(),
                section
        );
        var bindHost = readString(
                config,
                prefix,
                "bind",
                section
        );
        var advertisedHost = readString(
                config,
                prefix,
                "host",
                section
        );
        var port = readInt(
                config,
                prefix,
                "port",
                defaults.port(),
                section
        );
        var maxConnections = readAtLeast(
                config,
                prefix,
                "max_connection",
                1,
                defaults.maxConnections(),
                section
        );
        var profile = readKcpProfile(
                config,
                prefix,
                defaults.kcpProfile(),
                section
        );

        return new ServerTransportSettings(
                enabled,
                bindHost,
                advertisedHost,
                port,
                maxConnections,
                profile
        );
    }

    private boolean readBoolean(
            UnmodifiableConfig config,
            List<String> prefix,
            String key,
            boolean defaultValue,
            String section
    ) {
        var raw = rawAt(config, prefix, key);
        return switch (raw) {
            case null -> defaultValue;
            case Boolean value -> value;
            default -> {
                LOGGER.warn(
                        "Invalid type for {}.{} in {}: expected boolean; using default",
                        section,
                        key,
                        file
                );
                yield defaultValue;
            }
        };
    }

    private @Nullable String readString(
            UnmodifiableConfig config,
            List<String> prefix,
            String key,
            String section
    ) {
        var raw = rawAt(config, prefix, key);
        return switch (raw) {
            case null -> null;
            case String value when !value.isBlank() -> value;
            case String _ -> null;
            default -> {
                LOGGER.warn(
                        "Invalid type for {}.{} in {}: expected string; using default",
                        section,
                        key,
                        file
                );
                yield null;
            }
        };
    }

    private int readInt(
            UnmodifiableConfig config,
            List<String> prefix,
            String key,
            int defaultValue,
            String section
    ) {
        var raw = rawAt(config, prefix, key);
        return switch (raw) {
            case null -> defaultValue;
            case Number number -> {
                var value = number.intValue();
                if (value >= -1 && value <= 65535) {
                    yield value;
                }

                LOGGER.warn(
                        "{}.{} = {} out of range (-1..=65535) in {}; using default",
                        section,
                        key,
                        value,
                        file
                );
                yield defaultValue;
            }
            default -> {
                LOGGER.warn(
                        "Invalid type for {}.{} in {}: expected integer; using default",
                        section,
                        key,
                        file
                );
                yield defaultValue;
            }
        };
    }

    private int readAtLeast(
            UnmodifiableConfig config,
            List<String> prefix,
            String key,
            int minimum,
            int defaultValue,
            String section
    ) {
        var raw = rawAt(config, prefix, key);
        return switch (raw) {
            case null -> defaultValue;
            case Number number -> {
                var value = number.intValue();
                if (value >= minimum) {
                    yield value;
                }
                LOGGER.warn(
                        "{}.{} = {} below minimum {} in {}; using default",
                        section,
                        key,
                        value,
                        minimum,
                        file
                );
                yield defaultValue;
            }
            default -> {
                LOGGER.warn(
                        "Invalid type for {}.{} in {}: expected integer; using default",
                        section,
                        key,
                        file
                );
                yield defaultValue;
            }
        };
    }

    private @Nullable KcpProfile readKcpProfile(
            UnmodifiableConfig config,
            List<String> prefix,
            @Nullable KcpProfile defaultValue,
            String section
    ) {
        var raw = rawAt(config, prefix, "profile");
        return switch (raw) {
            case null -> defaultValue;
            case String text -> {
                var parsed = KcpProfile.parse(text);
                if (parsed == null) {
                    LOGGER.warn(
                            "Unknown kcp profile '{}' in {}.profile; using default",
                            text,
                            section
                    );
                    yield defaultValue;
                }
                yield parsed;
            }
            default -> {
                LOGGER.warn(
                        "Invalid type for {}.profile in {}: expected string; using default",
                        section,
                        file
                );
                yield defaultValue;
            }
        };
    }

    private @Nullable Object rawAt(
            UnmodifiableConfig config,
            List<String> prefix,
            String key
    ) {
        Object value;
        try {
            value = config.getRaw(append(prefix, key));
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to read {}.{} in {}: {}",
                    prefix.isEmpty()
                            ? ""
                            : prefix.getFirst(),
                    key,
                    file,
                    e.toString()
            );
            return null;
        }
        return value == null || NO_VALUE.equals(value)
                ? null
                : value;
    }

}
