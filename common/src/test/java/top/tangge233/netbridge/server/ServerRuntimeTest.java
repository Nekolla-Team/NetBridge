package top.tangge233.netbridge.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.nativebridge.NativeConnectRequest;
import top.tangge233.netbridge.nativebridge.NativeConnection;
import top.tangge233.netbridge.nativebridge.UnavailableNativeTransportBackend;
import top.tangge233.netbridge.nativebridge.fake.FakeNativeTransportBackend;
import top.tangge233.netbridge.transport.AcceleratedTransport;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;

import static java.util.Objects.requireNonNull;

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
            var quic = requireNonNull(entries.get(AcceleratedTransport.QUIC));
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
                    requireNonNull(runtime.announcement()
                            .entries()
                            .get(AcceleratedTransport.QUIC)).port()
            );
            runtime.stop();
            assertFalse(runtime.isRunning());

            assertTrue(runtime.start(25566, null));
            assertEquals(
                    25566,
                    requireNonNull(runtime.announcement()
                            .entries()
                            .get(AcceleratedTransport.QUIC)).port()
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

    @Test
    void serverGenerationValidationRejectsStaleAdoptionSession(@TempDir Path dir) throws Exception {
        try (
                var backend = fakeBackend();
                var runtime = new ServerRuntime(backend, store(dir))
        ) {
            var sessionALatch = new CountDownLatch(1);
            var sessionAGeneration = new AtomicReference<@Nullable Long>();
            runtime.setAdopter((_, gen) -> {
                sessionAGeneration.set(gen);
                sessionALatch.countDown();
            });

            // Start session A
            assertTrue(runtime.start(25565, null));
            var clientA = backend.connect(
                    NativeConnectRequest.quic("127.0.0.1", 25565)
            );
            assertTrue(sessionALatch.await(3, TimeUnit.SECONDS));
            var genA = sessionAGeneration.get();
            assertNotNull(genA);
            assertTrue(runtime.isSessionValid(genA));

            // Stop session A and start session B
            runtime.stop();
            assertFalse(
                    runtime.isSessionValid(genA),
                    "Stale generation A must be invalid after stop"
            );

            var sessionBLatch = new CountDownLatch(1);
            var sessionBGeneration = new AtomicReference<@Nullable Long>();
            runtime.setAdopter((_, gen) -> {
                sessionBGeneration.set(gen);
                sessionBLatch.countDown();
            });
            assertTrue(runtime.start(25566, null));
            assertFalse(
                    runtime.isSessionValid(genA),
                    "Stale generation A must still be invalid when session B is running"
            );

            var clientB = backend.connect(
                    NativeConnectRequest.quic("127.0.0.1", 25566)
            );
            assertTrue(sessionBLatch.await(3, TimeUnit.SECONDS));
            var genB = sessionBGeneration.get();
            assertNotNull(genB);
            assertNotEquals(genA, genB);
            assertTrue(
                    runtime.isSessionValid(genB),
                    "Session B generation must be valid"
            );

            clientA.close();
            clientB.close();
        }
    }

}
