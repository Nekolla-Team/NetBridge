package team.nekolla.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import team.nekolla.netbridge.nativebridge.NativeTransportKind;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-language smoke test for the ABI v1.1 shared-ring IO descriptor and kick.
 *
 * <p>This stage only proves the descriptor is exposed and validated, that kick is accepted, and
 * that
 * the legacy ABI is rejected once direct access is claimed. It intentionally does not read or write
 * through the mapped rings yet.
 */
class FfmSharedIoSmokeTest {

    private static Path nativeLibPath;

    @BeforeAll
    static void setUp() {
        nativeLibPath = FfmTestSupport.requireNativeLibraryOrSkip();
    }

    @Test
    void sharedIoDescriptorIsExposedValidatedAndKicked() throws Exception {
        try (var backend = FfmNativeTransportBackend.load(nativeLibPath, 2)) {
            var ctx = backend.context();
            assertTrue(
                    ctx.supportsSharedRingIo(),
                    "new native must advertise NB_FEATURE_SHARED_RING_IO"
            );

            var quic = FfmAbiCodec.transportToAbi(NativeTransportKind.QUIC);
            var serverId = ctx.serverStart(
                    quic,
                    null,
                    0,
                    8,
                    0
            );
            assertNotEquals(0L, serverId);
            var port = ctx.serverPort(serverId);
            assertTrue(port > 0);

            var clientId = ctx.connect(quic, "127.0.0.1", port, 0);
            assertNotEquals(0L, clientId);

            var deadline = System.currentTimeMillis() + 10_000;
            while (ctx.connectionState(clientId) != FfmAbiCodec.CONNECTION_STATE_CONNECTED
                    && System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(10);
            }
            assertEquals(
                    FfmAbiCodec.CONNECTION_STATE_CONNECTED,
                    ctx.connectionState(clientId)
            );

            var region = ctx.connectionIoRegion(clientId);
            assertEquals(FfmSharedIoRegion.LAYOUT_VERSION_1_0, region.layoutVersion());
            assertTrue(region.rustOwned());
            assertNotEquals(0L, region.txBase());
            assertNotEquals(0L, region.rxBase());
            assertEquals(1, Long.bitCount(region.txCapacity()));
            assertEquals(1, Long.bitCount(region.rxCapacity()));
            assertTrue(region.txCapacity() >= FfmSharedIoRegion.RING_MIN_CAPACITY);
            assertTrue(region.rxCapacity() >= FfmSharedIoRegion.RING_MIN_CAPACITY);
            assertEquals(
                    FfmSharedIoRegion.RING_HEADER_BYTES + region.txCapacity(),
                    region.txTotalBytes()
            );
            assertEquals(
                    FfmSharedIoRegion.RING_HEADER_BYTES + region.rxCapacity(),
                    region.rxTotalBytes()
            );

            // Re-querying the descriptor on a shared-direct connection is idempotent.
            var reQueried = ctx.connectionIoRegion(clientId);
            assertEquals(region.txBase(), reQueried.txBase());
            assertEquals(region.rxBase(), reQueried.rxBase());

            ctx.connectionIoKick(
                    clientId,
                    FfmSharedIoRegion.KICK_TX_DATA | FfmSharedIoRegion.KICK_RX_SPACE
            );

            // Direct mode has been claimed: the legacy write path must fail rather than corrupt the
            // single-producer/single-consumer contract.
            try (var arena = Arena.ofConfined()) {
                var segment = arena.allocateFrom(ValueLayout.JAVA_BYTE, new byte[]{1});
                assertThrows(
                        IllegalStateException.class,
                        () -> ctx.connectionWrite(clientId, segment, 1)
                );
            }

            ctx.connectionClose(clientId);
            ctx.serverStop(serverId);
        }
    }

}
