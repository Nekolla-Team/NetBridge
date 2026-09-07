package top.tangge233.netbridge.config.client;

import com.electronwill.nightconfig.core.NullObject;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ClientConfigStore(
        Path file
) {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConfigStore.class);
    private static final Object NO_VALUE = NullObject.NULL_OBJECT;

    public ClientSettings load() {
        if (!Files.exists(file)) {
            return ClientSettings.defaults();
        }

        try (var config = CommentedFileConfig.builder(file).build()) {
            config.load();
            var mode = readMode(config);
            var profile = readKcpProfile(config);
            return new ClientSettings(mode, profile);
        } catch (Exception e) {
            LOGGER.warn("Failed to read client config from {}; using defaults", file, e);
            return ClientSettings.defaults();
        }
    }

    private TransportMode readMode(UnmodifiableConfig config) {
        var raw = rawAt(config, "mode");
        if (raw == null) {
            return TransportMode.TCP;
        }

        if (raw instanceof String text) {
            var parsed = TransportMode.parse(text);
            if (parsed == null) {
                LOGGER.warn("Unknown transport mode '{}' in {}: using default", text, file);
                return TransportMode.TCP;
            }
            return parsed;
        }

        LOGGER.warn("Invalid transport mode type in {}: using default", file);
        return TransportMode.TCP;
    }

    private KcpProfile readKcpProfile(UnmodifiableConfig config) {
        String profileStr = null;

        var kcp = rawAt(config, "kcp");
        if (kcp instanceof UnmodifiableConfig sub) {
            profileStr = stringAt(sub, "profile");
        }
        if (profileStr == null) {
            profileStr = stringAt(config, List.of("kcp", "profile"));
        }
        if (profileStr == null) {
            profileStr = stringAt(config, "kcp.profile");
        }

        if (profileStr == null) {
            return KcpProfile.BALANCE;
        }

        var parsed = KcpProfile.parse(profileStr);
        if (parsed == null) {
            LOGGER.warn("Unknown kcp profile '{}' in {}: using default", profileStr, file);
            return KcpProfile.BALANCE;
        }

        return parsed;
    }

    private static @Nullable Object rawAt(
            UnmodifiableConfig config,
            String path
    ) {
        var value = config.getRaw(path);
        return value == null || NO_VALUE.equals(value)
                ? null
                : value;
    }

    private static @Nullable String stringAt(
            UnmodifiableConfig config,
            String path
    ) {
        var value = rawAt(config, path);
        return value instanceof String text
                ? text
                : null;
    }

    private static @Nullable String stringAt(
            UnmodifiableConfig config,
            List<String> path
    ) {
        var value = rawAt(config, path);
        return value instanceof String text
                ? text
                : null;
    }

    private static @Nullable Object rawAt(
            UnmodifiableConfig config,
            List<String> path
    ) {
        var value = config.getRaw(path);
        return value == null || NO_VALUE.equals(value)
                ? null
                : value;
    }

    public void save(ClientSettings settings) {
        var content = "mode = \"%s\"\n\n[kcp]\nprofile = \"%s\"\n".formatted(
                settings.mode().configValue(),
                settings.kcpProfile().configValue()
        );
        saveAtomically(content);
    }

    private void saveAtomically(String content) {
        var target = file.toAbsolutePath();
        var parent = target.getParent();
        if (parent == null) {
            LOGGER.warn("Cannot resolve parent directory for client config {}", target);
            return;
        }

        Path tmp = null;
        try {
            Files.createDirectories(parent);
            tmp = parent.resolve(
                    target.getFileName() + ".tmp-" + UUID.randomUUID()
            );
            try (
                    var channel = FileChannel.open(
                            tmp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    )
            ) {
                var _ = channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            moveAtomically(tmp, target);
        } catch (IOException e) {
            LOGGER.warn("Failed to save client config to {}: {}", target, e.toString());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException _) {
                    // Best-effort temp cleanup
                }
            }
        }
    }

    private static void moveAtomically(
            Path tmp,
            Path target
    ) throws IOException {
        try {
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

}
