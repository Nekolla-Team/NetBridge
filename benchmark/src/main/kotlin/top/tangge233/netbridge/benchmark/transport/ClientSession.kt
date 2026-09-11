package top.tangge233.netbridge.benchmark.transport

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val WRITE_WINDOW = 16
private const val DEFAULT_PING_TIMEOUT_MILLIS = 5_000L
private const val ACK_WINDOW_BYTES = 4L * 1024 * 1024

class ClientSession private constructor(
    val channel: Channel,
    private val pendingPings: MutableMap<Long, PendingPing>,
    private val reportResponse: CompletableFuture<Long>,
    private val sentBytesCounter: AtomicLong,
    private val receivedBytesCounter: AtomicLong,
    private val corruptFramesCounter: AtomicLong,
    private val disorderCounter: AtomicLong
) : AutoCloseable {

    private val streamStop = AtomicBoolean(true)
    private val streamFinished = AtomicBoolean(true)
    private val nextSeq = AtomicLong(1)

    @Volatile
    private var streamOutcome: CompletableFuture<StreamOutcome> =
        CompletableFuture.completedFuture(
            StreamOutcome(0L, 0L)
        )
    private var streamPayload: Int = 0
    private var outstanding: Int = 0
    private var bytesSinceAck: Long = 0L
    private var awaitingAck: Boolean = false
    private var streamStartNanos: Long = 0L
    private var streamEndNanos: Long = 0L
    private var streamSentBytes: Long = 0L

    val clientSentBytes: Long
        get() = sentBytesCounter.get()

    val clientReceivedBytes: Long
        get() = receivedBytesCounter.get()

    val corruptInboundFrames: Long
        get() = corruptFramesCounter.get()

    val disorderEvents: Long
        get() = disorderCounter.get()

    fun ping(payloadBytes: Int): Long {
        val seq = nextSequence()
        val sentNanos = System.nanoTime()
        val pending = PendingPing(sentNanos)

        pendingPings[seq] = pending
        channel.writeAndFlush(
            TransportPayloadCodec.encodeFrame(
                channel.alloc(),
                FrameType.PING,
                seq,
                payloadBytes
            )
        )

        return try {
            pending.response.get(
                DEFAULT_PING_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
            )
        } catch (e: TimeoutException) {
            pendingPings.remove(seq)
            throw IllegalStateException("ping timed out (transport stalled)", e)
        } catch (e: ExecutionException) {
            pendingPings.remove(seq)
            throw IllegalStateException("ping failed", e.cause)
        }
    }

    private fun nextSequence(): Long = nextSeq.getAndIncrement()

    fun startStreaming(payloadBytes: Int): CompletableFuture<StreamOutcome> {
        check(streamFinished.get()) { "stream already running" }

        streamPayload = payloadBytes
        streamStop.set(false)
        streamFinished.set(false)
        bytesSinceAck = 0L
        awaitingAck = false

        val outcome = CompletableFuture<StreamOutcome>()
        streamOutcome = outcome
        channel.eventLoop().execute { streamLoop() }
        return outcome
    }

    fun stopStreaming() {
        streamStop.set(true)
        channel.eventLoop().execute { pump() }
    }

    private fun streamLoop() {
        streamStartNanos = System.nanoTime()
        pump()
    }

    private fun pump() {
        if (streamFinished.get()) {
            return
        }

        var stopped = streamStop.get()
        var closed = !channel.isActive
        while (!stopped
            && !closed
            && outstanding < WRITE_WINDOW
            && !awaitingAck
        ) {
            sendNext()
            if (bytesSinceAck >= ACK_WINDOW_BYTES) {
                sendStreamAck()
            }
            stopped = streamStop.get()
            closed = !channel.isActive
        }

        if ((stopped || closed) && outstanding == 0) {
            finishStream()
        }
    }

    private fun sendNext() {
        val seq = nextSequence()
        outstanding++
        val payload = streamPayload
        streamSentBytes += payload
        bytesSinceAck += payload
        sentBytesCounter.addAndGet(payload.toLong())
        val frame = TransportPayloadCodec.encodeFrame(
            channel.alloc(),
            FrameType.DATA,
            seq,
            payload
        )
        try {
            channel.writeAndFlush(frame).addListener { future ->
                outstanding--
                if (future.isSuccess) {
                    pump()
                } else {
                    streamStop.set(true)
                    pump()
                }
            }
        } catch (_: Throwable) {
            outstanding--
            frame.release()
            streamStop.set(true)
            pump()
        }
    }

    private fun sendStreamAck() {
        if (awaitingAck || streamFinished.get()) {
            return
        }

        awaitingAck = true
        bytesSinceAck = 0L
        val seq = nextSequence()
        pendingPings[seq] = PendingPing(System.nanoTime()) {
            awaitingAck = false
            pump()
        }
        channel.writeAndFlush(
            TransportPayloadCodec.encodeFrame(
                channel.alloc(),
                FrameType.PING,
                seq,
                0
            )
        )
    }

    private fun finishStream() {
        if (streamFinished.getAndSet(true)) {
            return
        }

        streamEndNanos = System.nanoTime()
        streamOutcome.complete(
            StreamOutcome(
                streamSentBytes,
                streamEndNanos - streamStartNanos
            )
        )
    }

    fun requestReport(): CompletableFuture<Long> {
        channel.writeAndFlush(
            TransportPayloadCodec.encodeFrame(
                channel.alloc(),
                FrameType.REPORT_REQUEST,
                nextSequence(),
                0
            )
        )
        return reportResponse
    }

    fun startServerStream(payloadBytes: Int) {
        channel.writeAndFlush(
            TransportPayloadCodec.encodeStartBidi(
                channel.alloc(),
                nextSequence(),
                payloadBytes
            )
        )
    }

    override fun close() {
        streamStop.set(true)
        runCatching { channel.close().syncUninterruptibly() }
    }

    data class StreamOutcome(
        val bytesWritten: Long,
        val wallNanos: Long
    )

    private class PendingPing(
        val sentNanos: Long,
        val onAck: (() -> Unit)? = null
    ) {

        val response: CompletableFuture<Long> = CompletableFuture()

    }

    private class InboundHandler(
        private val pendingPings: MutableMap<Long, PendingPing>,
        private val reportResponse: CompletableFuture<Long>,
        private val clientReceivedBytes: AtomicLong,
        private val corruptInboundFrames: AtomicLong,
        private val disorderEvents: AtomicLong
    ) : ChannelInboundHandlerAdapter() {

        private var lastInboundSequence: Long = -1

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
            require(msg is ByteBuf) { "expected ByteBuf frame" }

            try {
                val type = TransportPayloadCodec.readType(msg)
                val seq = TransportPayloadCodec.readSequence(msg)
                when (type) {
                    FrameType.PING -> onPing(seq, msg)

                    FrameType.DATA -> onData(seq, msg)

                    FrameType.REPORT_RESPONSE -> reportResponse.complete(
                        TransportPayloadCodec.readReportBytes(msg)
                    )

                    else -> {
                    }
                }
            } finally {
                msg.release()
            }
        }

        private fun onPing(seq: Long, frame: ByteBuf) {
            val pending = pendingPings.remove(seq) ?: return

            val delta = System.nanoTime() - pending.sentNanos
            if (!TransportPayloadCodec.verifyPayload(frame, seq)) {
                corruptInboundFrames.incrementAndGet()
            }
            pending.response.complete(delta)
            pending.onAck?.invoke()
        }

        private fun onData(seq: Long, frame: ByteBuf) {
            val payload = frame.readableBytes() - TransportPayloadCodec.HEADER_BYTES
            if (payload < 0) {
                corruptInboundFrames.incrementAndGet()
                return
            }

            if (!TransportPayloadCodec.verifyPayload(frame, seq)) {
                corruptInboundFrames.incrementAndGet()
            }
            if (lastInboundSequence >= 0
                && seq != lastInboundSequence + 1
            ) {
                disorderEvents.incrementAndGet()
            }
            lastInboundSequence = seq
            clientReceivedBytes.addAndGet(payload.toLong())
        }

    }

    companion object {

        fun open(
            client: BenchClient,
            host: String,
            port: Int,
            timeoutMillis: Long
        ): ClientSession {
            val pending: MutableMap<Long, PendingPing> = ConcurrentHashMap()
            val report = CompletableFuture<Long>()
            val sent = AtomicLong()
            val received = AtomicLong()
            val corrupt = AtomicLong()
            val disorder = AtomicLong()
            val handler = InboundHandler(
                pending,
                report,
                received,
                corrupt,
                disorder
            )
            val future = client.connect(
                host,
                port,
                object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) {
                        ch.pipeline()
                                .addLast(TransportPayloadCodec.newDecoder())
                                .addLast(TransportPayloadCodec.newPrepender())
                                .addLast(handler)
                    }
                }
            )
            if (!future.awaitUninterruptibly(timeoutMillis, TimeUnit.MILLISECONDS)
                || !future.isSuccess
            ) {
                throw IllegalStateException(
                    "connect to $host:$port failed",
                    future.cause()
                )
            }

            val channel = future.channel()
            awaitActive(channel, timeoutMillis)
            return ClientSession(
                channel,
                pending,
                report,
                sent,
                received,
                corrupt,
                disorder
            )
        }

        private fun awaitActive(channel: Channel, timeoutMillis: Long) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!channel.isActive) {
                check(System.nanoTime() <= deadline) {
                    "channel never became active (transport connect failed?)"
                }
                Thread.onSpinWait()
            }
        }

    }

}
