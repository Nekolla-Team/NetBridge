package top.tangge233.netbridge.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.transport.TransportMode;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NetBridgePropertiesTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(NetBridgeProperties.KEY_TRANSPORT);
        System.clearProperty(NetBridgeProperties.KEY_QUIC_PORT);
        System.clearProperty(NetBridgeProperties.KEY_NATIVE_PATH);
        System.clearProperty(NetBridgeProperties.KEY_NATIVE_CACHE_DIR);
    }

    @Test
    void defaultsWhenNothingSet() {
        var props = NetBridgeProperties.load();

        assertNull(props.transportOverride());
        assertNull(props.quicPort());
        assertNull(props.nativeLibraryPath());
        assertNull(props.nativeCacheDirectory());
    }

    @Test
    void parsesAllKeys() {
        System.setProperty(NetBridgeProperties.KEY_TRANSPORT, "kcp");
        System.setProperty(NetBridgeProperties.KEY_QUIC_PORT, "28888");
        System.setProperty(NetBridgeProperties.KEY_NATIVE_PATH, "/opt/libnet.so");
        System.setProperty(NetBridgeProperties.KEY_NATIVE_CACHE_DIR, "/tmp/nb-cache");

        var props = NetBridgeProperties.load();

        assertEquals(TransportMode.KCP, props.transportOverride());
        assertEquals(28888, props.quicPort());
        assertEquals(Path.of("/opt/libnet.so"), props.nativeLibraryPath());
        assertEquals(Path.of("/tmp/nb-cache"), props.nativeCacheDirectory());
    }

    @Test
    void ignoresBlankKeys() {
        System.setProperty(NetBridgeProperties.KEY_TRANSPORT, "  ");
        System.setProperty(NetBridgeProperties.KEY_QUIC_PORT, "");
        System.setProperty(NetBridgeProperties.KEY_NATIVE_PATH, " ");

        var props = NetBridgeProperties.load();

        assertNull(props.transportOverride());
        assertNull(props.quicPort());
        assertNull(props.nativeLibraryPath());
    }

    @Test
    void unknownTransportFallsBackToNull() {
        System.setProperty(NetBridgeProperties.KEY_TRANSPORT, "sctp");

        assertNull(NetBridgeProperties.load().transportOverride());
    }

    @Test
    void nonNumericQuicPortFallsBackToNull() {
        System.setProperty(NetBridgeProperties.KEY_QUIC_PORT, "not-a-number");

        assertNull(NetBridgeProperties.load().quicPort());
    }

    @Test
    void transportParsingIsCaseInsensitive() {
        System.setProperty(NetBridgeProperties.KEY_TRANSPORT, "QUIC");

        assertEquals(TransportMode.QUIC, NetBridgeProperties.load().transportOverride());
    }

}
