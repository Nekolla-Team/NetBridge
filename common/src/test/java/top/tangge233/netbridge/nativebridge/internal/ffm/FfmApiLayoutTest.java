package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemoryLayout.PathElement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FfmApiLayoutTest {

    @Test
    void testBytesViewLayout() {
        assertEquals(
                16,
                FfmApiLayouts.BYTES_VIEW_V1.byteSize()
        );
        assertEquals(
                0,
                FfmApiLayouts.BYTES_VIEW_V1.byteOffset(PathElement.groupElement("data"))
        );
        assertEquals(
                8,
                FfmApiLayouts.BYTES_VIEW_V1.byteOffset(PathElement.groupElement("length"))
        );
        assertEquals(
                12,
                FfmApiLayouts.BYTES_VIEW_V1.byteOffset(PathElement.groupElement("reserved0"))
        );
    }

    @Test
    void testSocketAddressLayout() {
        assertEquals(
                32,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteSize()
        );
        assertEquals(
                0,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(PathElement.groupElement("family"))
        );
        assertEquals(
                4,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(PathElement.groupElement("port"))
        );
        assertEquals(
                6,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(PathElement.groupElement("reserved0"))
        );
        assertEquals(
                8,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(PathElement.groupElement("address"))
        );
        assertEquals(
                24,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(PathElement.groupElement("scope_id"))
        );
        assertEquals(
                28,
                FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(PathElement.groupElement("reserved1"))
        );
    }

    @Test
    void testContextOptionsLayout() {
        assertEquals(
                48,
                FfmApiLayouts.CONTEXT_OPTIONS_V1.byteSize()
        );
        assertEquals(
                0,
                FfmApiLayouts.CONTEXT_OPTIONS_V1.byteOffset(PathElement.groupElement("struct_size"))
        );
        assertEquals(
                4,
                FfmApiLayouts.CONTEXT_OPTIONS_V1.byteOffset(PathElement.groupElement("flags"))
        );
        assertEquals(
                8,
                FfmApiLayouts.CONTEXT_OPTIONS_V1.byteOffset(
                        PathElement.groupElement("worker_threads")
                )
        );
        assertEquals(
                12,
                FfmApiLayouts.CONTEXT_OPTIONS_V1.byteOffset(PathElement.groupElement("reserved0"))
        );
        assertEquals(
                16,
                FfmApiLayouts.CONTEXT_OPTIONS_V1.byteOffset(PathElement.groupElement("reserved"))
        );
    }

    @Test
    void testCallbacksLayout() {
        assertEquals(
                48,
                FfmApiLayouts.CALLBACKS_V1.byteSize()
        );
        assertEquals(
                0,
                FfmApiLayouts.CALLBACKS_V1.byteOffset(PathElement.groupElement("struct_size"))
        );
        assertEquals(
                4,
                FfmApiLayouts.CALLBACKS_V1.byteOffset(PathElement.groupElement("reserved0"))
        );
        assertEquals(
                8,
                FfmApiLayouts.CALLBACKS_V1.byteOffset(PathElement.groupElement("on_event"))
        );
        assertEquals(
                16,
                FfmApiLayouts.CALLBACKS_V1.byteOffset(PathElement.groupElement("reserved"))
        );
    }

    @Test
    void testConnectOptionsLayout() {
        assertEquals(
                72,
                FfmApiLayouts.CONNECT_OPTIONS_V1.byteSize()
        );
        assertEquals(
                0,
                FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(PathElement.groupElement("struct_size"))
        );
        assertEquals(
                4,
                FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                        PathElement.groupElement("transport_kind")
                )
        );
        assertEquals(
                8,
                FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(PathElement.groupElement("host_utf8"))
        );
        assertEquals(
                24,
                FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(PathElement.groupElement("port"))
        );
        assertEquals(
                26,
                FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(PathElement.groupElement("reserved0"))
        );
        assertEquals(
                28,
                FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(PathElement.groupElement("kcp_profile"))
        );
        assertEquals(
                32,
                FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(PathElement.groupElement("flags"))
        );
        assertEquals(
                40,
                FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(PathElement.groupElement("reserved"))
        );
    }

    @Test
    void testServerOptionsLayout() {
        assertEquals(
                80,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteSize()
        );
        assertEquals(
                0,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(PathElement.groupElement("struct_size"))
        );
        assertEquals(
                4,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(PathElement.groupElement("transport_kind"))
        );
        assertEquals(
                8,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(PathElement.groupElement("bind_host_utf8"))
        );
        assertEquals(
                24,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(PathElement.groupElement("port"))
        );
        assertEquals(
                26,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(PathElement.groupElement("reserved0"))
        );
        assertEquals(
                28,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                        PathElement.groupElement("max_connections")
                )
        );
        assertEquals(
                32,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(PathElement.groupElement("kcp_profile"))
        );
        assertEquals(
                36,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(PathElement.groupElement("flags"))
        );
        assertEquals(
                40,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(PathElement.groupElement("reserved1"))
        );
        assertEquals(
                48,
                FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(PathElement.groupElement("reserved"))
        );
    }

    @Test
    void testApiV1Layout() {
        assertEquals(
                184,
                FfmApiLayouts.API_V1.byteSize()
        );
        assertEquals(
                0,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("abi_major"))
        );
        assertEquals(
                4,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("abi_minor"))
        );
        assertEquals(
                8,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("struct_size"))
        );
        assertEquals(
                12,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("reserved0"))
        );
        assertEquals(
                16,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("feature_bits"))
        );
        assertEquals(
                0b11111L,
                FfmApiV1.FEATURE_QUIC
                        | FfmApiV1.FEATURE_KCP
                        | FfmApiV1.FEATURE_WRITABLE_EVENT
                        | FfmApiV1.FEATURE_BINARY_SOCKET_ADDRESS
                        | FfmApiV1.FEATURE_SERVER_STATE_EVENT
        );

        assertEquals(
                24,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("context_create"))
        );
        assertEquals(
                32,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("context_shutdown"))
        );
        assertEquals(
                40,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("context_destroy"))
        );

        assertEquals(
                48,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("connect"))
        );
        assertEquals(
                56,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("connection_state"))
        );
        assertEquals(
                64,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("connection_remote_address"))
        );
        assertEquals(
                72,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("connection_write"))
        );
        assertEquals(
                80,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("connection_read"))
        );
        assertEquals(
                88,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("connection_close"))
        );

        assertEquals(
                96,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("server_start"))
        );
        assertEquals(
                104,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("server_port"))
        );
        assertEquals(
                112,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("server_stop"))
        );

        assertEquals(
                120,
                FfmApiLayouts.API_V1.byteOffset(PathElement.groupElement("reserved"))
        );
    }

}
