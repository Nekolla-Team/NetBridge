package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Parsed immutable metadata from a native platform manifest.
 */
record NativeManifest(
        String artifact,
        String sha256,
        int abiMajor,
        int abiMinor,
        String rustPackageVersion
) {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    NativeManifest {
        requireNonNull(artifact, "artifact");
        requireNonNull(sha256, "sha256");
        requireNonNull(rustPackageVersion, "rustPackageVersion");
        if (artifact.isBlank()) {
            throw new IllegalArgumentException("artifact must not be blank");
        }
        if (!SHA256_HEX.matcher(sha256).matches()) {
            throw new IllegalArgumentException(
                    "sha256 must be exactly 64 hex characters: " + sha256
            );
        }
        if (abiMajor < 0 || abiMinor < 0) {
            throw new IllegalArgumentException(
                    "ABI versions must be non-negative: " + abiMajor + "." + abiMinor
            );
        }
        if (rustPackageVersion.isBlank()) {
            throw new IllegalArgumentException("rustPackageVersion must not be blank");
        }
    }

}
