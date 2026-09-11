package top.tangge233.netbridge.benchmarkreport.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.core.type.TypeReference
import top.tangge233.netbridge.benchmark.model.BenchmarkJson

@JsonIgnoreProperties(ignoreUnknown = true)
data class JmhResultDto(
    val benchmark: String,
    val mode: String = "",
    val threads: Int = 1,
    val forks: Int = 1,
    val jvm: String? = null,
    val jvmArgs: List<String> = emptyList(),
    val jdkVersion: String? = null,
    val vmName: String? = null,
    val vmVersion: String? = null,
    val warmupIterations: Int = 0,
    val warmupTime: String = "",
    val warmupBatchSize: Int = 1,
    val measurementIterations: Int = 0,
    val measurementTime: String = "",
    val measurementBatchSize: Int = 1,
    val params: Map<String, String> = emptyMap(),
    val primaryMetric: JmhPrimaryMetricDto? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JmhPrimaryMetricDto(
    val score: Double = Double.NaN,
    val scoreError: Double = Double.NaN,
    val scoreConfidence: List<Double> = emptyList(),
    val scorePercentiles: Map<String, Double> = emptyMap(),
    val scoreUnit: String = "",
    val rawData: List<List<Double>> = emptyList()
)

object JmhJson {

    fun read(text: String): List<JmhResultDto> =
        BenchmarkJson.mapper.readValue(
            text,
            object : TypeReference<List<JmhResultDto>>() {}
        )

    fun looksLikeJmh(text: String): Boolean =
        text.trimStart().startsWith("[")

}
