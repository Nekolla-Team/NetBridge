package team.nekolla.netbridge.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import team.nekolla.netbridge.transport.TransportMode;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class NetBridgePropertiesTest {

    @AfterEach
    void tearDown() {
        Arrays.asList(
                NetBridgeProperties.KEY_TRANSPORT,
                NetBridgeProperties.KEY_QUIC_PORT,
                NetBridgeProperties.KEY_NATIVE_PATH,
                NetBridgeProperties.KEY_NATIVE_CACHE_DIR,
                NetBridgeProperties.KEY_SHARED_IO,
                NetBridgeProperties.KEY_SHARED_IO_TX_CAPACITY,
                NetBridgeProperties.KEY_SHARED_IO_RX_CAPACITY
        ).forEach(System::clearProperty);
    }

    @Test
    void defaultsWhenNothingSet() {
        var props = NetBridgeProperties.load();

        assertNull(props.transportOverride());
        assertNull(props.quicPort());
        assertNull(props.nativeLibraryPath());
        assertNull(props.nativeCacheDirectory());
        assertEquals(SharedIoMode.AUTO, props.sharedIoMode());
        assertEquals(0, props.sharedIoTxCapacity());
        assertEquals(0, props.sharedIoRxCapacity());
    }

    @Test
    void parsesAllKeys() {
        System.setProperty(NetBridgeProperties.KEY_TRANSPORT, "kcp");
        System.setProperty(NetBridgeProperties.KEY_QUIC_PORT, "28888");
        System.setProperty(NetBridgeProperties.KEY_NATIVE_PATH, "/opt/libnet.so");
        System.setProperty(NetBridgeProperties.KEY_NATIVE_CACHE_DIR, "/tmp/nb-cache");
        System.setProperty(NetBridgeProperties.KEY_SHARED_IO, "on");
        System.setProperty(NetBridgeProperties.KEY_SHARED_IO_TX_CAPACITY, "131072");
        System.setProperty(NetBridgeProperties.KEY_SHARED_IO_RX_CAPACITY, "262144");

        var props = NetBridgeProperties.load();

        assertEquals(TransportMode.KCP, props.transportOverride());
        assertEquals(28888, props.quicPort());
        assertEquals(Path.of("/opt/libnet.so"), props.nativeLibraryPath());
        assertEquals(Path.of("/tmp/nb-cache"), props.nativeCacheDirectory());
        assertEquals(SharedIoMode.ON, props.sharedIoMode());
        assertEquals(131072, props.sharedIoTxCapacity());
        assertEquals(262144, props.sharedIoRxCapacity());
    }

    @Test
    void invalidSharedIoModeFailsFast() {
        System.setProperty(NetBridgeProperties.KEY_SHARED_IO, "sometimes");

        assertThrows(IllegalArgumentException.class, NetBridgeProperties::load);
    }

    @Test
    void invalidSharedIoCapacityFailsFast() {
        System.setProperty(NetBridgeProperties.KEY_SHARED_IO_TX_CAPACITY, "100000");
        assertThrows(IllegalArgumentException.class, NetBridgeProperties::load);

        System.setProperty(NetBridgeProperties.KEY_SHARED_IO_TX_CAPACITY, "32768");
        assertThrows(IllegalArgumentException.class, NetBridgeProperties::load);

        System.setProperty(NetBridgeProperties.KEY_SHARED_IO_TX_CAPACITY, "2097152");
        assertThrows(IllegalArgumentException.class, NetBridgeProperties::load);
    }

    @Test
    void invalidSharedIoCapacityIsNotNumeric() {
        System.setProperty(NetBridgeProperties.KEY_SHARED_IO_RX_CAPACITY, "big");

        assertThrows(IllegalArgumentException.class, NetBridgeProperties::load);
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
