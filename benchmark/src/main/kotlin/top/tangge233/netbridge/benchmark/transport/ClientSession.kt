package top.tangge233.netbridge.benchmark.transport

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val WRITE_WINDOW = 16
private const val DEFAULT_PING_TIMEOUT_MILLIS = 5_000L
private const val ACK_TIMEOUT_MILLIS = 5_000L
private const val ACK_WINDOW_BYTES = 4L * 1024 * 1024

class ClientSession private constructor(
    val channel: Channel,
    private val pendingPings: MutableMap<Long, PendingPing>,
    private val pendingReports: MutableMap<Long, CompletableFuture<SessionStats>>,
    private val pendingStreamStops: MutableMap<Long, CompletableFuture<SessionStats>>,
    private val pendingServerSends: MutableMap<Long, CompletableFuture<ServerSendResult>>,
    private val latestStats: AtomicReference<SessionStats>,
    private val inboundDataHook: AtomicReference<((Long) -> Unit)?>,
    private val sentBytesCounter: AtomicLong,
    private val receivedBytesCounter: AtomicLong,
    private val corruptFramesCounter: AtomicLong,
    private val disorderCounter: AtomicLong
) : AutoCloseable {

    private val streamStop = AtomicBoolean(true)
    private val streamFinished = AtomicBoolean(true)
    private val nextControlSeq = AtomicLong(1)
    private val nextDataSeq = AtomicLong(1)

    @Volatile
    private var streamOutcome: CompletableFuture<StreamOutcome> =
        CompletableFuture.completedFuture(
            StreamOutcome(0L, 0L, StreamStatus.OK)
        )
    private var streamPayload: Int = 0
    private var outstanding: Int = 0
    private var bytesSinceAck: Long = 0L
    private var awaitingAck: Boolean = false
    private var streamStartNanos: Long = 0L
    private var streamEndNanos: Long = 0L
    private var streamSentBytes: Long = 0L

    @Volatile
    private var streamError: String? = null

    val serverStats: SessionStats
        get() = latestStats.get()

    /** Installs (or clears) a callback invoked with each inbound DATA frame's arrival nanoTime. */
    fun onInboundData(hook: ((Long) -> Unit)?) {
        inboundDataHook.set(hook)
    }

    /**
     * Writes one workload DATA frame without blocking, using the session's monotonic data
     * sequence space. Returns the Netty write future so callers can bound outstanding writes.
     */
    fun writeWorkloadData(payloadBytes: Int): io.netty.channel.ChannelFuture {
        val seq = nextDataSequence()
        sentBytesCounter.addAndGet(payloadBytes.toLong())
        return channel.writeAndFlush(
            TransportPayloadCodec.encodeFrame(
                channel.alloc(),
                FrameType.DATA,
                seq,
                payloadBytes
            )
        )
    }

    val clientSentBytes: Long
        get() = sentBytesCounter.get()

    val clientReceivedBytes: Long
        get() = receivedBytesCounter.get()

    val corruptInboundFrames: Long
        get() = corruptFramesCounter.get()

    val disorderEvents: Long
        get() = disorderCounter.get()

    fun ping(payloadBytes: Int): Long {
        return try {
            pingAsync(payloadBytes).get(
                DEFAULT_PING_TIMEOUT_MILLIS + 1_000L,
                TimeUnit.MILLISECONDS
            )
        } catch (e: TimeoutException) {
            throw IllegalStateException("ping timed out (transport stalled)", e)
        } catch (e: ExecutionException) {
            throw IllegalStateException("ping failed", e.cause)
        }
    }

    /**
     * Issues a ping without blocking; the returned future completes with the round-trip nanos.
     * Callers may keep multiple pings in flight on an absolute schedule to avoid coordinated
     * omission (B-013).
     */
    fun pingAsync(payloadBytes: Int): CompletableFuture<Long> {
        val seq = nextControlSequence()
        val sentNanos = System.nanoTime()
        val pending = PendingPing(sentNanos)
        pending.timeoutTask = channel.eventLoop().schedule({
            if (pendingPings.remove(seq) != null) {
                pending.response.completeExceptionally(
                    IllegalStateException("ping timed out (transport stalled)")
                )
            }
        }, DEFAULT_PING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        pendingPings[seq] = pending

        channel.writeAndFlush(
            TransportPayloadCodec.encodeFrame(
                channel.alloc(),
                FrameType.PING,
                seq,
                payloadBytes
            )
        ).addListener { future ->
            if (!future.isSuccess) {
                pendingPings.remove(seq)
                pending.timeoutTask?.cancel(false)
                pending.response.completeExceptionally(future.cause())
            }
        }

        return pending.response
    }

    private fun nextControlSequence(): Long = nextControlSeq.getAndIncrement()

    private fun nextDataSequence(): Long = nextDataSeq.getAndIncrement()

    fun startStreaming(payloadBytes: Int): CompletableFuture<StreamOutcome> {
        check(streamFinished.get()) { "stream already running" }

        streamPayload = payloadBytes
        streamStop.set(false)
        streamFinished.set(false)
        bytesSinceAck = 0L
        awaitingAck = false
        outstanding = 0
        streamSentBytes = 0L
        streamError = null
        streamStartNanos = System.nanoTime()

        val outcome = CompletableFuture<StreamOutcome>()
        streamOutcome = outcome
        channel.eventLoop().execute { pump() }
        return outcome
    }

    fun stopStreaming() {
        streamStop.set(true)
        channel.eventLoop().execute { pump() }
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
        val seq = nextDataSequence()
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
                    failStream("data write failed: ${future.cause()}")
                }
            }
        } catch (t: Throwable) {
            outstanding--
            frame.release()
            failStream("data write threw: $t")
        }
    }

    private fun sendStreamAck() {
        if (awaitingAck || streamFinished.get()) {
            return
        }

        awaitingAck = true
        bytesSinceAck = 0L
        val seq = nextControlSequence()
        val pending = PendingPing(System.nanoTime())
        pending.onAck = {
            awaitingAck = false
            pump()
        }
        pending.timeoutTask = channel.eventLoop().schedule({
            if (pendingPings.remove(seq) != null) {
                failStream("stream ACK timed out")
            }
        }, ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        pendingPings[seq] = pending

        channel.writeAndFlush(
            TransportPayloadCodec.encodeFrame(
                channel.alloc(),
                FrameType.PING,
                seq,
                0
            )
        ).addListener { future ->
            if (!future.isSuccess) {
                pendingPings.remove(seq)
                pending.timeoutTask?.cancel(false)
                failStream("stream ACK write failed: ${future.cause()}")
            }
        }
    }

    private fun failStream(reason: String) {
        if (streamError == null) {
            streamError = reason
        }
        streamStop.set(true)
        channel.eventLoop().execute { pump() }
    }

    private fun finishStream() {
        if (streamFinished.getAndSet(true)) {
            return
        }

        streamEndNanos = System.nanoTime()
        val error = streamError
        streamOutcome.complete(
            StreamOutcome(
                streamSentBytes,
                streamEndNanos - streamStartNanos,
                if (error == null) StreamStatus.OK else StreamStatus.FAILED,
                error
            )
        )
    }

    fun requestReport(): CompletableFuture<SessionStats> {
        val seq = nextControlSequence()
        val future = CompletableFuture<SessionStats>()

        pendingReports[seq] = future
        channel.writeAndFlush(
            TransportPayloadCodec.encodeFrame(
                channel.alloc(),
                FrameType.REPORT_REQUEST,
                seq,
                0
            )
        ).addListener { f ->
            if (!f.isSuccess) {
                pendingReports.remove(seq)
                future.completeExceptionally(f.cause())
            }
        }
        return future
    }

    fun startServerStream(payloadBytes: Int) {
        channel.writeAndFlush(
            TransportPayloadCodec.encodeIntPayload(
                channel.alloc(),
                FrameType.STREAM_START,
                nextControlSequence(),
                payloadBytes
            )
        )
    }

    fun stopServerStream(): CompletableFuture<SessionStats> {
        val seq = nextControlSequence()
        val future = CompletableFuture<SessionStats>()

        pendingStreamStops[seq] = future
        channel.writeAndFlush(
            TransportPayloadCodec.encodeFrame(
                channel.alloc(),
                FrameType.STREAM_STOP,
                seq,
                0
            )
        ).addListener { f ->
            if (!f.isSuccess) {
                pendingStreamStops.remove(seq)
                future.completeExceptionally(f.cause())
            }
        }
        return future
    }

    /**
     * Asks the server to emit a burst of [count] frames of [payloadBytes] bytes on an
     * absolute [intervalNanos] schedule and resolves once the server reports completion.
     */
    fun serverSend(
        payloadBytes: Int,
        count: Int,
        intervalNanos: Long
    ): CompletableFuture<ServerSendResult> {
        val seq = nextControlSequence()
        val future = CompletableFuture<ServerSendResult>()

        pendingServerSends[seq] = future
        channel.writeAndFlush(
            TransportPayloadCodec.encodeServerSend(
                channel.alloc(),
                seq,
                payloadBytes,
                count,
                intervalNanos
            )
        ).addListener { f ->
            if (!f.isSuccess) {
                pendingServerSends.remove(seq)
                future.completeExceptionally(f.cause())
            }
        }
        return future
    }

    override fun close() {
        streamStop.set(true)
        runCatching { channel.close().syncUninterruptibly() }
    }

    data class StreamOutcome(
        val bytesWritten: Long,
        val wallNanos: Long,
        val status: StreamStatus,
        val error: String? = null
    ) {

        val failed: Boolean
            get() = status == StreamStatus.FAILED

    }

    enum class StreamStatus {

        OK,
        FAILED

    }

    private class PendingPing(
        val sentNanos: Long
    ) {

        val response: CompletableFuture<Long> = CompletableFuture()

        @Volatile
        var onAck: (() -> Unit)? = null

        @Volatile
        var timeoutTask: ScheduledFuture<*>? = null

    }

    private class InboundHandler(
        private val pendingPings: MutableMap<Long, PendingPing>,
        private val pendingReports: MutableMap<Long, CompletableFuture<SessionStats>>,
        private val pendingStreamStops: MutableMap<Long, CompletableFuture<SessionStats>>,
        private val pendingServerSends: MutableMap<Long, CompletableFuture<ServerSendResult>>,
        private val latestStats: AtomicReference<SessionStats>,
        private val inboundDataHook: AtomicReference<((Long) -> Unit)?>,
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
                    FrameType.PONG -> onPong(seq, msg)

                    FrameType.DATA -> onData(seq, msg)

                    FrameType.REPORT_RESPONSE -> {
                        val stats = TransportPayloadCodec.readStats(msg)
                        latestStats.set(stats)
                        pendingReports.remove(seq)?.complete(stats)
                    }

                    FrameType.STREAM_STOPPED -> {
                        val stats = TransportPayloadCodec.readStats(msg)
                        latestStats.set(stats)
                        pendingStreamStops.remove(seq)?.complete(stats)
                    }

                    FrameType.SERVER_SEND_COMPLETE -> {
                        val result = TransportPayloadCodec.readServerSendComplete(msg)
                        pendingServerSends.remove(seq)?.complete(result)
                    }

                    else -> {
                    }
                }
            } finally {
                msg.release()
            }
        }

        private fun onPong(seq: Long, frame: ByteBuf) {
            val pending = pendingPings.remove(seq) ?: return

            pending.timeoutTask?.cancel(false)
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
            inboundDataHook.get()?.invoke(System.nanoTime())
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
            val reports: MutableMap<Long, CompletableFuture<SessionStats>> = ConcurrentHashMap()
            val stops: MutableMap<Long, CompletableFuture<SessionStats>> = ConcurrentHashMap()
            val serverSends: MutableMap<Long, CompletableFuture<ServerSendResult>> =
                ConcurrentHashMap()
            val latestStats = AtomicReference(SessionStats.EMPTY)
            val inboundHook = AtomicReference<((Long) -> Unit)?>(null)
            val sent = AtomicLong()
            val received = AtomicLong()
            val corrupt = AtomicLong()
            val disorder = AtomicLong()
            val handler = InboundHandler(
                pending,
                reports,
                stops,
                serverSends,
                latestStats,
                inboundHook,
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
                reports,
                stops,
                serverSends,
                latestStats,
                inboundHook,
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
