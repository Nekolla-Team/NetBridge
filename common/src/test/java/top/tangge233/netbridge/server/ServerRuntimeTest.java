package top.tangge233.netbridge.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.nativebridge.NativeConnectRequest;
import top.tangge233.netbridge.nativebridge.NativeConnection;
import top.tangge233.netbridge.nativebridge.UnavailableNativeTransportBackend;
import top.tangge233.netbridge.nativebridge.fake.FakeNativeTransportBackend;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimeTest {

    @Test
    void startPublishesAnnouncementAndRuns(@TempDir Path dir) {
        try (
                var backend = fakeBackend();
                var runtime = new ServerRuntime(backend, store(dir))
        ) {
            assertTrue(runtime.start(25565, null));
            assertTrue(runtime.isRunning());
            var entries = runtime.announcement().entries();
            assertFalse(entries.isEmpty());
            var quic = Objects.requireNonNull(entries.get("quic"));
            assertEquals(25565, quic.port());
        }
    }

    private static FakeNativeTransportBackend fakeBackend() {
        return new FakeNativeTransportBackend();
    }

    private static ServerConfigStore store(Path dir) {
        return new ServerConfigStore(dir.resolve("server.toml"));
    }

    @Test
    void duplicateStartIsIdempotent(@TempDir Path dir) {
        try (
                var backend = fakeBackend();
                var runtime = new ServerRuntime(backend, store(dir))
        ) {
            assertTrue(runtime.start(25565, null));
            assertTrue(runtime.start(25565, null));
            assertTrue(runtime.isRunning());
        }
    }

    @Test
    void stopClearsStateAndAnnouncement(@TempDir Path dir) {
        try (
                var backend = fakeBackend();
                var runtime = new ServerRuntime(backend, store(dir))
        ) {
            assertTrue(runtime.start(25565, null));
            runtime.stop();
            assertFalse(runtime.isRunning());
            assertTrue(runtime.announcement().entries().isEmpty());
        }
    }

    @Test
    void restartSupportsSecondServerSession(@TempDir Path dir) {
        try (
                var backend = fakeBackend();
                var runtime = new ServerRuntime(backend, store(dir))
        ) {
            assertTrue(runtime.start(25565, null));
            assertEquals(
                    25565,
                    Objects.requireNonNull(runtime.announcement().entries().get("quic")).port()
            );
            runtime.stop();
            assertFalse(runtime.isRunning());

            assertTrue(runtime.start(25566, null));
            assertEquals(
                    25566,
                    Objects.requireNonNull(runtime.announcement().entries().get("quic")).port()
            );
            runtime.stop();
            assertFalse(runtime.isRunning());
            assertTrue(runtime.announcement().entries().isEmpty());
        }
    }

    @Test
    void unavailableBackendDegradesWithoutError(@TempDir Path dir) {
        var backend = new UnavailableNativeTransportBackend("boom");
        try (var runtime = new ServerRuntime(backend, store(dir))) {
            assertFalse(runtime.start(25565, null));
            assertFalse(runtime.isRunning());
            assertTrue(runtime.announcement().entries().isEmpty());
        }
        backend.close();
    }

    @Test
    void acceptedConnectionIsAdopted(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        var adopted = new AtomicReference<@Nullable NativeConnection>();
        try (
                var backend = fakeBackend();
                var runtime = new ServerRuntime(backend, store(dir))
        ) {
            runtime.setAdopter((connection, _) -> {
                adopted.set(connection);
                latch.countDown();
            });
            assertTrue(runtime.start(25565, null));

            backend.connect(NativeConnectRequest.quic(
                    "127.0.0.1",
                    25565
            ));
            assertTrue(
                    latch.await(3, TimeUnit.SECONDS),
                    "接受的新连接应交给 adopter"
            );
            var conn = adopted.get();
            assertNotNull(conn);
            assertTrue(conn.id() > 0);
        }
    }

    @Test
    void adopterFailureClosesConnection(@TempDir Path dir) throws Exception {
        var backend = fakeBackend();
        var runtime = new ServerRuntime(backend, store(dir));
        var _ = new CountDownLatch(1);
        var adopted = new AtomicReference<@Nullable NativeConnection>();
        runtime.setAdopter((connection, _) -> {
            adopted.set(connection);
            throw new RuntimeException("adopt failed");
        });
        assertTrue(runtime.start(25565, null));

        var client = backend.connect(
                NativeConnectRequest.quic("127.0.0.1", 25565)
        );
        var deadline = System.currentTimeMillis() + 3000;
        while (adopted.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        var conn = adopted.get();
        assertNotNull(conn);
        assertFalse(conn.state().toString().isEmpty());
        client.close();
        runtime.close();
        backend.close();
    }

}
