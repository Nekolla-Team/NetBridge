package top.tangge233.netbridge.benchmarkreport.model

import top.tangge233.netbridge.benchmarkreport.dsl.Cell
import top.tangge233.netbridge.benchmarkreport.dsl.KeyValue

data class Card(
    val label: String,
    val value: String,
    val sub: String? = null
)

data class Section(
    val title: String,
    val description: String?,
    val headers: List<String>,
    val rows: List<List<Cell>>,
    val collapsible: Boolean = false,
    val note: String? = null
)

data class ReportModel(
    val task: String,
    val suite: String,
    val suiteTitle: String,
    val executedAt: String,
    val git: String?,
    val cards: List<Card>,
    val scope: List<KeyValue>,
    val sections: List<Section>,
    val artifacts: List<KeyValue>,
    val generatedAt: String
) {

    val title: String get() = task

}
