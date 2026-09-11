package top.tangge233.netbridge.benchmark.transport

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.ChannelInputShutdownReadComplete
import java.util.concurrent.atomic.AtomicBoolean

class BenchServerHandler : ChannelInboundHandlerAdapter() {

    private val backStreamStarted = AtomicBoolean(false)
    private val backStreamPending = AtomicBoolean(false)
    private val corruptFrameObservedFlag = AtomicBoolean(false)
    private var backStreamPayload: Int = 0
    private var appBytesReceived: Long = 0L
    private var serverSeq: Long = 0L
    private var closing: Boolean = false

    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any?
    ) {
        require(msg is ByteBuf) { "expected ByteBuf frame" }

        try {
            val type = TransportPayloadCodec.readType(msg)
            val seq = TransportPayloadCodec.readSequence(msg)
            when (type) {
                FrameType.PING -> ctx.writeAndFlush(msg.retain())

                FrameType.DATA -> handleData(
                    ctx,
                    msg,
                    seq
                )

                FrameType.REPORT_REQUEST -> ctx.writeAndFlush(
                    TransportPayloadCodec.encodeReportResponse(
                        ctx.alloc(),
                        seq,
                        appBytesReceived
                    )
                )

                FrameType.START_BIDI -> {
                    val payload = TransportPayloadCodec.readBidiPayloadBytes(msg)
                    if (backStreamStarted.compareAndSet(false, true)) {
                        ctx.channel().eventLoop().execute {
                            streamBack(ctx, payload)
                        }
                    }
                }

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

    private fun handleData(
        ctx: ChannelHandlerContext,
        frame: ByteBuf,
        seq: Long
    ) {
        val payload = frame.readableBytes() - TransportPayloadCodec.HEADER_BYTES
        if (payload < 0) {
            corruptFrameObservedFlag.set(true)
            return
        }

        appBytesReceived += payload
        if (!TransportPayloadCodec.verifyPayload(frame, seq)) {
            corruptFrameObservedFlag.set(true)
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.channel().isWritable
            && backStreamPending.compareAndSet(true, false)
        ) {
            val payload = backStreamPayload
            ctx.channel().eventLoop().execute {
                streamBack(ctx, payload)
            }
        }
        ctx.fireChannelWritabilityChanged()
    }

    private fun streamBack(
        ctx: ChannelHandlerContext,
        payloadBytes: Int
    ) {
        if (closing || !ctx.channel().isActive) {
            return
        }

        if (!ctx.channel().isWritable) {
            backStreamPayload = payloadBytes
            backStreamPending.set(true)
            return
        }

        val alloc = ctx.alloc()
        val seq = ++serverSeq
        val frame = TransportPayloadCodec.encodeFrame(
            alloc,
            FrameType.DATA,
            seq,
            payloadBytes
        )

        ctx.writeAndFlush(frame).addListener {
            if (ctx.channel().isActive) {
                ctx.channel().eventLoop().execute {
                    streamBack(ctx, payloadBytes)
                }
            }
        }
    }

}
