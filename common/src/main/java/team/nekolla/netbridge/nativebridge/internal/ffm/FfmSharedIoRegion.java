package team.nekolla.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Decoded {@code nb_shared_io_region_v1_t} descriptor for one connection.
 *
 * <p>The TX/RX regions are owned by native Rust; the addresses are only valid until the connection
 * is closed. This type validates the descriptor contract up front so callers can rely on the
 * capacities and layout version.
 *
 * @param structSize    native-reported descriptor size in bytes
 * @param flags         {@code NB_SHARED_IO_REGION_*} flag bits
 * @param layoutVersion ring layout version ({@code major << 32 | minor})
 * @param txBase        base address of the Java to transport ring region
 * @param txTotalBytes  total mapped TX size ({@code header + capacity})
 * @param txCapacity    usable TX capacity in bytes
 * @param rxBase        base address of the transport to Java ring region
 * @param rxTotalBytes  total mapped RX size ({@code header + capacity})
 * @param rxCapacity    usable RX capacity in bytes
 */
public record FfmSharedIoRegion(
        int structSize,
        int flags,
        long layoutVersion,
        long txBase,
        long txTotalBytes,
        long txCapacity,
        long rxBase,
        long rxTotalBytes,
        long rxCapacity
) {

    public static final int FLAG_RUST_OWNED = 1 << 0;
    public static final int KICK_TX_DATA = 1 << 0;
    public static final int KICK_RX_SPACE = 1 << 1;

    public static final long LAYOUT_VERSION_1_0 = 1L << 32;
    public static final long RING_HEADER_BYTES = 192L;
    public static final long RING_ALIGNMENT = 128L;
    public static final long RING_MIN_CAPACITY = 64L * 1024;

    private static final int MIN_STRUCT_SIZE = 64;

    public static FfmSharedIoRegion decode(MemorySegment segment) {
        var layout = FfmApiLayouts.SHARED_IO_REGION_V1;
        var value = new FfmSharedIoRegion(
                segment.get(
                        ValueLayout.JAVA_INT,
                        layout.byteOffset(MemoryLayout.PathElement.groupElement("struct_size"))
                ),
                segment.get(
                        ValueLayout.JAVA_INT,
                        layout.byteOffset(MemoryLayout.PathElement.groupElement("flags"))
                ),
                segment.get(
                        ValueLayout.JAVA_LONG,
                        layout.byteOffset(MemoryLayout.PathElement.groupElement("layout_version"))
                ),
                segment.get(
                        ValueLayout.ADDRESS,
                        layout.byteOffset(MemoryLayout.PathElement.groupElement("tx_base"))
                ).address(),
                segment.get(
                        ValueLayout.JAVA_LONG,
                        layout.byteOffset(MemoryLayout.PathElement.groupElement("tx_total_bytes"))
                ),
                segment.get(
                        ValueLayout.JAVA_LONG,
                        layout.byteOffset(MemoryLayout.PathElement.groupElement("tx_capacity"))
                ),
                segment.get(
                        ValueLayout.ADDRESS,
                        layout.byteOffset(MemoryLayout.PathElement.groupElement("rx_base"))
                ).address(),
                segment.get(
                        ValueLayout.JAVA_LONG,
                        layout.byteOffset(MemoryLayout.PathElement.groupElement("rx_total_bytes"))
                ),
                segment.get(
                        ValueLayout.JAVA_LONG,
                        layout.byteOffset(MemoryLayout.PathElement.groupElement("rx_capacity"))
                )
        );
        value.validate();
        return value;
    }

    private void validate() {
        if (structSize < MIN_STRUCT_SIZE) {
            throw new IllegalStateException(
                    "Shared IO descriptor too small: struct_size=" + structSize
            );
        }
        if (!rustOwned()) {
            throw new IllegalStateException(
                    "Shared IO descriptor is not flagged Rust-owned: flags=" + flags
            );
        }
        if (layoutVersion != LAYOUT_VERSION_1_0) {
            throw new IllegalStateException(
                    "Unsupported shared IO layout version: " + Long.toHexString(layoutVersion)
            );
        }
        validateRegion("tx", txBase, txTotalBytes, txCapacity);
        validateRegion("rx", rxBase, rxTotalBytes, rxCapacity);
    }

    public boolean rustOwned() {
        return (flags & FLAG_RUST_OWNED) != 0;
    }

    private static void validateRegion(
            String name,
            long base,
            long totalBytes,
            long capacity
    ) {
        if (base == 0) {
            throw new IllegalStateException("%s ring base address is null".formatted(name));
        }
        if (base % RING_ALIGNMENT != 0) {
            throw new IllegalStateException(
                    "%s ring base is not %d-byte aligned: %d".formatted(name, RING_ALIGNMENT, base)
            );
        }
        if (capacity < RING_MIN_CAPACITY) {
            throw new IllegalStateException(
                    "%s ring capacity below minimum: %d".formatted(name, capacity)
            );
        }
        if (Long.bitCount(capacity) != 1) {
            throw new IllegalStateException(
                    "%s ring capacity is not a power of two: %d".formatted(name, capacity)
            );
        }
        if (totalBytes != RING_HEADER_BYTES + capacity) {
            throw new IllegalStateException(
                    "%s ring total_bytes=%d does not match header+capacity=%d".formatted(
                            name,
                            totalBytes,
                            RING_HEADER_BYTES + capacity
                    )
            );
        }
    }

}
