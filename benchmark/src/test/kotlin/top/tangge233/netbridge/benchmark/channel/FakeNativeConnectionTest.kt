package top.tangge233.netbridge.benchmark.channel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.tangge233.netbridge.nativebridge.NativeConnectionListener
import top.tangge233.netbridge.nativebridge.NativeConnectionState
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class FakeNativeConnectionTest {

    @Test
    fun writeProgressesAndAdvancesPosition() {
        val conn = FakeNativeConnection(1)
        val src = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        val result = conn.write(src)
        assertTrue(result.progressed())
        assertEquals(4, result.bytes())
        assertEquals(4, src.position())
        assertEquals(4, conn.writtenBytes())
    }

    @Test
    fun wouldBlockUntilWritable() {
        val conn = FakeNativeConnection(1)
        val writableNotified = AtomicBoolean()
        conn.setListener(object : NativeConnectionListener {
            override fun onWritable() {
                writableNotified.set(true)
            }
        })
        conn.connect()
        conn.setWriteWouldBlock(true)

        val src = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        assertTrue(conn.write(src).wouldBlock())
        assertEquals(0, conn.writtenBytes())

        conn.setWriteWouldBlock(false)
        conn.makeWritable()
        assertTrue(writableNotified.get())

        val result = conn.write(src)
        assertTrue(result.progressed())
        assertEquals(3, conn.writtenBytes())
    }

    @Test
    fun connectNotifiesListenerAndStateChanges() {
        val conn = FakeNativeConnection(1)
        val notified = AtomicBoolean()
        conn.setListener(object : NativeConnectionListener {
            override fun onStateChanged(state: NativeConnectionState) {
                if (state == NativeConnectionState.CONNECTED) {
                    notified.set(true)
                }
            }
        })
        conn.connect()
        assertTrue(notified.get())
        assertEquals(NativeConnectionState.CONNECTED, conn.state())
    }

    @Test
    fun readEmptyIsWouldBlockThenPushProvidesData() {
        val conn = FakeNativeConnection(1)
        conn.connect()
        val target = ByteBuffer.allocate(4)
        assertTrue(conn.read(target).wouldBlock())

        conn.push(byteArrayOf(9, 8, 7, 6))
        val result = conn.read(target)
        assertTrue(result.progressed())
        assertEquals(4, result.bytes())
        assertEquals(9, target.array()[0])
    }

    @Test
    fun endlessReadNeverExhausts() {
        val conn = FakeNativeConnection(1)
        conn.connect()
        val pattern = ByteArray(1024)
        conn.setEndlessRead(pattern, 64)
        val targetA = ByteBuffer.allocate(64)
        val a = conn.read(targetA)
        val targetB = ByteBuffer.allocate(64)
        val b = conn.read(targetB)
        assertEquals(64, a.bytes())
        assertEquals(64, b.bytes())
    }

}
