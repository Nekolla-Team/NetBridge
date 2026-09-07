package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.NetBridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class NativeLibraryResolver {

    private NativeLibraryResolver() {
    }

    public static String nativeResourcePath() {
        return "native/" + platformDir() + "/" + nativeResourceName();
    }

    public static String normalizedOs() {
        return switch (System.getProperty("os.name", "").toLowerCase(Locale.ROOT)) {
            case String s when s.contains("win") -> "windows";
            case String s when s.contains("mac")
                    || s.contains("darwin") -> "macos";
            case String s when s.contains("nux")
                    || s.contains("nix")
                    || s.contains("linux") -> "linux";
            default -> "unknown";
        };
    }

    public static String normalizedArch() {
        var raw = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> "unknown";
        };
    }

    public static String nativeResourceName() {
        return switch (normalizedOs()) {
            case "windows" -> "net_bridge_native.dll";
            case "macos" -> "libnet_bridge_native.dylib";
            case "linux" -> "libnet_bridge_native.so";
            default -> throw unsupportedPlatform();
        };
    }

    @SuppressWarnings("SwitchStatementWithTooFewBranches")
    public static String platformDir() {
        var os = normalizedOs();
        var arch = normalizedArch();
        return switch (os) {
            case "linux" -> switch (arch) {
                case "x86_64", "aarch64" -> "linux-" + arch;
                default -> throw unsupportedPlatform();
            };
            case "macos" -> switch (arch) {
                case "x86_64", "aarch64" -> "macos-" + arch;
                default -> throw unsupportedPlatform();
            };
            case "windows" -> switch (arch) {
                case "x86_64" -> "windows-x86_64";
                default -> throw unsupportedPlatform();
            };
            default -> throw unsupportedPlatform();
        };
    }

    private static NativeResourceException unsupportedPlatform() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return new NativeResourceException(
                NativeResourceError.UNSUPPORTED_PLATFORM,
                "normalized os=" + os + " arch=" + arch
        );
    }

    private static Path defaultCacheRoot() {
        return Path.of(System.getProperty("user.home"))
                .resolve(".netbridge")
                .resolve("native");
    }

    static NativeManifest readPackagedManifest() {
        var manifestResource = "native/" + platformDir() + "/manifest.json";
        var url = NativeLibraryResolver.class.getResource("/" + manifestResource);

        if (url == null) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MISSING,
                    "RESOURCE_MANIFEST_MISSING: %s (platform=%s)".formatted(
                            manifestResource,
                            platformDir()
                    )
            );
        }

        NativeManifest manifest;
        try (var in = url.openStream()) {
            manifest = NativeManifestCodec.parse(in);
        } catch (IOException e) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MISSING,
                    "RESOURCE_MANIFEST_MISSING: cannot read " + manifestResource,
                    e
            );
        }

        if (manifest.abiMajor() != 1) {
            throw new NativeResourceException(
                    NativeResourceError.ABI_MAJOR_MISMATCH,
                    "ABI_MAJOR_MISMATCH: manifest abiMajor=" + manifest.abiMajor() + " (expected 1)"
            );
        }

        if (manifest.abiMinor() < 0) {
            throw new NativeResourceException(
                    NativeResourceError.ABI_MINOR_INCOMPATIBLE,
                    "ABI_MINOR_INCOMPATIBLE: manifest abiMinor=" + manifest.abiMinor()
            );
        }

        if (!manifest.artifact().equals(nativeResourceName())) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MANIFEST_INVALID,
                    "RESOURCE_MANIFEST_INVALID: artifact %s does not match platform artifact %s".formatted(
                            manifest.artifact(),
                            nativeResourceName()
                    )
            );
        }

        return manifest;
    }

    public static Path extractPackagedLibrary() {
        return extractPackagedLibrary(null);
    }

    public static Path extractPackagedLibrary(@Nullable Path cacheDirectory) {
        var manifest = readPackagedManifest();
        var resource = nativeResourcePath();
        var url = NativeLibraryResolver.class.getResource("/" + resource);

        if (url == null) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MISSING,
                    resource
            );
        }

        var expectedSha = Objects.requireNonNull(manifest.sha256())
                .toLowerCase(Locale.ROOT);
        var cacheRoot = cacheDirectory != null
                ? cacheDirectory
                : defaultCacheRoot();
        var target = cacheRoot.resolve(expectedSha).resolve(nativeResourceName());

        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new NativeResourceException(
                    NativeResourceError.CACHE_UNWRITABLE,
                    "cannot create cache dir " + target.getParent(),
                    e
            );
        }

        if (Files.exists(target)) {
            try (var in = Files.newInputStream(target)) {
                var actual = sha256Hex(in);
                if (actual.equals(expectedSha)) {
                    return target;
                }

                NetBridge.LOGGER.warn(
                        "net-bridge native cache entry corrupted ({}), re-extracting",
                        target
                );
            } catch (IOException e) {
                NetBridge.LOGGER.warn(
                        "net-bridge native cache entry unreadable ({}), re-extracting",
                        target
                );
            }
        }

        var tmp = cacheRoot
                .resolve(expectedSha)
                .resolve(nativeResourceName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(tmp.getParent());
            try (
                    var in = url.openStream();
                    var out = Files.newOutputStream(
                            tmp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                    )
            ) {
                var digest = MessageDigest.getInstance("SHA-256");
                var buffer = new byte[8192];

                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }

                var actualSha = hex(digest.digest());
                if (!actualSha.equals(expectedSha)) {
                    throw new NativeResourceException(
                            NativeResourceError.CHECKSUM_MISMATCH,
                            "%s: expected %s, got %s".formatted(
                                    resource,
                                    expectedSha,
                                    actualSha
                            )
                    );
                }

                moveAtomically(tmp, target);
                return target;
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new NativeResourceException(
                    NativeResourceError.CACHE_UNWRITABLE,
                    "failed to extract " + resource,
                    e
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    e
            );
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
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            if (Files.exists(target)) {
                try (
                        var inTarget = Files.newInputStream(target);
                        var inTmp = Files.newInputStream(tmp)
                ) {
                    if (sha256Hex(inTarget).equals(sha256Hex(inTmp))) {
                        return;
                    }
                } catch (IOException _) {
                    // Ignore and proceed to replace existing target
                }
            }
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    static String sha256Hex(InputStream in) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

}
