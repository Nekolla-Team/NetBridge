package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.*;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * The single translator between C ABI values and semantic types.
 *
 * <p>Rules: all ABI constants remain in the FFM internal layer; unknown connection/server
 * states must never be silently mapped to a healthy state; unknown failure reasons are mapped to
 * the semantic {@code GENERIC} value (consistent with the existing public contract); invalid raw
 * events are rejected at the upcall boundary.
 */
public final class FfmAbiCodec {

    public static final int TRANSPORT_QUIC = 1;
    public static final int TRANSPORT_KCP = 2;

    public static final int CONNECTION_STATE_CONNECTING = 1;
    public static final int CONNECTION_STATE_CONNECTED = 2;
    public static final int CONNECTION_STATE_CLOSED = 3;
    public static final int CONNECTION_STATE_FAILED = 4;

    public static final int EVENT_CONNECTION_STATE = 1;
    public static final int EVENT_DATA_AVAILABLE = 2;
    public static final int EVENT_WRITABLE = 3;
    public static final int EVENT_ACCEPTED = 4;
    public static final int EVENT_SERVER_STATE = 5;

    public static final int SERVER_STATE_RUNNING = 1;
    public static final int SERVER_STATE_STOPPED = 2;
    public static final int SERVER_STATE_FAILED = 3;

    public static final int KCP_PROFILE_BALANCED = 0;
    public static final int KCP_PROFILE_AGGRESSIVE = 2;

    private FfmAbiCodec() {
    }

    public static int transportToAbi(NativeTransportKind transport) {
        return switch (transport) {
            case QUIC -> TRANSPORT_QUIC;
            case KCP -> TRANSPORT_KCP;
        };
    }

    public static int kcpProfileToAbi(NativeKcpProfile profile) {
        return switch (profile) {
            case BALANCED -> KCP_PROFILE_BALANCED;
            case AGGRESSIVE -> KCP_PROFILE_AGGRESSIVE;
        };
    }

    /**
     * Decodes raw FFM upcall callback parameters into a typed event; invalid combinations throw
     * {@link IllegalArgumentException}.
     */
    public static NativeEvent decodeEvent(
            int eventKind,
            long objectId,
            long arg0,
            long arg1
    ) {
        return switch (eventKind) {
            case EVENT_CONNECTION_STATE -> new NativeEvent.ConnectionStateChanged(
                    objectId,
                    connectionStateFromAbi((int) arg0),
                    failureReasonFromCode(arg1)
            );
            case EVENT_DATA_AVAILABLE -> new NativeEvent.DataAvailable(objectId);
            case EVENT_WRITABLE -> new NativeEvent.Writable(objectId);
            case EVENT_ACCEPTED -> new NativeEvent.Accepted(
                    objectId,
                    arg0
            );
            case EVENT_SERVER_STATE -> new NativeEvent.ServerStateChanged(
                    objectId,
                    serverStateFromAbi((int) arg0)
            );
            default -> throw new IllegalArgumentException(
                    "Unknown native event kind: " + eventKind
            );
        };
    }

    public static NativeConnectionState connectionStateFromAbi(int abi) {
        return switch (abi) {
            case CONNECTION_STATE_CONNECTING -> NativeConnectionState.CONNECTING;
            case CONNECTION_STATE_CONNECTED -> NativeConnectionState.CONNECTED;
            case CONNECTION_STATE_CLOSED -> NativeConnectionState.CLOSED;
            case CONNECTION_STATE_FAILED -> NativeConnectionState.FAILED;
            default -> throw new IllegalArgumentException(
                    "Unknown native connection state: " + abi
            );
        };
    }

    public static NativeFailureReason failureReasonFromCode(long code) {
        return switch ((int) code) {
            case 0 -> NativeFailureReason.GENERIC;
            case 1 -> NativeFailureReason.DNS;
            case 2 -> NativeFailureReason.SETUP;
            case 3 -> NativeFailureReason.REFUSED;
            case 4 -> NativeFailureReason.TIMEOUT;
            case 5 -> NativeFailureReason.PROTOCOL;
            case 6 -> NativeFailureReason.CANCELLED;
            case 7 -> NativeFailureReason.INTERNAL;
            default -> NativeFailureReason.GENERIC;
        };
    }

    /**
     * Decodes the C {@code socket_address_v1} struct stored in {@code outAddr} into a semantic
     * {@link InetSocketAddress}. Family 4 (IPv4) and 6 (IPv6, with scope id) are supported;
     * anything else is rejected rather than guessed.
     */
    public static InetSocketAddress decodeSocketAddress(MemorySegment outAddr) {
        var socket = FfmApiLayouts.SOCKET_ADDRESS_V1;
        var family = outAddr.get(
                ValueLayout.JAVA_INT,
                socket.byteOffset(MemoryLayout.PathElement.groupElement("family"))
        );
        var port = Short.toUnsignedInt(outAddr.get(
                ValueLayout.JAVA_SHORT,
                socket.byteOffset(MemoryLayout.PathElement.groupElement("port"))
        ));
        var scopeId = outAddr.get(
                ValueLayout.JAVA_INT,
                socket.byteOffset(MemoryLayout.PathElement.groupElement("scope_id"))
        );
        var addrOffset = socket.byteOffset(
                MemoryLayout.PathElement.groupElement("address")
        );

        InetAddress inet;
        try {
            if (family == 4) {
                var ipBytes = new byte[4];
                MemorySegment.copy(
                        outAddr,
                        addrOffset,
                        MemorySegment.ofArray(ipBytes),
                        0,
                        4
                );
                inet = InetAddress.getByAddress(ipBytes);
            } else if (family == 6) {
                var ipBytes = new byte[16];
                MemorySegment.copy(
                        outAddr,
                        addrOffset,
                        MemorySegment.ofArray(ipBytes),
                        0,
                        16
                );
                inet = scopeId != 0
                        ? Inet6Address.getByAddress(null, ipBytes, scopeId)
                        : InetAddress.getByAddress(ipBytes);
            } else {
                throw new NativeException("UNSUPPORTED_SOCKET_FAMILY: family=" + family);
            }
        } catch (UnknownHostException e) {
            throw new NativeException("Malformed socket address from native layer", e);
        }
        return new InetSocketAddress(inet, port);
    }

    public static NativeServerState serverStateFromAbi(int abi) {
        return switch (abi) {
            case SERVER_STATE_RUNNING -> NativeServerState.RUNNING;
            case SERVER_STATE_STOPPED -> NativeServerState.STOPPED;
            case SERVER_STATE_FAILED -> NativeServerState.FAILED;
            default -> throw new IllegalArgumentException(
                    "Unknown native server state: " + abi
            );
        };
    }

}
