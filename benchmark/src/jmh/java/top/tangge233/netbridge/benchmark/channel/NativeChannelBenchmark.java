package top.tangge233.netbridge.benchmark.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import top.tangge233.netbridge.channel.NativeChannel;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * L3A NativeChannel microbenchmarks on a real Netty event loop over {@link FakeNativeConnection}
 * (no Rust/FFM in the measured path).
 */
@SuppressWarnings("NullAway")
public class NativeChannelBenchmark {

    @Benchmark
    public void writeDirectByteBuf(ChannelState s, Blackhole bh) {
        var chunk = s.master.retainedDuplicate();
        s.channel.writeAndFlush(chunk).awaitUninterruptibly(5, TimeUnit.SECONDS);
        bh.consume(s.connection.writtenBytes());
    }

    @Benchmark
    public void writeCompositeByteBuf(ChannelState s, Blackhole bh) {
        var half = s.size / 2;
        var composite = (ByteBuf) s.channel.alloc()
                .compositeBuffer(2)
                .addComponent(
                        true,
                        s.master.retainedSlice(0, half)
                )
                .addComponent(
                        true,
                        s.master.retainedSlice(half, s.size - half)
                );
        s.channel.writeAndFlush(composite).awaitUninterruptibly(5, TimeUnit.SECONDS);
        bh.consume(s.connection.writtenBytes());
    }

    @Benchmark
    public void readAutoRead(ChannelState s, Blackhole bh) {
        var before = s.counting.delivered.get();
        s.connection.push(s.pushChunk);
        s.readOnLoop();
        bh.consume(s.awaitDelivered(before + s.size));
    }

    @Benchmark
    public void readManual(ChannelState s, Blackhole bh) {
        if (s.channel.config().isAutoRead()) {
            s.setAutoReadOnLoop(false);
        }
        var before = s.counting.delivered.get();
        s.connection.push(s.pushChunk);
        s.readOnLoop();
        bh.consume(s.awaitDelivered(before + s.size));
    }

    @Benchmark
    public void wouldBlockRecovery(ChannelState s, Blackhole bh) {
        var baseline = s.connection.wouldBlockWrites();
        s.connection.setWriteWouldBlock(true);
        var chunk = s.master.retainedDuplicate();
        var future = s.channel.writeAndFlush(chunk);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (s.connection.wouldBlockWrites() == baseline
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        s.connection.setWriteWouldBlock(false);
        s.connection.makeWritable();
        future.awaitUninterruptibly(5, TimeUnit.SECONDS);
        bh.consume(s.connection.wouldBlockWrites());
    }

    /** Base state: one registered NativeChannel driven by a fake connection. */
    @SuppressWarnings("NotNullFieldNotInitialized")
    @State(Scope.Benchmark)
    public static class ChannelState {

        @Param({"64", "1024", "65536"})
        int size;

        EventLoopGroup group;
        FakeNativeConnection connection;
        NativeChannel channel;
        CountingHandler counting;
        ByteBuf master;
        byte[] pushChunk;

        @Setup(Level.Trial)
        public void setUp() {
            group = new NioEventLoopGroup(1);
            connection = new FakeNativeConnection(1);
            channel = new NativeChannel(connection);
            counting = new CountingHandler();
            channel.pipeline().addLast(counting);
            group.register(channel).awaitUninterruptibly();
            connection.connect();
            awaitTrue(() -> channel.isActive());
            var pattern = new byte[size];
            IntStream.range(0, size)
                    .forEach(i ->
                            pattern[i] = (byte) (i * 31)
                    );
            pushChunk = pattern;
            master = Unpooled.buffer(size);
            master.writeBytes(pattern);
            // Arm the read path once, on the event loop: NativeChannel drains inline
            // from read()/doBeginRead(), so it must not run on the benchmark thread.
            readOnLoop();
        }

        void awaitTrue(BooleanSupplier condition) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("timed out waiting for condition");
                }
                Thread.onSpinWait();
            }
        }

        void readOnLoop() {
            channel.eventLoop()
                    .submit(() -> channel.read())
                    .syncUninterruptibly();
        }

        void setAutoReadOnLoop(boolean value) {
            channel.eventLoop()
                    .submit(() -> channel.config().setAutoRead(value))
                    .syncUninterruptibly();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            try {
                channel.close().await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            master.release();
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }

        long awaitDelivered(long atLeast) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (counting.delivered.get() < atLeast) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("timed out waiting for read delivery");
                }
                Thread.onSpinWait();
            }
            return counting.delivered.get();
        }

    }

    /** Counts and releases inbound data on the channel event loop. */
    static final class CountingHandler extends ChannelInboundHandlerAdapter {

        private final AtomicLong delivered = new AtomicLong();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) {
                delivered.addAndGet(buf.readableBytes());
                buf.release();
            } else {
                ctx.fireChannelRead(msg);
            }
        }

    }

}
