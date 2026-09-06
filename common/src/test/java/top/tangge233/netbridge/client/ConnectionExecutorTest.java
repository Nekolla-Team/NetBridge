package top.tangge233.netbridge.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.ability.TransportProtocol;
import top.tangge233.netbridge.config.client.ClientConfigStore;
import top.tangge233.netbridge.config.client.ClientSettings;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.nativebridge.*;
import top.tangge233.netbridge.nativebridge.fake.FakeNativeTransportBackend;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionExecutorTest {

    private final NioEventLoopGroup group = new NioEventLoopGroup(1);

    @AfterEach
    void shutdownGroup() {
        group.shutdownGracefully().syncUninterruptibly();
    }

    @Test
    void tcpOnlyPlanOpensTcp() {
        var successCache = new SuccessfulEndpointCache();
        var store = new ConnectionStateStore();
        var executor = new ConnectionExecutor(
                successCache,
                store,
                NativeRetryPolicy.defaults()
        );
        var adapter = adapter(group);
        var backend = new FakeNativeTransportBackend();
        try (backend) {
            var plan = ConnectionPlan.tcpOnly(new InetSocketAddress(
                    "203.0.113.9",
                    25565
            ));
            var future = executor.execute(
                    plan,
                    backend,
                    adapter
            );
            assertNotNull(future);
            assertEquals(
                    1,
                    adapter.openTcpCount.get()
            );
            assertTrue(adapter.tcpFuture.isSuccess());
            assertEquals(
                    ConnectionSnapshot.Phase.IDLE,
                    store.snapshot().phase()
            );
        }
    }

    private static TestAdapter adapter(EventLoopGroup group) {
        return new TestAdapter(group);
    }

    @Test
    void delegatingChannelFutureCancellationCascadesAndPreventsRetries() throws Exception {
        var successCache = new SuccessfulEndpointCache();
        var store = new ConnectionStateStore();
        var executor = new ConnectionExecutor(
                successCache,
                store,
                new NativeRetryPolicy(
                        3,
                        5000L,
                        5000L
                )
        );
        var adapter = adapter(group);
        var backend = new UnresponsiveTestBackend();
        var plan = planWithNative(new InetSocketAddress(
                InetAddress.getLoopbackAddress(),
                25565
        ));
        var future = executor.execute(
                plan,
                backend,
                adapter
        );
        assertTrue(future.cancel(true));
        assertTrue(future.isCancelled());
        assertTrue(future.isDone());

        // Wait a bit to ensure no fallback or retries occur
        Thread.sleep(100);
        assertEquals(
                0,
                adapter.openTcpCount.get(),
                "Cancelled future must not trigger TCP fallback"
        );
    }

    private static ConnectionPlan planWithNative(InetSocketAddress endpoint) {
        return ConnectionPlan.withNativeAttempt(
                new InetSocketAddress("203.0.113.9", 25565),
                new ConnectionPlan.NativeAttemptPlan(
                        TransportMode.QUIC,
                        endpoint,
                        KcpProfile.BALANCE
                )
        );
    }

    @Test
    void delegatingChannelFutureListenersExactlyOnceOnTerminal() {
        var dcf = new DelegatingChannelFuture(group);
        var count = new AtomicInteger(0);
        dcf.addListener(_ -> count.incrementAndGet());

        var embedded = new EmbeddedChannel();
        var inner = embedded.newSucceededFuture();
        dcf.setDelegate(inner, true);

        // Add another listener after completion
        dcf.addListener(_ -> count.incrementAndGet());

        assertEquals(2, count.get(), "Listeners must be invoked exactly once each");
    }

    @Test
    void nativeUnavailableFallsBackToTcp() {
        var store = new ConnectionStateStore();
        var executor = new ConnectionExecutor(
                new SuccessfulEndpointCache(),
                store,
                NativeRetryPolicy.defaults()
        );
        var adapter = adapter(group);
        var plan = planWithNative(new InetSocketAddress(
                InetAddress.getLoopbackAddress(),
                1
        ));
        var future = executor.execute(
                plan,
                null,
                adapter
        );
        assertNotNull(future);
        assertEquals(
                1,
                adapter.openTcpCount.get()
        );
    }

    @Test
    void nativeFailureRetriesThenFallsBack() {
        var store = new ConnectionStateStore();
        var executor = new ConnectionExecutor(
                new SuccessfulEndpointCache(),
                store,
                NativeRetryPolicy.defaults()
        );
        var adapter = adapter(group);
        var plan = planWithNative(new InetSocketAddress(
                InetAddress.getLoopbackAddress(),
                1
        ));
        try (var backend = new FakeNativeTransportBackend()) {
            var future = executor.execute(
                    plan,
                    backend,
                    adapter
            );
            assertNotNull(future);
            assertEquals(
                    1,
                    adapter.openTcpCount.get(),
                    "重试耗尽后回退 TCP 一次"
            );
        }
    }

    @Test
    void nativeSuccessRecordsAndPublishesConnected() throws Exception {
        var successCache = new SuccessfulEndpointCache();
        var store = new ConnectionStateStore();
        var executor = new ConnectionExecutor(
                successCache,
                store,
                NativeRetryPolicy.defaults()
        );
        var adapter = adapter(group);
        try (var backend = new AsyncTestBackend(group)) {
            var endpoint = new InetSocketAddress(
                    InetAddress.getLoopbackAddress(),
                    25570
            );
            var plan = planWithNative(endpoint);
            var future = executor.execute(
                    plan,
                    backend,
                    adapter
            );
            assertTrue(
                    future.await(15, TimeUnit.SECONDS),
                    "native 成功路径应在超时内完成"
            );
            assertTrue(
                    future.isSuccess(),
                    "native 成功路径应成功"
            );
            assertEquals(
                    0,
                    adapter.openTcpCount.get()
            );

            var lookup = successCache.lookup(
                    plan.tcpAddress(),
                    TransportMode.QUIC
            );
            assertTrue(
                    lookup.isPresent(),
                    "成功端点应写入缓存"
            );
            assertEquals(
                    endpoint.getPort(),
                    lookup.orElseThrow().endpoint().getPort()
            );

            assertEquals(
                    ConnectionSnapshot.Phase.CONNECTED,
                    store.snapshot().phase()
            );
            assertNotNull(store.snapshot().transportLine());
        }
    }

    @Test
    void connectAfterClientRuntimeCloseFailsFastWithoutNetworkWork() {
        var settingsService = ClientSettingsService.create(
                new ClientConfigStore(Path.of("nonexistent.toml"))
        );
        var backend = new FakeNativeTransportBackend();
        var runtime = new ClientRuntime(settingsService, backend);
        runtime.close();

        var adapter = adapter(group);
        var future = runtime.connect(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565),
                adapter
        );
        assertTrue(
                future.isDone(),
                "Future must be done immediately after closed runtime connect"
        );
        assertFalse(
                future.isSuccess(),
                "Future must fail on closed runtime"
        );
        assertEquals(
                0,
                adapter.openTcpCount.get(),
                "No TCP socket may be opened after runtime close"
        );
    }

    @Test
    void clientRuntimeCloseCancelsActiveAttemptAndTasks() {
        var store = new ClientConfigStore(
                Path.of("nonexistent.toml")
        );
        var settingsService = new ClientSettingsService(
                store,
                new ClientSettings(
                        TransportMode.QUIC,
                        KcpProfile.BALANCE
                )
        );
        var backend = new UnresponsiveTestBackend();
        var runtime = new ClientRuntime(settingsService, backend);
        runtime.recordServerCapabilities(
                new InetSocketAddress(
                        InetAddress.getLoopbackAddress(),
                        25565
                ),
                NetworksAbility.of(
                        new NetworksEntry(
                                true,
                                "127.0.0.1",
                                25565,
                                TransportProtocol.QUIC_V1
                        )
                )
        );

        var adapter = adapter(group);
        var future = runtime.connect(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565),
                adapter
        );
        assertFalse(future.isDone());

        runtime.close();
        assertTrue(
                future.isDone(),
                "Active attempt must be completed/cancelled on runtime close"
        );
        assertTrue(
                future.isCancelled(),
                "Active attempt must be cancelled on runtime close"
        );
        assertEquals(
                0,
                adapter.openTcpCount.get(),
                "Cancelled attempt must not fallback to TCP"
        );
    }

    private static final class UnresponsiveTestBackend implements NativeTransportBackend {

        @Override
        public NativeBackendAvailability availability() {
            return new NativeBackendAvailability(NativeBackendState.AVAILABLE, null);
        }

        @Override
        public NativeConnection connect(NativeConnectRequest request) {
            return new AsyncTestConnection(request, 1);
        }

        @Override
        public NativeServer startServer(NativeServerRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }

    }

    private static final class AsyncTestBackend implements NativeTransportBackend {

        private final EventLoopGroup group;
        private final AtomicLong ids = new AtomicLong(1);

        AsyncTestBackend(EventLoopGroup group) {
            this.group = group;
        }

        @Override
        public NativeBackendAvailability availability() {
            return new NativeBackendAvailability(NativeBackendState.AVAILABLE, null);
        }

        @Override
        public NativeConnection connect(NativeConnectRequest request) {
            var conn = new AsyncTestConnection(
                    request,
                    ids.getAndIncrement()
            );
            var unused = group.next().schedule(
                    conn::complete,
                    25,
                    TimeUnit.MILLISECONDS
            );
            return conn;
        }

        @Override
        public NativeServer startServer(NativeServerRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }

    }

    private static final class AsyncTestConnection implements NativeConnection {

        private final NativeConnectRequest request;
        private final long id;
        private volatile NativeConnectionState state = NativeConnectionState.CONNECTING;
        private volatile @Nullable NativeConnectionListener listener;

        AsyncTestConnection(
                NativeConnectRequest request,
                long id
        ) {
            this.request = request;
            this.id = id;
        }

        void complete() {
            if (state == NativeConnectionState.CONNECTING) {
                state = NativeConnectionState.CONNECTED;
                var l = listener;
                if (l != null) {
                    l.onStateChanged(NativeConnectionState.CONNECTED);
                }
            }
        }

        @Override
        public long id() {
            return id;
        }

        @Override
        public NativeTransportKind transport() {
            return request.transport();
        }

        @Override
        public NativeConnectionState state() {
            return state;
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return new InetSocketAddress(
                    InetAddress.getLoopbackAddress(),
                    request.port()
            );
        }

        @Override
        public NativeIoResult write(ByteBuffer source) {
            return state == NativeConnectionState.CONNECTED
                    ? NativeIoResult.WOULD_BLOCK
                    : NativeIoResult.CLOSED;
        }

        @Override
        public NativeIoResult read(ByteBuffer target) {
            return NativeIoResult.WOULD_BLOCK;
        }

        @Override
        public void setListener(@Nullable NativeConnectionListener listener) {
            this.listener = listener;
            if (state != NativeConnectionState.CONNECTING && listener != null) {
                listener.onStateChanged(state);
            }
        }

        @Override
        public void close() {
            state = NativeConnectionState.CLOSED;
            listener = null;
        }

    }

    private static final class TestAdapter implements ConnectionExecutorAdapter {

        private final EventLoopGroup group;
        private final AtomicInteger openTcpCount = new AtomicInteger();
        private final EmbeddedChannel tcpChannel = new EmbeddedChannel();
        private final ChannelFuture tcpFuture = tcpChannel.newSucceededFuture();

        TestAdapter(EventLoopGroup group) {
            this.group = group;
        }

        @Override
        public EventLoopGroup eventLoopGroup() {
            return group;
        }

        @Override
        public void initNativeChannel(Channel channel) {
        }

        @Override
        public ChannelFuture openTcp(InetSocketAddress address) {
            openTcpCount.incrementAndGet();
            return tcpFuture;
        }

    }

}
