package team.nekolla.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

public final class FfmApiLayouts {

    public static final StructLayout BYTES_VIEW_V1 = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("data"),
            ValueLayout.JAVA_INT.withName("length"),
            ValueLayout.JAVA_INT.withName("reserved0")
    ).withName("nb_bytes_view_v1");

    public static final StructLayout SOCKET_ADDRESS_V1 = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("family"),
            ValueLayout.JAVA_SHORT.withName("port"),
            ValueLayout.JAVA_SHORT.withName("reserved0"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_BYTE).withName("address"),
            ValueLayout.JAVA_INT.withName("scope_id"),
            ValueLayout.JAVA_INT.withName("reserved1")
    ).withName("nb_socket_address_v1");

    public static final StructLayout CONTEXT_OPTIONS_V1 = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("struct_size"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("worker_threads"),
            ValueLayout.JAVA_INT.withName("reserved0"),
            ValueLayout.JAVA_INT.withName("shared_io_tx_capacity"),
            ValueLayout.JAVA_INT.withName("shared_io_rx_capacity"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_LONG).withName("reserved")
    ).withName("nb_context_options_v1");

    public static final StructLayout CALLBACKS_V1 = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("struct_size"),
            ValueLayout.JAVA_INT.withName("reserved0"),
            ValueLayout.ADDRESS.withName("on_event"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_LONG).withName("reserved")
    ).withName("nb_callbacks_v1");

    public static final StructLayout CONNECT_OPTIONS_V1 = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("struct_size"),
            ValueLayout.JAVA_INT.withName("transport_kind"),
            BYTES_VIEW_V1.withName("host_utf8"),
            ValueLayout.JAVA_SHORT.withName("port"),
            ValueLayout.JAVA_SHORT.withName("reserved0"),
            ValueLayout.JAVA_INT.withName("kcp_profile"),
            ValueLayout.JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_LONG).withName("reserved")
    ).withName("nb_connect_options_v1");

    public static final StructLayout SERVER_OPTIONS_V1 = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("struct_size"),
            ValueLayout.JAVA_INT.withName("transport_kind"),
            BYTES_VIEW_V1.withName("bind_host_utf8"),
            ValueLayout.JAVA_SHORT.withName("port"),
            ValueLayout.JAVA_SHORT.withName("reserved0"),
            ValueLayout.JAVA_INT.withName("max_connections"),
            ValueLayout.JAVA_INT.withName("kcp_profile"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("reserved1"),
            MemoryLayout.paddingLayout(4),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_LONG).withName("reserved")
    ).withName("nb_server_options_v1");

    public static final StructLayout SHARED_IO_REGION_V1 = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("struct_size"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_LONG.withName("layout_version"),
            ValueLayout.ADDRESS.withName("tx_base"),
            ValueLayout.JAVA_LONG.withName("tx_total_bytes"),
            ValueLayout.JAVA_LONG.withName("tx_capacity"),
            ValueLayout.ADDRESS.withName("rx_base"),
            ValueLayout.JAVA_LONG.withName("rx_total_bytes"),
            ValueLayout.JAVA_LONG.withName("rx_capacity"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_LONG).withName("reserved")
    ).withName("nb_shared_io_region_v1");

    public static final StructLayout API_HEADER_V1 = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("abi_major"),
            ValueLayout.JAVA_INT.withName("abi_minor"),
            ValueLayout.JAVA_INT.withName("struct_size"),
            ValueLayout.JAVA_INT.withName("reserved0"),
            ValueLayout.JAVA_LONG.withName("feature_bits")
    ).withName("nb_api_header_v1");

    public static final StructLayout API_V1 = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("abi_major"),
            ValueLayout.JAVA_INT.withName("abi_minor"),
            ValueLayout.JAVA_INT.withName("struct_size"),
            ValueLayout.JAVA_INT.withName("reserved0"),
            ValueLayout.JAVA_LONG.withName("feature_bits"),

            ValueLayout.ADDRESS.withName("context_create"),
            ValueLayout.ADDRESS.withName("context_shutdown"),
            ValueLayout.ADDRESS.withName("context_destroy"),

            ValueLayout.ADDRESS.withName("connect"),
            ValueLayout.ADDRESS.withName("connection_state"),
            ValueLayout.ADDRESS.withName("connection_remote_address"),
            ValueLayout.ADDRESS.withName("connection_write"),
            ValueLayout.ADDRESS.withName("connection_read"),
            ValueLayout.ADDRESS.withName("connection_close"),

            ValueLayout.ADDRESS.withName("server_start"),
            ValueLayout.ADDRESS.withName("server_port"),
            ValueLayout.ADDRESS.withName("server_stop"),

            ValueLayout.ADDRESS.withName("connection_io_region"),
            ValueLayout.ADDRESS.withName("connection_io_kick"),

            MemoryLayout.sequenceLayout(6, ValueLayout.JAVA_LONG).withName("reserved")
    ).withName("nb_api_v1");

    /* === Function Descriptors === */

    public static final FunctionDescriptor GET_API_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor EVENT_CALLBACK_DESC = FunctionDescriptor.ofVoid(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG
    );

    public static final FunctionDescriptor CONTEXT_CREATE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor CONTEXT_SHUTDOWN_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT
    );

    public static final FunctionDescriptor CONTEXT_DESTROY_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor CONNECT_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor CONNECTION_STATE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor CONNECTION_REMOTE_ADDRESS_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor CONNECTION_WRITE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor CONNECTION_READ_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor CONNECTION_CLOSE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG
    );

    public static final FunctionDescriptor SERVER_START_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor SERVER_PORT_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor SERVER_STOP_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG
    );

    public static final FunctionDescriptor CONNECTION_IO_REGION_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS
    );

    public static final FunctionDescriptor CONNECTION_IO_KICK_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT
    );

    private FfmApiLayouts() {
        super();
    }

}
