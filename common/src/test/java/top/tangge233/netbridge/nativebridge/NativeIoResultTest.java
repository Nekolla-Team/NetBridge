package top.tangge233.netbridge.nativebridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeIoResultTest {

    @Test
    void negativeBytesRejectedForEveryStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeIoResult.progressed(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeIoResult(NativeIoStatus.WOULD_BLOCK, -1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeIoResult(NativeIoStatus.CLOSED, -1)
        );
    }

    @Test
    void wouldBlockAndClosedMustCarryZeroBytes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeIoResult(NativeIoStatus.WOULD_BLOCK, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeIoResult(NativeIoStatus.CLOSED, 1)
        );
    }

    @Test
    void wouldBlockAndClosedConstantsCarryZeroBytes() {
        assertEquals(NativeIoStatus.WOULD_BLOCK, NativeIoResult.WOULD_BLOCK.status());
        assertEquals(0, NativeIoResult.WOULD_BLOCK.bytes());
        assertEquals(NativeIoStatus.CLOSED, NativeIoResult.CLOSED.status());
        assertEquals(0, NativeIoResult.CLOSED.bytes());
    }

    @Test
    void progressedAllowsZeroBytes() {
        var empty = NativeIoResult.progressed(0);
        assertTrue(empty.progressed());
        assertEquals(0, empty.bytes());
    }

    @Test
    @SuppressWarnings({"NullAway", "DataFlowIssue"})
    void nullStatusRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new NativeIoResult(null, 0)
        );
    }

}
