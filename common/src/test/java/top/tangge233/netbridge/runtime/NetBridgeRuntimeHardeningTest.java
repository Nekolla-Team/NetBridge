package top.tangge233.netbridge.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.config.client.ClientConfigStore;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.nativebridge.*;
import top.tangge233.netbridge.nativebridge.fake.FakeNativeTransportBackend;
import top.tangge233.netbridge.nativebridge.internal.ffm.NativeResourceException;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.NullMarked;

import static org.junit.jupiter.api.Assertions.*;

class NetBridgeRuntimeHardeningTest {

    @AfterEach
    void tearDown() {
        try {
            NetBridgeServices.resetForTest();
        } catch (Throwable _) {
            // ignore
        }
    }

    @Test
    @NullMarked
    void closeFailureEntersCloseFailedAndRetainsFailedBackend(@TempDir Path dir) {
        var paths = new ConfigPaths(dir);
        var clientSettings = ClientSettingsService.create(
                new ClientConfigStore(paths.clientFile())
        );
        var serverConfig = new ServerConfigStore(paths.serverFile());

        var delegate = new FakeNativeTransportBackend();
        var backendClosed = new AtomicBoolean(false);
        var backend = new NativeTransportBackend() {
            @Override
            public NativeBackendAvailability availability() {
                return delegate.availability();
            }

            @Override
            public NativeConnection connect(NativeConnectRequest request) {
                return delegate.connect(request);
            }

            @Override
            public NativeServer startServer(NativeServerRequest request) {
                return delegate.startServer(request);
            }

            @Override
            public void close() {
                backendClosed.set(true);
                throw new RuntimeException("Simulated native backend close failure");
            }
        };

        var runtime = new NetBridgeRuntime(paths, clientSettings, serverConfig, backend);
        assertEquals(NetBridgeRuntime.State.OPEN, runtime.state());

        assertThrows(NativeResourceException.class, runtime::close);
        assertEquals(NetBridgeRuntime.State.CLOSE_FAILED, runtime.state());
        assertTrue(backendClosed.get());
    }

    @Test
    void successfulCloseTransitionsToClosed(@TempDir Path dir) {
        var paths = new ConfigPaths(dir);
        var clientSettings = ClientSettingsService.create(
                new ClientConfigStore(paths.clientFile())
        );
        var serverConfig = new ServerConfigStore(paths.serverFile());
        var backend = new FakeNativeTransportBackend();

        var runtime = new NetBridgeRuntime(
                paths,
                clientSettings,
                serverConfig,
                backend
        );
        assertEquals(NetBridgeRuntime.State.OPEN, runtime.state());

        runtime.close();
        assertEquals(NetBridgeRuntime.State.CLOSED, runtime.state());
        assertFalse(runtime.nativeAvailable());
    }

}
