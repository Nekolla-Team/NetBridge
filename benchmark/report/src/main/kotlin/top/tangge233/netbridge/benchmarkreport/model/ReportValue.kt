package top.tangge233.netbridge.benchmarkreport.model

import top.tangge233.netbridge.benchmarkreport.format.HumanUnits

sealed interface ReportValue {

    fun text(): String

    val raw: String?
    val numeric: Boolean get() = false
    val code: Boolean get() = false

}

data class TextValue(val value: String) : ReportValue {

    override fun text(): String = value

    override val raw: String? = null

}

data class CodeValue(val value: String) : ReportValue {

    override fun text(): String = value

    override val raw: String = value
    override val code: Boolean get() = true

}

data class IntegerValue(val value: Long) : ReportValue {

    override fun text(): String = HumanUnits.integer(value)

    override val raw: String = value.toString()
    override val numeric: Boolean get() = true

}

data class DecimalValue(val value: Double) : ReportValue {

    override fun text(): String = HumanUnits.decimal(value)

    override val raw: String = HumanUnits.rawTitle(value)
    override val numeric: Boolean get() = true

}

data class SignedDecimalValue(val value: Double) : ReportValue {

    override fun text(): String = "\u00b1 ${HumanUnits.decimal(value)}"

    override val raw: String = HumanUnits.rawTitle(value)
    override val numeric: Boolean get() = true

}

data class DurationValue(val nanos: Double) : ReportValue {

    override fun text(): String = HumanUnits.nanos(nanos)

    override val raw: String = HumanUnits.rawTitle(nanos)
    override val numeric: Boolean get() = true

    companion object {

        fun ofNanos(value: Long): DurationValue = DurationValue(value.toDouble())

    }

}

data class BytesValue(val bytes: Long) : ReportValue {

    override fun text(): String = HumanUnits.bytes(bytes)

    override val raw: String? = null

}

data class ThroughputValue(val mibPerSecond: Double) : ReportValue {

    override fun text(): String = HumanUnits.throughput(mibPerSecond)

    override val raw: String = HumanUnits.rawTitle(mibPerSecond)
    override val numeric: Boolean get() = true

}

data class StatusValue(
    val ok: Boolean,
    val detail: String? = null
) : ReportValue {

    override fun text(): String = if (ok) "verified" else "MISMATCH"

    override val raw: String? = detail

}

data class MissingValue(val label: String = "\u2014") : ReportValue {

    override fun text(): String = label

    override val raw: String? = null

}
