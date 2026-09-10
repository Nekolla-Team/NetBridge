package top.tangge233.netbridge.benchmarkreport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.tangge233.netbridge.benchmarkreport.format.HumanUnits

class HumanUnitsTest {

    @Test
    fun `nanos golden values`() {
        assertEquals("1.24 ms", HumanUnits.nanos(1_241_392.8))
        assertEquals("426.19 ns", HumanUnits.nanos(426.19034682857927))
        assertEquals("68.51 \u00b5s", HumanUnits.nanos(68_512.51555250224))
        assertEquals("1.07 ms", HumanUnits.nanos(1_067_826.6))
        assertEquals("536.80 ms", HumanUnits.nanos(536_804_070.0))
        assertEquals("933.43 \u00b5s", HumanUnits.nanos(933_432.0))
        assertEquals("1.50 s", HumanUnits.nanos(1_500_000_000.0))
    }

    @Test
    fun `bytes golden values`() {
        assertEquals("0 B", HumanUnits.bytes(0))
        assertEquals("64 B", HumanUnits.bytes(64))
        assertEquals("1 KiB", HumanUnits.bytes(1024))
        assertEquals("4 KiB", HumanUnits.bytes(4096))
        assertEquals("2.64 KiB", HumanUnits.bytes(2706))
        assertEquals("64 KiB", HumanUnits.bytes(65536))
        assertEquals("69.31 MiB", HumanUnits.bytes(72_679_424))
    }

    @Test
    fun `rate percent and decimal formatting`() {
        assertEquals("45.70 MiB/s", HumanUnits.throughput(45.69779561099469))
        assertEquals("0.00 MiB/s", HumanUnits.throughput(0.004807420145296799))
        assertEquals("12.50%", HumanUnits.percent(12.5))
        assertEquals("426.19", HumanUnits.decimal(426.19034682857927))
        assertEquals("5", HumanUnits.integer(5))
        assertEquals("-5", HumanUnits.integer(-5))
    }

    @Test
    fun `non finite values display dash but keep raw`() {
        assertEquals("\u2014", HumanUnits.nanos(Double.NaN))
        assertEquals("\u2014", HumanUnits.nanos(Double.POSITIVE_INFINITY))
        assertEquals("\u2014", HumanUnits.decimal(Double.NaN))
        assertEquals("\u2014", HumanUnits.throughput(Double.NaN))
        assertEquals("NaN", HumanUnits.rawTitle(Double.NaN))
        assertEquals("Infinity", HumanUnits.rawTitle(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", HumanUnits.rawTitle(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `raw title avoids scientific notation`() {
        assertEquals("1241392.8", HumanUnits.rawTitle(1_241_392.8))
        assertEquals("1099988", HumanUnits.rawTitle(1_099_988.0))
        assertEquals("426.19034682857927", HumanUnits.rawTitle(426.19034682857927))
        assertEquals("536804070", HumanUnits.rawTitle(536_804_070.0))
    }

}
