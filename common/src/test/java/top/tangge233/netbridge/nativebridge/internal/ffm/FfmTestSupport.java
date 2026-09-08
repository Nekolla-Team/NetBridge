package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class FfmTestSupport {

    private FfmTestSupport() {
    }

    /**
     * For native-backed integration test classes. Call from {@code @BeforeAll}; when no cdylib is
     * staged the class is skipped via a JUnit assumption instead of failing, keeping pure-Java
     * {@code test} runs green.
     */
    public static Path requireNativeLibraryOrSkip() {
        var lib = findNativeLibraryOrNull();
        Assumptions.assumeTrue(
                lib != null,
                () -> "native library not available for tests; checked " + Arrays.toString(
                        candidates()
                )
        );
        return Objects.requireNonNull(lib);
    }

    /**
     * Locates the staged native cdylib for integration tests, honoring
     * {@code netbridge.native.path} first, then well-known relative build locations. Returns
     * {@code null} when no library is available (for example a pure-Java {@code test} run without
     * {@code -PskipNativeBuild} or without a prior native build).
     */
    public static @Nullable Path findNativeLibraryOrNull() {
        var prop = System.getProperty("netbridge.native.path");
        if (prop != null && !prop.isBlank()) {
            var p = Path.of(prop);
            if (Files.exists(p)) {
                return p.toAbsolutePath();
            }
        }

        return Arrays.stream(candidates())
                .filter(Files::exists)
                .findFirst()
                .map(Path::toAbsolutePath)
                .orElse(null);
    }

    private static Path[] candidates() {
        var libName = NativeLibraryResolver.nativeResourceName();
        var platformDir = NativeLibraryResolver.platformDir();
        return new Path[]{
                Path.of("build/native", platformDir, libName),
                Path.of("../build/native", platformDir, libName),
                Path.of("../../build/native", platformDir, libName),
                Path.of("rust/target/debug", libName),
                Path.of("../rust/target/debug", libName),
                Path.of("../../rust/target/debug", libName),
                Path.of("rust/target/release", libName),
                Path.of("../rust/target/release", libName),
                Path.of("../../rust/target/release", libName),
        };
    }

}
