package team.nekolla.netbridge.benchmark.workload

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MinecraftWorkloadTest {

    @Test
    fun movementWorkloadSizes() {
        val movement = BuiltinMinecraftWorkloads.MOVEMENT
        assertEquals(20 * 12L + 10 * 16L, movement.totalBytes)
        assertEquals(30L, movement.totalMessages)
        assertTrue(movement.hasSmallMessages)
    }

    @Test
    fun chunkBurstIsNotSmallMessageHeavy() {
        val chunk = BuiltinMinecraftWorkloads.CHUNK_BURST
        assertTrue(chunk.name.isNotEmpty())
        assertEquals(
            256 * 32768L + 128 * 65536L + 256 * 32L,
            chunk.totalBytes
        )
    }

    @Test
    fun builtinLookupAndValidation() {
        assertEquals(
            "mixed_play",
            BuiltinMinecraftWorkloads.byName("MIXED_PLAY").name
        )
        assertThrows(IllegalArgumentException::class.java) {
            BuiltinMinecraftWorkloads.byName("does-not-exist")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkloadStep(
                WorkloadStep.Direction.CLIENT_TO_SERVER,
                0,
                0L,
                1
            )
        }
    }

}
