package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public record FfmApiV1(
        MethodHandle contextCreate,
        MethodHandle contextShutdown,
        MethodHandle contextDestroy,
        MethodHandle connect,
        MethodHandle connectionState,
        MethodHandle connectionRemoteAddress,
        MethodHandle connectionWrite,
        MethodHandle connectionRead,
        MethodHandle connectionClose,
        MethodHandle serverStart,
        MethodHandle serverPort,
        MethodHandle serverStop,
        int abiMajor,
        int abiMinor,
        int structSize,
        long featureBits
) {

    public static final long FEATURE_QUIC = 1L << 0;
    public static final long FEATURE_KCP = 1L << 1;
    public static final long FEATURE_WRITABLE_EVENT = 1L << 2;
    public static final long FEATURE_BINARY_SOCKET_ADDRESS = 1L << 3;
    public static final long FEATURE_SERVER_STATE_EVENT = 1L << 4;

    private FfmApiV1(
            int abiMajor,
            int abiMinor,
            int structSize,
            long featureBits,
            MethodHandle contextCreate,
            MethodHandle contextShutdown,
            MethodHandle contextDestroy,
            MethodHandle connect,
            MethodHandle connectionState,
            MethodHandle connectionRemoteAddress,
            MethodHandle connectionWrite,
            MethodHandle connectionRead,
            MethodHandle connectionClose,
            MethodHandle serverStart,
            MethodHandle serverPort,
            MethodHandle serverStop
    ) {
        this(
                contextCreate,
                contextShutdown,
                contextDestroy,
                connect,
                connectionState,
                connectionRemoteAddress,
                connectionWrite,
                connectionRead,
                connectionClose,
                serverStart,
                serverPort,
                serverStop,
                abiMajor,
                abiMinor,
                structSize,
                featureBits
        );
    }

    public static FfmApiV1 fromSegment(MemorySegment tableSegment) {
        return fromAddress(tableSegment, Arena.global());
    }

    public static FfmApiV1 fromAddress(
            MemorySegment tableAddress,
            Arena arena
    ) {
        if (tableAddress.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Null API table pointer");
        }

        var headerSegment = tableAddress.reinterpret(
                FfmApiLayouts.API_HEADER_V1.byteSize(),
                arena,
                null
        );

        var major = headerSegment.get(
                ValueLayout.JAVA_INT,
                FfmApiLayouts.API_HEADER_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("abi_major")
                )
        );
        var minor = headerSegment.get(
                ValueLayout.JAVA_INT,
                FfmApiLayouts.API_HEADER_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("abi_minor")
                )
        );
        var reportedSize = headerSegment.get(
                ValueLayout.JAVA_INT,
                FfmApiLayouts.API_HEADER_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("struct_size")
                )
        );
        var features = headerSegment.get(
                ValueLayout.JAVA_LONG,
                FfmApiLayouts.API_HEADER_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("feature_bits")
                )
        );

        if (major != 1) {
            throw new IllegalStateException(
                    "Unsupported ABI major version: " + major + " (expected 1)"
            );
        }
        if (minor < 0) {
            throw new IllegalStateException(
                    "Unsupported ABI minor version: " + minor
            );
        }

        var minRequiredSize = FfmApiLayouts.API_V1.byteOffset(
                MemoryLayout.PathElement.groupElement("server_stop")
        ) + ValueLayout.ADDRESS.byteSize();
        if (reportedSize < minRequiredSize) {
            throw new IllegalStateException(
                    "Truncated API table: struct_size=%d < minRequired=%d".formatted(
                            reportedSize,
                            minRequiredSize
                    )
            );
        }

        var safeSize = Math.min(reportedSize, (int) FfmApiLayouts.API_V1.byteSize());
        var fullSegment = tableAddress.reinterpret(safeSize, arena, null);
        var linker = Linker.nativeLinker();

        var contextCreatePtr = getFnPtr(
                fullSegment,
                reportedSize,
                "context_create"
        );
        var contextShutdownPtr = getFnPtr(
                fullSegment,
                reportedSize,
                "context_shutdown"
        );
        var contextDestroyPtr = getFnPtr(
                fullSegment,
                reportedSize,
                "context_destroy"
        );

        var connectPtr = getFnPtr(
                fullSegment,
                reportedSize,
                "connect"
        );
        var connectionStatePtr = getFnPtr(
                fullSegment,
                reportedSize,
                "connection_state"
        );
        var connectionRemoteAddressPtr = getFnPtr(
                fullSegment,
                reportedSize,
                "connection_remote_address"
        );
        var connectionWritePtr = getFnPtr(
                fullSegment,
                reportedSize,
                "connection_write"
        );
        var connectionReadPtr = getFnPtr(
                fullSegment,
                reportedSize,
                "connection_read"
        );
        var connectionClosePtr = getFnPtr(
                fullSegment,
                reportedSize,
                "connection_close"
        );

        var serverStartPtr = getFnPtr(
                fullSegment,
                reportedSize,
                "server_start"
        );
        var serverPortPtr = getFnPtr(
                fullSegment,
                reportedSize,
                "server_port"
        );
        var serverStopPtr = getFnPtr(
                fullSegment,
                reportedSize,
                "server_stop"
        );

        return new FfmApiV1(
                major,
                minor,
                reportedSize,
                features,
                linker.downcallHandle(
                        contextCreatePtr,
                        FfmApiLayouts.CONTEXT_CREATE_DESC
                ),
                linker.downcallHandle(
                        contextShutdownPtr,
                        FfmApiLayouts.CONTEXT_SHUTDOWN_DESC
                ),
                linker.downcallHandle(
                        contextDestroyPtr,
                        FfmApiLayouts.CONTEXT_DESTROY_DESC
                ),
                linker.downcallHandle(
                        connectPtr,
                        FfmApiLayouts.CONNECT_DESC
                ),
                linker.downcallHandle(
                        connectionStatePtr,
                        FfmApiLayouts.CONNECTION_STATE_DESC
                ),
                linker.downcallHandle(
                        connectionRemoteAddressPtr,
                        FfmApiLayouts.CONNECTION_REMOTE_ADDRESS_DESC
                ),
                linker.downcallHandle(
                        connectionWritePtr,
                        FfmApiLayouts.CONNECTION_WRITE_DESC
                ),
                linker.downcallHandle(
                        connectionReadPtr,
                        FfmApiLayouts.CONNECTION_READ_DESC
                ),
                linker.downcallHandle(
                        connectionClosePtr,
                        FfmApiLayouts.CONNECTION_CLOSE_DESC
                ),
                linker.downcallHandle(
                        serverStartPtr,
                        FfmApiLayouts.SERVER_START_DESC
                ),
                linker.downcallHandle(
                        serverPortPtr,
                        FfmApiLayouts.SERVER_PORT_DESC
                ),
                linker.downcallHandle(
                        serverStopPtr,
                        FfmApiLayouts.SERVER_STOP_DESC
                )
        );
    }

    private static MemorySegment getFnPtr(
            MemorySegment segment,
            int structSize,
            String name
    ) {
        var offset = FfmApiLayouts.API_V1.byteOffset(MemoryLayout.PathElement.groupElement(name));
        if (offset + ValueLayout.ADDRESS.byteSize() > structSize) {
            throw new IllegalStateException(
                    "Field %s offset exceeds reported struct_size: %d".formatted(name, structSize)
            );
        }
        var addr = segment.get(ValueLayout.ADDRESS, offset);
        if (addr.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Function pointer in API table is null: " + name);
        }
        return addr;
    }

}
