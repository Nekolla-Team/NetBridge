package top.tangge233.netbridge.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.nativebridge.*;
import top.tangge233.netbridge.nativebridge.fake.FakeNativeTransportBackend;
import top.tangge233.netbridge.nativebridge.internal.ffm.NativeResourceException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimeHardeningTest {

    @Test
    void earlyAcceptDuringStartIsValidAndAdopted(@TempDir Path dir) throws Exception {
        var store = new ServerConfigStore(dir.resolve("server.toml"));
        var adopted = new AtomicBoolean(false);
        var adoptedGen = new AtomicReference<>(0L);
        var latch = new CountDownLatch(1);

        var delegate = new FakeNativeTransportBackend();
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
                var server = delegate.startServer(request);
                return new NativeServer() {
                    @Override
                    public long id() {
                        return server.id();
                    }

                    @Override
                    public NativeTransportKind transport() {
                        return server.transport();
                    }

                    @Override
                    public int localPort() {
                        return server.localPort();
                    }

                    @Override
                    public void setListener(NativeServerListener listener) {
                        server.setListener(listener);
                        var fakeConn = new NativeConnection() {
                            @Override
                            public long id() {
                                return 999;
                            }

                            @Override
                            public NativeTransportKind transport() {
                                return request.transport();
                            }

                            @Override
                            public NativeConnectionState state() {
                                return NativeConnectionState.CONNECTED;
                            }

                            @Override
                            public InetSocketAddress remoteAddress() {
                                return new InetSocketAddress(
                                        InetAddress.getLoopbackAddress(),
                                        12345
                                );
                            }

                            @Override
                            public NativeIoResult write(ByteBuffer src) {
                                return NativeIoResult.progressed(src.remaining());
                            }

                            @Override
                            public NativeIoResult read(ByteBuffer dst) {
                                return NativeIoResult.progressed(0);
                            }

                            @Override
                            public void setListener(NativeConnectionListener listener) {
                            }

                            @Override
                            public void close() {
                            }
                        };
                        listener.onAccepted(fakeConn);
                    }

                    @Override
                    public void close() {
                        server.close();
                    }
                };
            }

            @Override
            public void close() {
                delegate.close();
            }
        };

        try (var runtime = new ServerRuntime(backend, store)) {
            runtime.setAdopter((_, gen) -> {
                adopted.set(true);
                adoptedGen.set(gen);
                latch.countDown();
            });

            assertTrue(runtime.start(25565, null));
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(adopted.get());
            assertNotEquals(0L, (long) adoptedGen.get());
        }
    }

    @Test
    void stopFailureRetainsManagerAndThrowsException(@TempDir Path dir) {
        var store = new ServerConfigStore(dir.resolve("server.toml"));
        var delegate = new FakeNativeTransportBackend();
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
                var server = delegate.startServer(request);
                return new NativeServer() {
                    @Override
                    public long id() {
                        return server.id();
                    }

                    @Override
                    public NativeTransportKind transport() {
                        return server.transport();
                    }

                    @Override
                    public int localPort() {
                        return server.localPort();
                    }

                    @Override
                    public void setListener(NativeServerListener listener) {
                        server.setListener(listener);
                    }

                    @Override
                    public void close() {
                        throw new RuntimeException("Simulated native server close failure");
                    }
                };
            }

            @Override
            public void close() {
                delegate.close();
            }
        };

        try (var runtime = new ServerRuntime(backend, store)) {
            assertTrue(runtime.start(25565, null));
            assertTrue(runtime.isRunning());

            assertThrows(NativeResourceException.class, runtime::stop);
            assertTrue(runtime.isRunning());
        } catch (NativeResourceException _) {
            // expected from close() in try-with-resources
        }
    }

}
