package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class NativeLibraryResolverTest {

    @Test
    void platformResolutionReturnsValidValuesOnCurrentPlatform() {
        var os = NativeLibraryResolver.normalizedOs();
        var arch = NativeLibraryResolver.normalizedArch();
        assertNotEquals("unknown", os);
        assertNotEquals("unknown", arch);

        var resName = NativeLibraryResolver.nativeResourceName();
        var platformDir = NativeLibraryResolver.platformDir();

        assertNotNull(resName);
        assertFalse(resName.isBlank());
        assertNotNull(platformDir);
        assertTrue(platformDir.contains("-"));
        assertTrue(NativeLibraryResolver.nativeResourcePath().startsWith("native/"));
    }

    @Test
    void unsupportedPlatformThrowsNativeResourceException() {
        var originalOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "SolarisOS");
            var ex = assertThrows(
                    NativeResourceException.class,
                    NativeLibraryResolver::nativeResourceName
            );
            assertEquals("UNSUPPORTED_PLATFORM:", ex.code());
            var msg = ex.getMessage();
            assertNotNull(msg);
            assertTrue(msg.contains("SolarisOS".toLowerCase(Locale.ROOT)));
        } finally {
            if (originalOs != null) {
                System.setProperty("os.name", originalOs);
            }
        }
    }

    @Test
    void unsupportedArchThrowsNativeResourceException() {
        var originalArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.arch", "mips64");
            var ex = assertThrows(
                    NativeResourceException.class,
                    NativeLibraryResolver::platformDir
            );
            assertEquals("UNSUPPORTED_PLATFORM:", ex.code());
            var msg = ex.getMessage();
            assertNotNull(msg);
            assertTrue(msg.contains("mips64"));
        } finally {
            if (originalArch != null) {
                System.setProperty("os.arch", originalArch);
            }
        }
    }

    @Test
    void windowsAarch64ThrowsNativeResourceException() {
        var originalOs = System.getProperty("os.name");
        var originalArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "aarch64");
            var ex = assertThrows(
                    NativeResourceException.class,
                    NativeLibraryResolver::platformDir
            );
            assertEquals("UNSUPPORTED_PLATFORM:", ex.code());
        } finally {
            if (originalOs != null) {
                System.setProperty("os.name", originalOs);
            }
            if (originalArch != null) {
                System.setProperty("os.arch", originalArch);
            }
        }
    }

}
