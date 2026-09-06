package top.tangge233.netbridge.client;

import io.netty.channel.ChannelFuture;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.transport.TransportMode;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

public final class ClientRuntime implements AutoCloseable {

    private final ClientSettingsService settings;
    private final @Nullable NativeTransportBackend backend;
    private final ServerCapabilityCache capabilities;
    private final SuccessfulEndpointCache successfulEndpoints;
    private final ConnectionStateStore stateStore;
    private final ConnectionPlanner planner;
    private final ConnectionExecutor executor;
    private final Set<DelegatingChannelFuture> activeFutures = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private volatile boolean closed;

    public ClientRuntime(
            ClientSettingsService settings,
            @Nullable NativeTransportBackend backend
    ) {
        this.settings = settings;
        this.backend = backend;
        this.capabilities = new ServerCapabilityCache();
        this.successfulEndpoints = new SuccessfulEndpointCache();
        this.stateStore = new ConnectionStateStore();
        this.planner = new ConnectionPlanner();
        this.executor = new ConnectionExecutor(
                successfulEndpoints,
                stateStore,
                NativeRetryPolicy.defaults()
        );
    }

    public ChannelFuture connect(
            InetSocketAddress tcpAddress,
            ConnectionExecutorAdapter adapter
    ) {
        var future = new DelegatingChannelFuture(adapter.eventLoopGroup());
        synchronized (lifecycleLock) {
            if (closed) {
                stateStore.idle();
                future.completeFailure(new IllegalStateException("ClientRuntime is closed"), null);
                return future;
            }
            activeFutures.add(future);
        }

        future.addListener(_ -> {
            synchronized (lifecycleLock) {
                activeFutures.remove(future);
            }
        });

        var current = settings.current();
        var plan = planner.plan(
                tcpAddress,
                current,
                capabilities.get(tcpAddress),
                successfulEndpoints.lookup(tcpAddress, current.mode()),
                nativeAvailable()
        );
        executor.execute(
                plan,
                backend,
                adapter,
                future
        );
        return future;
    }

    public boolean nativeAvailable() {
        return !closed
                && backend != null
                && backend.availability().available();
    }

    public void recordServerCapabilities(
            InetSocketAddress address,
            NetworksAbility ability
    ) {
        capabilities.record(address, ability);
    }

    public boolean acceleratedRequested() {
        return settings.current().mode() != TransportMode.TCP;
    }

    public ConnectionSnapshot snapshot() {
        return stateStore.snapshot();
    }

    @Override
    public void close() {
        List<DelegatingChannelFuture> toCancel;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            toCancel = new ArrayList<>(activeFutures);
            activeFutures.clear();
        }

        for (var future : toCancel) {
            try {
                future.cancel(true);
            } catch (Throwable _) {
                // ignore
            }
        }
        stateStore.idle();
    }

}
