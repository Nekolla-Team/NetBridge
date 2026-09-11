package top.tangge233.netbridge.benchmark.channel

import top.tangge233.netbridge.nativebridge.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicLong

class FakeNativeConnection(
    private val connectionId: Long
) : NativeConnection {

    private val writtenBytesCounter = AtomicLong()
    private val readBytesCounter = AtomicLong()
    private val wouldBlockWritesCounter = AtomicLong()

    private val stateLock = Any()
    private val inboundQueue = ArrayDeque<ByteArray>()
    private var stateValue = NativeConnectionState.CONNECTING
    private var listener: NativeConnectionListener? = null
    private var writeWouldBlock = false
    private var endlessRead = false
    private var endlessPattern: ByteArray? = null
    private var endlessPatternLength = 0

    constructor(id: Int) : this(id.toLong())

    override fun id(): Long = connectionId

    override fun transport(): NativeTransportKind = NativeTransportKind.QUIC

    override fun state(): NativeConnectionState =
        synchronized(stateLock) {
            stateValue
        }

    override fun remoteAddress(): InetSocketAddress =
        InetSocketAddress("127.0.0.1", 1)

    override fun write(source: ByteBuffer): NativeIoResult {
        if (writeWouldBlock) {
            wouldBlockWritesCounter.incrementAndGet()
            return NativeIoResult.WOULD_BLOCK
        }

        val bytes = source.remaining()
        writtenBytesCounter.addAndGet(bytes.toLong())
        source.position(source.limit())
        return NativeIoResult.progressed(bytes)
    }

    override fun read(target: ByteBuffer): NativeIoResult {
        val endless = endlessPattern
        if (endlessRead && endless != null) {
            val n = minOf(target.remaining(), endlessPatternLength)
            target.put(endless, 0, n)
            readBytesCounter.addAndGet(n.toLong())
            return NativeIoResult.progressed(n)
        }

        val head = synchronized(inboundQueue) {
            inboundQueue.peekFirst()
        }
        if (head == null) {
            return NativeIoResult.WOULD_BLOCK
        }

        val n = minOf(target.remaining(), head.size)
        target.put(head, 0, n)
        readBytesCounter.addAndGet(n.toLong())
        synchronized(inboundQueue) {
            val still = inboundQueue.peekFirst()
            if (still === head) {
                if (n == head.size) {
                    inboundQueue.pollFirst()
                } else {
                    val rest = head.copyOfRange(n, head.size)
                    inboundQueue.pollFirst()
                    inboundQueue.addFirst(rest)
                }
            }
        }
        return NativeIoResult.progressed(n)
    }

    override fun setListener(listener: NativeConnectionListener) {
        val current = synchronized(stateLock) {
            this.listener = listener
            stateValue
        }
        if (current == NativeConnectionState.CONNECTED) {
            listener.onStateChanged(NativeConnectionState.CONNECTED)
        }
    }

    override fun close() {
        setState(NativeConnectionState.CLOSED)
    }

    fun setState(next: NativeConnectionState) {
        val notify = synchronized(stateLock) {
            stateValue = next
            listener
        }
        notify?.onStateChanged(next)
    }

    fun connect() {
        setState(NativeConnectionState.CONNECTED)
    }

    fun fail() {
        setState(NativeConnectionState.FAILED)
    }

    fun setWriteWouldBlock(enabled: Boolean) {
        this.writeWouldBlock = enabled
    }

    fun wouldBlockWrites(): Long = wouldBlockWritesCounter.get()

    fun makeWritable() {
        val notify = synchronized(stateLock) {
            listener
        }
        notify?.onWritable()
    }

    fun push(data: ByteArray) {
        val notify = synchronized(stateLock) {
            listener
        }
        synchronized(inboundQueue) {
            inboundQueue.addLast(data.clone())
        }
        notify?.onDataAvailable()
    }

    fun setEndlessRead(pattern: ByteArray, chunkBytes: Int) {
        this.endlessRead = true
        this.endlessPattern = pattern
        this.endlessPatternLength = minOf(chunkBytes, pattern.size)
    }

    fun setEndlessRead(enabled: Boolean) {
        this.endlessRead = enabled
    }

    fun writtenBytes(): Long = writtenBytesCounter.get()

}
