package top.tangge233.netbridge.benchmarkreport.model

data class ReportMeta(
    val task: String,
    val suite: String,
    val suiteTitle: String,
    val executedAt: String,
    val resultRows: Int? = null,
    val benchmarkCaseCount: Int? = null,
    val reportPath: String
)
