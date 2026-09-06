package top.tangge233.netbridge.server;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

public final class ServerRuntime implements AutoCloseable {

    private final NativeTransportBackend backend;
    private final ServerConfigStore configStore;
    private final AtomicLong generationSequence = new AtomicLong(1);
    private final ExecutorService adoptExecutor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "net-bridge-adopt");
        thread.setDaemon(true);
        return thread;
    });

    private volatile @Nullable ServerTransportManager manager;
    private volatile @Nullable NativeConnectionAdopter adopter;
    private volatile boolean closed;

    public ServerRuntime(
            NativeTransportBackend backend,
            ServerConfigStore configStore
    ) {
        this.backend = backend;
        this.configStore = configStore;
    }

    public void setAdopter(@Nullable NativeConnectionAdopter adopter) {
        this.adopter = adopter;
    }

    public synchronized boolean start(
            int mcPort,
            @Nullable String mcBindIp
    ) {
        if (closed) {
            return false;
        }

        var current = manager;
        if (current != null && current.isRunning()) {
            return true;
        }

        var next = new ServerTransportManager(
                backend,
                configStore.load(),
                adopter,
                adoptExecutor,
                generationSequence.getAndIncrement()
        );
        this.manager = next;
        var started = false;
        try {
            started = next.start(mcPort, mcBindIp);
        } catch (Throwable t) {
            this.manager = null;
            throw t;
        }
        if (!started) {
            this.manager = null;
        }
        return started;
    }

    public boolean isRunning() {
        var current = manager;
        return current != null && current.isRunning();
    }

    public boolean isSessionValid(long sessionGeneration) {
        var current = manager;
        return !closed
                && current != null
                && current.isSessionValid(sessionGeneration);
    }

    public NetworksAbility announcement() {
        var current = manager;
        return current != null
                ? current.announcement()
                : NetworksAbility.empty();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        stop();
        closed = true;
        adoptExecutor.shutdown();
        try {
            if (!adoptExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                adoptExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            adoptExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        NetBridge.LOGGER.info("Server runtime closed");
    }

    public synchronized void stop() {
        var current = manager;
        if (current != null) {
            current.close();
            manager = null;
        }
    }

}
