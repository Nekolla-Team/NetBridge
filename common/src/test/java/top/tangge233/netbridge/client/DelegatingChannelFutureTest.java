package top.tangge233.netbridge.client;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DelegatingChannelFutureTest {

    private static NioEventLoopGroup eventLoopGroup;

    @BeforeAll
    static void setUp() {
        eventLoopGroup = new NioEventLoopGroup(2);
    }

    @AfterAll
    static void tearDown() {
        var _ = eventLoopGroup.shutdownGracefully();
    }

    @Test
    void channelThrowsBeforeInitialized() {
        var future = new DelegatingChannelFuture(eventLoopGroup);
        assertThrows(IllegalStateException.class, future::channel);
    }

    @Test
    void channelReturnsWinningChannelOnSuccess() {
        var future = new DelegatingChannelFuture(eventLoopGroup);
        var channel = new EmbeddedChannel();
        future.completeSuccess(channel);
        assertSame(channel, future.channel());
        assertTrue(future.isDone());
        assertTrue(future.isSuccess());
        assertFalse(future.isCancelled());
    }

    @Test
    void cancelClosesCurrentChannelAndDelegate() {
        var future = new DelegatingChannelFuture(eventLoopGroup);
        var channel = new EmbeddedChannel();
        assertTrue(future.registerAttempt(channel, null));
        assertTrue(channel.isOpen());

        assertTrue(future.cancel(true));
        assertTrue(future.isCancelled());
        assertTrue(future.isDone());
        assertFalse(future.isSuccess());
        assertFalse(channel.isOpen());
    }

    @Test
    void registerAttemptOnCancelledFutureClosesChannelImmediately() {
        var future = new DelegatingChannelFuture(eventLoopGroup);
        future.cancel(true);

        var channel = new EmbeddedChannel();
        assertTrue(channel.isOpen());
        assertFalse(future.registerAttempt(channel, null));
        assertFalse(channel.isOpen());
    }

    @Test
    void concurrentAddListenerAndCompleteSuccess() throws Exception {
        var future = new DelegatingChannelFuture(eventLoopGroup);
        var channel = new EmbeddedChannel();
        var callbackCount = new AtomicInteger(0);
        var listenerCount = 50;

        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(listenerCount + 1);

        var executor = Executors.newFixedThreadPool(8);
        try {
            IntStream.range(0, listenerCount)
                    .<Runnable>mapToObj(_ -> () -> {
                        try {
                            startLatch.await();
                            future.addListener(_ -> callbackCount.incrementAndGet());
                        } catch (Exception e) {
                            // ignore
                        } finally {
                            doneLatch.countDown();
                        }
                    })
                    .forEach(executor::execute);

            executor.execute(() -> {
                try {
                    startLatch.await();
                    future.completeSuccess(channel);
                } catch (Exception e) {
                    // ignore
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
            assertEquals(listenerCount, callbackCount.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void syncThrowsCauseOnFailure() {
        var future = new DelegatingChannelFuture(eventLoopGroup);
        var cause = new ConnectException("remote refused");
        future.completeFailure(cause, null);

        var exc = assertThrows(ConnectException.class, future::sync);
        assertEquals("remote refused", exc.getMessage());
    }

}
