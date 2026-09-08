package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.nativebridge.NativeEvent;
import top.tangge233.netbridge.nativebridge.NativeException;

import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FfmPocTest {

    private static Path nativeLibPath;

    @BeforeAll
    static void setUp() {
        nativeLibPath = FfmTestSupport.requireNativeLibraryOrSkip();
    }

    @Test
    void testPoc1SymbolLookupLoadsCdylib() {
        try (var arena = Arena.ofConfined()) {
            var lookup = SymbolLookup.libraryLookup(nativeLibPath, arena);
            var symbolOpt = lookup.find("netbridge_get_api");
            assertTrue(
                    symbolOpt.isPresent(),
                    "netbridge_get_api symbol must be exported and found"
            );
            assertNotEquals(MemorySegment.NULL, symbolOpt.get());
        }
    }

    @Test
    void testPoc2GetApiBootstrap() throws Throwable {
        try (var arena = Arena.ofShared()) {
            var lookup = SymbolLookup.libraryLookup(nativeLibPath, arena);
            var symbol = lookup.find("netbridge_get_api").orElseThrow();
            var linker = Linker.nativeLinker();
            var getApiHandle = linker.downcallHandle(
                    symbol,
                    FfmApiLayouts.GET_API_DESC
            );

            var tablePtr = arena.allocate(ValueLayout.ADDRESS);
            var status = (int) getApiHandle.invokeExact(1, 0, tablePtr);
            assertEquals(FfmStatus.NB_OK, status);

            var tableAddr = tablePtr.get(ValueLayout.ADDRESS, 0);
            assertNotEquals(MemorySegment.NULL, tableAddr);

            var tableSegment = tableAddr.reinterpret(
                    FfmApiLayouts.API_V1.byteSize(),
                    arena,
                    null
            );
            var api = FfmApiV1.fromSegment(tableSegment);
            assertEquals(1, api.abiMajor());
            assertEquals(0, api.abiMinor());
            assertEquals(184, api.structSize());

            assertNotNull(api.contextCreate());
            assertNotNull(api.connect());
            assertNotNull(api.connectionWrite());
            assertNotNull(api.connectionRead());
            assertNotNull(api.serverStart());

            var badPtr = arena.allocate(ValueLayout.ADDRESS);
            var badMajorStatus = (int) getApiHandle.invokeExact(2, 0, badPtr);
            assertEquals(FfmStatus.NB_ABI_MISMATCH, badMajorStatus);

            var badMinorStatus = (int) getApiHandle.invokeExact(1, 99, badPtr);
            assertEquals(FfmStatus.NB_ABI_MISMATCH, badMinorStatus);
        }
    }

    @Test
    void testPoc3MemorySegmentReadWriteRoundtrip() throws Exception {
        try (
                var lib = FfmNativeLibrary.load(nativeLibPath);
                var ctx = lib.createContext(2)
        ) {

            var acceptedConnLatch = new CountDownLatch(1);
            var serverConnHolder = new long[1];

            ctx.dispatcher().addListener(event -> {
                if (event instanceof NativeEvent.Accepted accepted) {
                    serverConnHolder[0] = accepted.connectionId();
                    acceptedConnLatch.countDown();
                }
            });

            var serverId = ctx.serverStart(
                    1,
                    null,
                    0,
                    64,
                    0
            );
            var port = ctx.serverPort(serverId);
            assertTrue(port > 0);

            var clientId = ctx.connect(
                    1,
                    "127.0.0.1",
                    port,
                    0
            );
            assertTrue(clientId > 0);

            var deadline = Instant.now().plus(Duration.ofSeconds(5));
            while (Instant.now().isBefore(deadline)) {
                if (ctx.connectionState(clientId) == 2) { // NB_CONNECTION_CONNECTED = 2
                    break;
                }
                Thread.sleep(10);
            }
            assertEquals(2, ctx.connectionState(clientId));

            assertTrue(
                    acceptedConnLatch.await(5, TimeUnit.SECONDS),
                    "Server should accept incoming connection"
            );
            var serverConnId = serverConnHolder[0];
            assertTrue(serverConnId > 0);

            var clientMsg = "Hello server via Java 25 MemorySegment!".getBytes(StandardCharsets.UTF_8);
            try (var arena = Arena.ofConfined()) {
                var srcSeg = arena.allocateFrom(
                        ValueLayout.JAVA_BYTE,
                        clientMsg
                );
                var written = ctx.connectionWrite(
                        clientId,
                        srcSeg,
                        clientMsg.length
                );
                assertEquals(clientMsg.length, written.bytes());

                var dstSeg = arena.allocate(clientMsg.length);
                var totalRead = 0;
                var readDeadline = Instant.now().plus(Duration.ofSeconds(5));
                while (Instant.now().isBefore(readDeadline) && totalRead < clientMsg.length) {
                    var n = ctx.connectionRead(
                            serverConnId,
                            dstSeg,
                            clientMsg.length
                    );
                    if (n.bytes() > 0) {
                        totalRead += n.bytes();
                    } else {
                        Thread.sleep(10);
                    }
                }
                assertEquals(clientMsg.length, totalRead);
                var readBytes = dstSeg.toArray(ValueLayout.JAVA_BYTE);
                assertArrayEquals(clientMsg, readBytes);
            }

            var serverReply = "Pong from server via MemorySegment!".getBytes(StandardCharsets.UTF_8);
            try (var arena = Arena.ofConfined()) {
                var replySeg = arena.allocateFrom(
                        ValueLayout.JAVA_BYTE,
                        serverReply
                );
                var written = ctx.connectionWrite(
                        serverConnId,
                        replySeg,
                        serverReply.length
                );
                assertEquals(serverReply.length, written.bytes());

                var clientDstSeg = arena.allocate(serverReply.length);
                var totalClientRead = 0;
                var readDeadline = Instant.now().plus(Duration.ofSeconds(5));
                while (Instant.now().isBefore(readDeadline)
                        && totalClientRead < serverReply.length
                ) {
                    var n = ctx.connectionRead(
                            clientId,
                            clientDstSeg,
                            serverReply.length
                    );
                    if (n.bytes() > 0) {
                        totalClientRead += n.bytes();
                    } else {
                        Thread.sleep(10);
                    }
                }
                assertEquals(serverReply.length, totalClientRead);
                var clientReadBytes = clientDstSeg.toArray(ValueLayout.JAVA_BYTE);
                assertArrayEquals(serverReply, clientReadBytes);
            }

            ctx.connectionClose(clientId);
            ctx.connectionClose(serverConnId);
            ctx.serverStop(serverId);
        }
    }

    @Test
    void testPoc4RustTokioThreadUpcallToDispatcher() throws Exception {
        try (
                var lib = FfmNativeLibrary.load(nativeLibPath);
                var ctx = lib.createContext(2)
        ) {

            var receivedEvents = new CopyOnWriteArrayList<NativeEvent>();
            var eventLatch = new CountDownLatch(1);

            ctx.dispatcher().addListener(event -> {
                receivedEvents.add(event);
                if (event instanceof NativeEvent.Accepted
                        || event instanceof NativeEvent.ConnectionStateChanged
                ) {
                    eventLatch.countDown();
                }
            });

            var serverId = ctx.serverStart(
                    1,
                    null,
                    0,
                    64,
                    0
            );
            var port = ctx.serverPort(serverId);

            var clientId = ctx.connect(
                    1,
                    "127.0.0.1",
                    port,
                    0
            );

            var received = eventLatch.await(5, TimeUnit.SECONDS);
            assertTrue(
                    received,
                    "Should receive event from Rust Tokio worker thread via FFM upcall stub"
            );
            assertFalse(receivedEvents.isEmpty());

            ctx.connectionClose(clientId);
            ctx.serverStop(serverId);
        }
    }

    @Test
    void testContextLifecycleAndShutdown() {
        try (var lib = FfmNativeLibrary.load(nativeLibPath)) {
            var ctx = lib.createContext(2);
            var serverId = ctx.serverStart(
                    1,
                    null,
                    0,
                    32,
                    0
            );
            var port = ctx.serverPort(serverId);
            var _ = ctx.connect(
                    1,
                    "127.0.0.1",
                    port,
                    0
            );

            ctx.shutdown(1000);
            ctx.destroy();

            assertThrows(
                    NativeException.class,
                    () -> ctx.connect(
                            1,
                            "127.0.0.1",
                            port,
                            0
                    )
            );
        }
    }

}
