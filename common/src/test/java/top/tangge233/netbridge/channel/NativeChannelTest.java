package top.tangge233.netbridge.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.nativebridge.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;

class NativeChannelTest {

    private final NioEventLoopGroup group = new NioEventLoopGroup(2);

    @AfterEach
    void shutdownGroup() {
        group.shutdownGracefully().syncUninterruptibly();
    }

    @Test
    void stateTransitionToConnectedActivatesChannel() throws Exception {
        var conn = new ScriptedConnection();
        var events = new Events();
        var channel = new NativeChannel(conn);
        channel.pipeline().addLast(events);
        group.register(channel).syncUninterruptibly();

        conn.transition(NativeConnectionState.CONNECTED);
        assertTrue(
                events.active.await(2, TimeUnit.SECONDS),
                "CONNECTED event should activate the channel"
        );
        assertTrue(channel.isActive());

        channel.close().syncUninterruptibly();
        assertTrue(events.inactive.await(2, TimeUnit.SECONDS));
    }

    @Test
    void dataAvailableDrivesRead() throws Exception {
        var conn = new ScriptedConnection();
        var events = new Events();
        var channel = new NativeChannel(conn);
        channel.pipeline().addLast(events);
        group.register(channel).syncUninterruptibly();
        conn.transition(NativeConnectionState.CONNECTED);
        assertTrue(events.active.await(2, TimeUnit.SECONDS));

        var payload = "native read payload".getBytes(StandardCharsets.UTF_8);
        conn.pushData(payload);
        assertTrue(
                events.read.await(2, TimeUnit.SECONDS),
                "DATA_AVAILABLE should drive reads"
        );
        assertArrayEquals(
                payload,
                events.readData.getFirst()
        );

        channel.close().syncUninterruptibly();
    }

    @Test
    void outboundWriteFlushesToConnection() throws Exception {
        var conn = new ScriptedConnection();
        var events = new Events();
        var channel = new NativeChannel(conn);
        channel.pipeline().addLast(events);
        group.register(channel).syncUninterruptibly();
        conn.transition(NativeConnectionState.CONNECTED);
        assertTrue(events.active.await(2, TimeUnit.SECONDS));

        var payload = "write me out".getBytes(StandardCharsets.UTF_8);
        channel.writeAndFlush(Unpooled.wrappedBuffer(payload)).syncUninterruptibly();
        assertArrayEquals(
                payload,
                conn.outboundPayload()
        );

        channel.close().syncUninterruptibly();
    }

    @Test
    void wouldBlockPausesUntilWritable() throws Exception {
        var conn = new ScriptedConnection();
        var events = new Events();
        var channel = new NativeChannel(conn);
        channel.pipeline().addLast(events);
        group.register(channel).syncUninterruptibly();
        conn.transition(NativeConnectionState.CONNECTED);
        assertTrue(events.active.await(2, TimeUnit.SECONDS));

        conn.capacity = 4;
        var payload = "backpressure-payload".getBytes(StandardCharsets.UTF_8);
        var future = channel.writeAndFlush(Unpooled.wrappedBuffer(payload));
        channel.eventLoop()
                .submit(() -> {
                }).syncUninterruptibly();
        assertFalse(
                conn.hasOutbound(),
                "Insufficient capacity should retain the message"
        );
        assertFalse(
                future.isDone(),
                "Write future should remain incomplete while WOULD_BLOCK is active"
        );

        conn.unblock();
        future.syncUninterruptibly();
        assertArrayEquals(payload, conn.outboundPayload());

        channel.close().syncUninterruptibly();
    }

    @Test
    void closeIsIdempotent() throws Exception {
        var conn = new ScriptedConnection();
        var events = new Events();
        var channel = new NativeChannel(conn);
        channel.pipeline().addLast(events);
        group.register(channel).syncUninterruptibly();
        conn.transition(NativeConnectionState.CONNECTED);
        assertTrue(events.active.await(2, TimeUnit.SECONDS));

        channel.close().syncUninterruptibly();
        channel.close().syncUninterruptibly();
        assertFalse(channel.isActive());
        assertTrue(events.inactive.await(2, TimeUnit.SECONDS));
        assertFalse(channel.isOpen());
    }

    @Test
    void failedStateClosesChannel() {
        var conn = new ScriptedConnection();
        var events = new Events();
        var channel = new NativeChannel(conn);
        channel.pipeline().addLast(events);
        group.register(channel).syncUninterruptibly();

        conn.transition(NativeConnectionState.FAILED);
        channel.eventLoop()
                .submit(() -> {
                }).syncUninterruptibly();
        assertFalse(
                channel.isOpen(),
                "FAILED should close the channel"
        );
        assertFalse(channel.isActive());
    }

    private static final class Events extends ChannelInboundHandlerAdapter {

        final CountDownLatch active = new CountDownLatch(1);
        final CountDownLatch inactive = new CountDownLatch(1);
        final CountDownLatch read = new CountDownLatch(1);
        final List<byte[]> readData = new ArrayList<>();

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            active.countDown();
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            inactive.countDown();
            ctx.fireChannelInactive();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) {
                var bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                buf.release();
                readData.add(bytes);
                read.countDown();
            } else {
                ctx.fireChannelRead(msg);
            }
        }

    }

    private static final class ScriptedConnection implements NativeConnection {

        private static final AtomicLong SEQ = new AtomicLong();

        private final long id = SEQ.incrementAndGet();
        private final AtomicReference<NativeConnectionState> state =
                new AtomicReference<>(NativeConnectionState.CONNECTING);
        private final ConcurrentLinkedDeque<byte[]> readQueue = new ConcurrentLinkedDeque<>();
        private final List<byte[]> outbound = new ArrayList<>();
        private volatile @Nullable NativeConnectionListener listener;
        private volatile int capacity = Integer.MAX_VALUE;
        private volatile boolean closed;

        @Override
        public long id() {
            return id;
        }

        @Override
        public NativeTransportKind transport() {
            return NativeTransportKind.QUIC;
        }

        @Override
        public NativeConnectionState state() {
            return state.get();
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25570);
        }

        @Override
        public NativeIoResult write(ByteBuffer source) {
            if (closed) {
                return NativeIoResult.CLOSED;
            }

            var length = source.remaining();
            synchronized (this) {
                if (length > capacity) {
                    var copy = new byte[length];
                    source.get(copy);
                    return NativeIoResult.WOULD_BLOCK;
                }

                var copy = new byte[length];
                source.get(copy);
                outbound.add(copy);
                return NativeIoResult.progressed(length);
            }
        }

        @Override
        public NativeIoResult read(ByteBuffer target) {
            if (closed) {
                return NativeIoResult.CLOSED;
            }

            var head = readQueue.peek();

            if (head == null) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = Math.min(head.length, target.remaining());
            target.put(head, 0, n);
            if (n == head.length) {
                readQueue.poll();
            } else {
                var rest = new byte[head.length - n];
                System.arraycopy(head, n, rest, 0, rest.length);
                readQueue.poll();
                readQueue.addFirst(rest);
            }

            return NativeIoResult.progressed(n);
        }

        @Override
        public void setListener(@Nullable NativeConnectionListener listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            closed = true;
            state.set(NativeConnectionState.CLOSED);
            listener = null;
        }

        void transition(NativeConnectionState newState) {
            state.set(newState);
            var l = listener;
            if (l != null) {
                l.onStateChanged(newState);
            }
        }

        void pushData(byte[] data) {
            readQueue.add(data);
            var l = listener;
            if (l != null) {
                l.onDataAvailable();
            }
        }

        void unblock() {
            capacity = Integer.MAX_VALUE;
            var l = listener;
            if (l != null) {
                l.onWritable();
            }
        }

        boolean hasOutbound() {
            return !outbound.isEmpty();
        }

        byte[] outboundPayload() {
            if (outbound.isEmpty()) {
                return new byte[0];
            }

            var total = outbound.stream().mapToInt(b -> b.length).sum();
            var merged = new byte[total];
            var offset = 0;

            for (var part : outbound) {
                System.arraycopy(part, 0, merged, offset, part.length);
                offset += part.length;
            }

            return merged;
        }

    }

}
