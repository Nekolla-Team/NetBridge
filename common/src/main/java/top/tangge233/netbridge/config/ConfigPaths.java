package top.tangge233.netbridge.config;

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * Resolves the NetBridge configuration file path.
 *
 * @param directory Configuration root directory (e.g. .minecraft/config/net-bridge)
 */
public record ConfigPaths(
        Path directory
) {

    public ConfigPaths {
        requireNonNull(directory, "directory");
    }

    public Path clientFile() {
        return directory.resolve("client.toml");
    }

    public Path serverFile() {
        return directory.resolve("server.toml");
    }

}
