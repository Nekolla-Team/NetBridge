package top.tangge233.netbridge.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.config.client.ClientConfigStore;
import top.tangge233.netbridge.config.client.ClientSettings;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.nativebridge.fake.FakeNativeTransportBackend;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuntimeConcurrencyTest {

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
    void connectVsCloseRaceNeverLeaksOrLeavesUncancelledFutures() throws Exception {
        var runs = 50;
        for (var run = 0; run < runs; run++) {
            var backend = new FakeNativeTransportBackend();
            var runtime = new ClientRuntime(testSettings(TransportMode.TCP), backend);
            var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);

            var threads = 10;
            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(threads + 1);
            var futures = Collections.synchronizedList(new ArrayList<ChannelFuture>());

            var executor = Executors.newFixedThreadPool(threads + 1);
            try {
                IntStream.range(0, threads)
                        .<Runnable>mapToObj(_ -> () -> {
                            try {
                                startLatch.await();
                                var f = runtime.connect(
                                        address, new ConnectionExecutorAdapter() {
                                            @Override
                                            public EventLoopGroup eventLoopGroup() {
                                                return eventLoopGroup;
                                            }

                                            @Override
                                            public void initNativeChannel(Channel channel) {
                                            }

                                            @Override
                                            public ChannelFuture openTcp(InetSocketAddress address) {
                                                var ch = new EmbeddedChannel();
                                                var p = new DefaultChannelPromise(ch);
                                                p.setSuccess();
                                                return p;
                                            }
                                        }
                                );
                                futures.add(f);
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
                        runtime.close();
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        doneLatch.countDown();
                    }
                });

                startLatch.countDown();
                assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

                // Every future that was returned must either be done/cancelled or failed.
                futures.forEach(f -> assertTrue(
                        f.isDone(),
                        "All futures must complete or cancel after runtime is closed"
                ));
            } finally {
                executor.shutdownNow();
                runtime.close();
                backend.close();
            }
        }
    }

    private static ClientSettingsService testSettings(TransportMode mode) {
        var store = new ClientConfigStore(Path.of("non-existent-client.toml"));
        return new ClientSettingsService(store, new ClientSettings(mode, KcpProfile.BALANCE));
    }

    @Test
    void cancelBeforeFallbackPreventsSocketOpen() {
        var executor = new ConnectionExecutor(
                new SuccessfulEndpointCache(),
                new ConnectionStateStore(),
                NativeRetryPolicy.defaults()
        );

        var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        var plan = new ConnectionPlanner().plan(
                address,
                new ClientSettings(TransportMode.TCP, KcpProfile.BALANCE),
                NetworksAbility.empty(),
                Optional.empty(),
                false
        );

        var future = new DelegatingChannelFuture(eventLoopGroup);
        future.cancel(true); // Cancelled before fallback

        var openedChannel = new AtomicBoolean(false);
        executor.execute(
                plan,
                null,
                new ConnectionExecutorAdapter() {
                    @Override
                    public EventLoopGroup eventLoopGroup() {
                        return eventLoopGroup;
                    }

                    @Override
                    public void initNativeChannel(Channel channel) {
                    }

                    @Override
                    public ChannelFuture openTcp(InetSocketAddress address) {
                        openedChannel.set(true);
                        var ch = new EmbeddedChannel();
                        var p = new DefaultChannelPromise(ch);
                        p.setSuccess();
                        return p;
                    }
                },
                future
        );

        assertFalse(
                openedChannel.get(),
                "openTcp must not be called if future was already cancelled"
        );
    }

    @Test
    void cancelDuringFallbackClosesSocketImmediately() {
        var executor = new ConnectionExecutor(
                new SuccessfulEndpointCache(),
                new ConnectionStateStore(),
                NativeRetryPolicy.defaults()
        );

        var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        var plan = new ConnectionPlanner().plan(
                address,
                new ClientSettings(TransportMode.TCP, KcpProfile.BALANCE),
                NetworksAbility.empty(),
                Optional.empty(),
                false
        );

        var future = new DelegatingChannelFuture(eventLoopGroup);
        var openedChannel = new AtomicBoolean(false);
        var createdChannel = new EmbeddedChannel();

        executor.execute(
                plan,
                null,
                new ConnectionExecutorAdapter() {
                    @Override
                    public EventLoopGroup eventLoopGroup() {
                        return eventLoopGroup;
                    }

                    @Override
                    public void initNativeChannel(Channel channel) {
                    }

                    @Override
                    public ChannelFuture openTcp(InetSocketAddress address) {
                        openedChannel.set(true);
                        future.cancel(true); // Cancelled concurrently during openTcp
                        var p = new DefaultChannelPromise(createdChannel);
                        p.setSuccess();
                        return p;
                    }
                },
                future
        );

        assertTrue(openedChannel.get());
        assertFalse(createdChannel.isOpen(), "Channel must be closed if future was cancelled");
    }

}
