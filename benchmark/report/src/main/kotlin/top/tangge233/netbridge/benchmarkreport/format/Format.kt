package top.tangge233.netbridge.benchmarkreport.format

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.floor

object HumanUnits {

    const val MISSING = "\u2014"

    fun nanos(value: Double): String {
        if (!value.isFinite()) return MISSING
        val absValue = abs(value)
        return when {
            absValue < 1_000.0 -> "%.2f ns".format(value)
            absValue < 1_000_000.0 -> "%.2f \u00b5s".format(value / 1_000.0)
            absValue < 1_000_000_000.0 -> "%.2f ms".format(value / 1_000_000.0)
            else -> "%.2f s".format(value / 1_000_000_000.0)
        }
    }

    fun bytes(value: Long): String {
        val absValue = abs(value)
        return when {
            absValue < 1024L -> "$value B"
            absValue < 1024L * 1024L -> scaled(value, 1024.0, "KiB")
            absValue < 1024L * 1024L * 1024L -> scaled(value, 1024.0 * 1024.0, "MiB")
            else -> scaled(value, 1024.0 * 1024.0 * 1024.0, "GiB")
        }
    }

    private fun scaled(
        value: Long,
        divisor: Double,
        unit: String
    ): String {
        val scaled = value / divisor
        return if (scaled == floor(scaled)) "${scaled.toLong()} $unit" else "%.2f $unit".format(scaled)
    }

    fun throughput(mibPerSecond: Double): String =
        if (mibPerSecond.isFinite()) "%.2f MiB/s".format(mibPerSecond) else MISSING

    fun percent(value: Double): String =
        if (value.isFinite()) "%.2f%%".format(value) else MISSING

    fun decimal(value: Double): String =
        if (value.isFinite()) "%.2f".format(value) else MISSING

    fun integer(value: Long): String = value.toString()

    fun rawTitle(value: Double): String = when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
        value == value.toLong().toDouble() -> value.toLong().toString()
        else -> BigDecimal.valueOf(value).toPlainString()
    }

}
