package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.nativebridge.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.Inet6Address;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FfmAbiCodecTest {

    @Test
    void transportMapsToAbiExplicitly() {
        assertEquals(
                FfmAbiCodec.TRANSPORT_QUIC,
                FfmAbiCodec.transportToAbi(NativeTransportKind.QUIC)
        );
        assertEquals(
                FfmAbiCodec.TRANSPORT_KCP,
                FfmAbiCodec.transportToAbi(NativeTransportKind.KCP)
        );
    }

    @Test
    void kcpProfileMapsToAbiExplicitly() {
        assertEquals(
                FfmAbiCodec.KCP_PROFILE_BALANCED,
                FfmAbiCodec.kcpProfileToAbi(NativeKcpProfile.BALANCED)
        );
        assertEquals(
                FfmAbiCodec.KCP_PROFILE_AGGRESSIVE,
                FfmAbiCodec.kcpProfileToAbi(NativeKcpProfile.AGGRESSIVE)
        );
    }

    @Test
    void everyConnectionStateDecodes() {
        assertEquals(
                NativeConnectionState.CONNECTING,
                FfmAbiCodec.connectionStateFromAbi(1)
        );
        assertEquals(
                NativeConnectionState.CONNECTED,
                FfmAbiCodec.connectionStateFromAbi(2)
        );
        assertEquals(
                NativeConnectionState.CLOSED,
                FfmAbiCodec.connectionStateFromAbi(3)
        );
        assertEquals(
                NativeConnectionState.FAILED,
                FfmAbiCodec.connectionStateFromAbi(4)
        );
    }

    @Test
    void unknownConnectionStateIsRejectedNotHealthy() {
        IntStream.of(0, 5, -1)
                .forEach(i -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FfmAbiCodec.connectionStateFromAbi(i)
                ));
    }

    @Test
    void everyServerStateDecodes() {
        assertEquals(
                NativeServerState.RUNNING,
                FfmAbiCodec.serverStateFromAbi(1)
        );
        assertEquals(
                NativeServerState.STOPPED,
                FfmAbiCodec.serverStateFromAbi(2)
        );
        assertEquals(
                NativeServerState.FAILED,
                FfmAbiCodec.serverStateFromAbi(3)
        );
    }

    @Test
    void unknownServerStateIsRejectedNotRunning() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FfmAbiCodec.serverStateFromAbi(0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> FfmAbiCodec.serverStateFromAbi(4)
        );
    }

    @Test
    void everyKnownFailureReasonDecodes() {
        assertEquals(NativeFailureReason.GENERIC, FfmAbiCodec.failureReasonFromCode(0));
        assertEquals(NativeFailureReason.DNS, FfmAbiCodec.failureReasonFromCode(1));
        assertEquals(NativeFailureReason.SETUP, FfmAbiCodec.failureReasonFromCode(2));
        assertEquals(NativeFailureReason.REFUSED, FfmAbiCodec.failureReasonFromCode(3));
        assertEquals(NativeFailureReason.TIMEOUT, FfmAbiCodec.failureReasonFromCode(4));
        assertEquals(NativeFailureReason.PROTOCOL, FfmAbiCodec.failureReasonFromCode(5));
        assertEquals(NativeFailureReason.CANCELLED, FfmAbiCodec.failureReasonFromCode(6));
        assertEquals(NativeFailureReason.INTERNAL, FfmAbiCodec.failureReasonFromCode(7));
    }

    @Test
    void unknownFailureReasonMapsToDeliberateGeneric() {
        assertEquals(NativeFailureReason.GENERIC, FfmAbiCodec.failureReasonFromCode(8));
        assertEquals(NativeFailureReason.GENERIC, FfmAbiCodec.failureReasonFromCode(-1));
    }

    @Test
    void decodeConnectionStateEvent() {
        var event = FfmAbiCodec.decodeEvent(1, 42, 2, 0);
        assertInstanceOf(NativeEvent.ConnectionStateChanged.class, event);
        var state = (NativeEvent.ConnectionStateChanged) event;
        assertEquals(42, state.connectionId());
        assertEquals(NativeConnectionState.CONNECTED, state.state());
        assertEquals(NativeFailureReason.GENERIC, state.reason());
    }

    @Test
    void decodeConnectionStateEventCarriesReason() {
        var event = FfmAbiCodec.decodeEvent(1, 7, 4, 3);
        var state = (NativeEvent.ConnectionStateChanged) event;
        assertEquals(NativeConnectionState.FAILED, state.state());
        assertEquals(NativeFailureReason.REFUSED, state.reason());
    }

    @Test
    void decodeDataAvailableAndWritableEvents() {
        var data = FfmAbiCodec.decodeEvent(2, 11, 0, 0);
        assertInstanceOf(NativeEvent.DataAvailable.class, data);
        assertEquals(11, ((NativeEvent.DataAvailable) data).connectionId());

        var writable = FfmAbiCodec.decodeEvent(3, 13, 0, 0);
        assertInstanceOf(NativeEvent.Writable.class, writable);
        assertEquals(13, ((NativeEvent.Writable) writable).connectionId());
    }

    @Test
    void decodeAcceptedEventUsesObjectIdAsServer() {
        var event = FfmAbiCodec.decodeEvent(4, 100, 55, 0);
        assertInstanceOf(NativeEvent.Accepted.class, event);
        var accepted = (NativeEvent.Accepted) event;
        assertEquals(100, accepted.serverId());
        assertEquals(55, accepted.connectionId());
    }

    @Test
    void decodeServerStateEvent() {
        var event = FfmAbiCodec.decodeEvent(5, 9, 1, 0);
        assertInstanceOf(NativeEvent.ServerStateChanged.class, event);
        var server = (NativeEvent.ServerStateChanged) event;
        assertEquals(9, server.serverId());
        assertEquals(NativeServerState.RUNNING, server.state());
    }

    @Test
    void unknownEventKindIsRejectedAtDecoder() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FfmAbiCodec.decodeEvent(9, 1, 0, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> FfmAbiCodec.decodeEvent(0, 1, 0, 0)
        );
    }

    @Test
    void malformedStateInEventIsRejectedAtDecoder() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FfmAbiCodec.decodeEvent(1, 1, 99, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> FfmAbiCodec.decodeEvent(5, 1, 7, 0)
        );
    }

    @Test
    void decodeIpv4SocketAddress() {
        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(FfmApiLayouts.SOCKET_ADDRESS_V1);
            writeSocketAddress(
                    segment,
                    4,
                    new byte[]{(byte) 203, 0, (byte) 113, 9},
                    0,
                    25565
            );
            var addr = FfmAbiCodec.decodeSocketAddress(segment);
            assertEquals("203.0.113.9", addr.getAddress().getHostAddress());
            assertEquals(25565, addr.getPort());
        }
    }

    private static void writeSocketAddress(
            MemorySegment segment,
            int family,
            byte[] bytes,
            int scopeId,
            int port
    ) {
        segment.set(
                ValueLayout.JAVA_INT,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("family")
                ),
                family
        );
        segment.set(
                ValueLayout.JAVA_SHORT,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("port")
                ),
                (short) port
        );
        var addrOffset = FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                MemoryLayout.PathElement.groupElement("address")
        );
        MemorySegment.copy(
                MemorySegment.ofArray(bytes),
                0,
                segment,
                addrOffset,
                bytes.length
        );
        segment.set(
                ValueLayout.JAVA_INT,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("scope_id")
                ),
                scopeId
        );
    }

    @Test
    void decodeIpv6SocketAddress() {
        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(FfmApiLayouts.SOCKET_ADDRESS_V1);
            writeSocketAddress(
                    segment,
                    6,
                    new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
                    0,
                    443
            );
            var addr = FfmAbiCodec.decodeSocketAddress(segment);
            assertArrayEquals(
                    new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
                    addr.getAddress().getAddress()
            );
            assertEquals(
                    443,
                    addr.getPort()
            );
        }
    }

    @Test
    void decodeIpv6SocketAddressKeepsScopeId() {
        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(FfmApiLayouts.SOCKET_ADDRESS_V1);
            writeSocketAddress(
                    segment,
                    6,
                    new byte[]{(byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
                    5,
                    0
            );
            var addr = FfmAbiCodec.decodeSocketAddress(segment);
            assertInstanceOf(
                    Inet6Address.class,
                    addr.getAddress()
            );
            assertEquals(
                    5,
                    ((Inet6Address) addr.getAddress()).getScopeId()
            );
        }
    }

    @Test
    void unknownSocketFamilyIsRejected() {
        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(FfmApiLayouts.SOCKET_ADDRESS_V1);
            writeSocketAddress(
                    segment,
                    99,
                    new byte[16],
                    0,
                    1
            );
            assertThrows(
                    NativeException.class,
                    () -> FfmAbiCodec.decodeSocketAddress(segment)
            );
        }
    }

}

