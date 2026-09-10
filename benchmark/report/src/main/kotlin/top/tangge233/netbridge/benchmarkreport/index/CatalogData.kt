package top.tangge233.netbridge.benchmarkreport.index

data class CatalogRow(
    val task: String,
    val suite: String,
    val executedAt: String,
    val reportPath: String
)

data class CatalogData(
    val rows: List<CatalogRow>,
    val generatedAt: String
)
