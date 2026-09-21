package team.nekolla.netbridge.benchmark.transport

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.ChannelInputShutdownReadComplete
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class BenchServerHandler : ChannelInboundHandlerAdapter() {

    // Inbound (client -> server) accounting.
    private var appBytesReceived: Long = 0L
    private var framesReceived: Long = 0L
    private var corruptFrames: Long = 0L
    private var disorderEvents: Long = 0L
    private var firstInboundSequence: Long = -1L
    private var lastInboundSequence: Long = -1L

    // Outbound reverse stream (server -> client) accounting.
    private var reversePayload: Int = 0
    private var reverseStarted: Boolean = false
    private var reverseStopRequested: Boolean = false
    private var reverseWriting: Boolean = false
    private var reverseEnded: Boolean = false
    private var sentAppBytes: Long = 0L
    private var framesSent: Long = 0L
    private var failedWrites: Long = 0L
    private var reverseStartNanos: Long = -1L
    private var reverseEndNanos: Long = -1L
    private var stopSeq: Long = -1L
    private var serverSeq: Long = 0L
    private var closing: Boolean = false

    // Server-driven burst (SERVER_SEND) accounting.
    private var serverSendActive: Boolean = false
    private var serverSendRequestSeq: Long = -1L
    private var serverSendSpec: ServerSendSpec? = null
    private var serverSendIndex: Int = 0
    private var serverSendStartNanos: Long = -1L
    private var serverSendBytes: Long = 0L
    private var serverSendFrames: Long = 0L
    private var serverSendFailures: Long = 0L
    private var serverSendTask: ScheduledFuture<*>? = null
    private var serverSendWriting: Boolean = false

    private fun stats(): SessionStats = SessionStats(
        bytesReceived = appBytesReceived,
        framesReceived = framesReceived,
        bytesSent = sentAppBytes,
        framesSent = framesSent,
        corruptFrames = corruptFrames,
        disorderEvents = disorderEvents,
        firstSequence = firstInboundSequence,
        lastSequence = lastInboundSequence,
        startNanos = reverseStartNanos,
        endNanos = reverseEndNanos,
        failedWrites = failedWrites
    )

    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any?
    ) {
        require(msg is ByteBuf) { "expected ByteBuf frame" }

        try {
            val type = TransportPayloadCodec.readType(msg)
            val seq = TransportPayloadCodec.readSequence(msg)
            when (type) {
                FrameType.PING -> {
                    // Echo the probe back as an explicit PONG (same seq + payload).
                    TransportPayloadCodec.rewritePingAsPong(msg)
                    ctx.writeAndFlush(msg.retain())
                }

                FrameType.DATA -> handleData(msg, seq)

                FrameType.REPORT_REQUEST -> ctx.writeAndFlush(
                    TransportPayloadCodec.encodeStats(
                        ctx.alloc(),
                        FrameType.REPORT_RESPONSE,
                        seq,
                        stats()
                    )
                )

                FrameType.STREAM_START -> {
                    val payload = TransportPayloadCodec.readIntPayload(msg)
                    startReverseStream(ctx, payload)
                }

                FrameType.STREAM_STOP -> {
                    reverseStopRequested = true
                    stopSeq = seq
                    if (!reverseWriting) {
                        finishReverseStream(ctx, sendAck = true)
                    }
                }

                FrameType.SERVER_SEND -> handleServerSend(
                    ctx,
                    seq,
                    TransportPayloadCodec.readServerSend(msg)
                )

                FrameType.BYE -> {
                    closing = true
                    ctx.close()
                }

                else -> error("unexpected frame $type")
            }
        } finally {
            msg.release()
        }
    }

    override fun userEventTriggered(
        ctx: ChannelHandlerContext,
        evt: Any?
    ) {
        if (evt is ChannelInputShutdownReadComplete) {
            ctx.close()
        } else {
            ctx.fireUserEventTriggered(evt)
        }
    }

    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable
    ) {
        ctx.close()
    }

    private fun handleServerSend(
        ctx: ChannelHandlerContext,
        requestSeq: Long,
        spec: ServerSendSpec
    ) {
        if (serverSendActive) {
            ctx.writeAndFlush(
                TransportPayloadCodec.encodeServerSendComplete(
                    ctx.alloc(),
                    requestSeq,
                    0L,
                    0L,
                    1L
                )
            )
            return
        }
        serverSendActive = true
        serverSendRequestSeq = requestSeq
        serverSendSpec = spec
        serverSendIndex = 0
        serverSendBytes = 0L
        serverSendFrames = 0L
        serverSendFailures = 0L
        serverSendStartNanos = System.nanoTime()
        scheduleNextServerSend(ctx)
    }

    private fun scheduleNextServerSend(ctx: ChannelHandlerContext) {
        val spec = serverSendSpec ?: return
        if (closing || !ctx.channel().isActive) {
            finishServerSend(ctx, ack = false)
            return
        }
        if (serverSendIndex >= spec.count) {
            finishServerSend(ctx, ack = true)
            return
        }
        val deadline = serverSendStartNanos +
                spec.intervalNanos * serverSendIndex.toLong()
        val delay = deadline - System.nanoTime()
        if (delay > 0L) {
            serverSendTask = ctx.channel().eventLoop().schedule({
                sendServerFrame(ctx)
            }, delay, TimeUnit.NANOSECONDS)
        } else {
            sendServerFrame(ctx)
        }
    }

    private fun sendServerFrame(ctx: ChannelHandlerContext) {
        val spec = serverSendSpec ?: return
        if (closing || !ctx.channel().isActive) {
            finishServerSend(ctx, ack = false)
            return
        }

        val seq = ++serverSeq
        val frame = TransportPayloadCodec.encodeFrame(
            ctx.alloc(),
            FrameType.DATA,
            seq,
            spec.payloadBytes
        )
        ctx.writeAndFlush(frame).addListener { future ->
            if (future.isSuccess) {
                serverSendBytes += spec.payloadBytes.toLong()
                serverSendFrames++
            } else {
                serverSendFailures++
            }
            serverSendIndex++
            scheduleNextServerSend(ctx)
        }
    }

    private fun finishServerSend(ctx: ChannelHandlerContext, ack: Boolean) {
        if (!serverSendActive) {
            return
        }
        serverSendTask?.cancel(false)
        serverSendTask = null
        serverSendActive = false
        sentAppBytes += serverSendBytes
        framesSent += serverSendFrames
        failedWrites += serverSendFailures

        if (ack && ctx.channel().isActive) {
            ctx.writeAndFlush(
                TransportPayloadCodec.encodeServerSendComplete(
                    ctx.alloc(),
                    serverSendRequestSeq,
                    serverSendBytes,
                    serverSendFrames,
                    serverSendFailures
                )
            )
        }
        serverSendRequestSeq = -1L
        serverSendSpec = null
    }

    private fun handleData(frame: ByteBuf, seq: Long) {
        val payload = frame.readableBytes() - TransportPayloadCodec.HEADER_BYTES
        if (payload < 0) {
            corruptFrames++
            return
        }

        framesReceived++
        if (firstInboundSequence < 0) {
            firstInboundSequence = seq
        }
        if (lastInboundSequence >= 0 && seq != lastInboundSequence + 1) {
            disorderEvents++
        }
        lastInboundSequence = seq

        appBytesReceived += payload
        if (!TransportPayloadCodec.verifyPayload(frame, seq)) {
            corruptFrames++
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.channel().isWritable
            && reverseStarted
            && !reverseEnded
            && !reverseStopRequested
        ) {
            streamReverse(ctx)
        }
        ctx.fireChannelWritabilityChanged()
    }

    private fun startReverseStream(
        ctx: ChannelHandlerContext,
        payloadBytes: Int
    ) {
        if (reverseStarted) {
            return
        }
        reverseStarted = true
        reversePayload = payloadBytes
        reverseStartNanos = System.nanoTime()
        ctx.channel().eventLoop().execute { streamReverse(ctx) }
    }

    private fun streamReverse(ctx: ChannelHandlerContext) {
        if (closing
            || !ctx.channel().isActive
            || reverseStopRequested
            || reverseEnded
        ) {
            finishReverseStream(ctx, sendAck = true)
            return
        }
        if (reverseWriting) {
            return
        }
        if (!ctx.channel().isWritable) {
            // Resume from channelWritabilityChanged once the channel accepts writes again.
            return
        }

        reverseWriting = true
        val seq = ++serverSeq
        val frame = TransportPayloadCodec.encodeFrame(
            ctx.alloc(),
            FrameType.DATA,
            seq,
            reversePayload
        )

        ctx.writeAndFlush(frame).addListener { future ->
            reverseWriting = false
            if (future.isSuccess) {
                sentAppBytes += reversePayload.toLong()
                framesSent++
            } else {
                failedWrites++
                reverseStopRequested = true
            }

            if (reverseStopRequested || closing || !ctx.channel().isActive) {
                finishReverseStream(ctx, sendAck = true)
            } else {
                ctx.channel().eventLoop().execute { streamReverse(ctx) }
            }
        }
    }

    private fun finishReverseStream(
        ctx: ChannelHandlerContext,
        sendAck: Boolean
    ) {
        if (reverseEnded) {
            return
        }
        reverseEnded = true
        reverseEndNanos = System.nanoTime()

        if (sendAck && stopSeq >= 0 && ctx.channel().isActive) {
            ctx.writeAndFlush(
                TransportPayloadCodec.encodeStats(
                    ctx.alloc(),
                    FrameType.STREAM_STOPPED,
                    stopSeq,
                    stats()
                )
            )
        }
    }

}
