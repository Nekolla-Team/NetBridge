package top.tangge233.netbridge.benchmarkreport.dsl

import top.tangge233.netbridge.benchmarkreport.model.*
import java.time.Instant

@DslMarker
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION
)
annotation class ReportDsl

data class KeyValue(
    val key: String,
    val value: String
)

data class Cell(
    val label: String,
    val value: ReportValue,
    val tooltip: String? = null
) {

    fun text(): String = value.text()

    fun title(): String? = tooltip ?: value.raw

}

@ReportDsl
class SectionBuilder {

    var title: String = ""
    var description: String? = null
    var headers: List<String> = emptyList()
    var rows: List<List<Cell>> = emptyList()
    var collapsible: Boolean = false
    var note: String? = null

    fun build(): Section = Section(title, description, headers, rows, collapsible, note)

}

fun section(block: SectionBuilder.() -> Unit): Section =
    SectionBuilder().apply(block).build()

@ReportDsl
class ReportBuilder {

    var task: String = ""
    var suite: String = ""
    var suiteTitle: String = ""
    var executedAt: String = ""
    var git: String? = null

    private val cards = mutableListOf<Card>()
    private val scopeEntries = mutableListOf<KeyValue>()
    private val sectionList = mutableListOf<Section>()
    private val artifactList = mutableListOf<KeyValue>()

    fun card(
        label: String,
        value: String,
        sub: String? = null
    ) {
        cards += Card(label, value, sub)
    }

    fun scope(
        key: String,
        value: String
    ) {
        scopeEntries += KeyValue(key, value)
    }

    fun table(
        title: String,
        description: String?,
        headers: List<String>,
        rows: List<List<Cell>>
    ) {
        sectionList += Section(title, description, headers, rows)
    }

    fun tableSections(sections: List<Section>) {
        sectionList += sections
    }

    fun keyValues(
        title: String,
        entries: List<KeyValue>,
        note: String? = null
    ) {
        sectionList += Section(
            title = title,
            description = null,
            headers = listOf("Key", "Value"),
            rows = entries.map {
                listOf(
                    Cell(it.key, CodeValue(it.key)),
                    Cell(it.value, TextValue(it.value))
                )
            },
            collapsible = true,
            note = note
        )
    }

    fun artifact(
        name: String,
        description: String
    ) {
        artifactList += KeyValue(name, description)
    }

    fun build(generatedAt: String = Instant.now().toString()): ReportModel =
        ReportModel(
            task = task,
            suite = suite,
            suiteTitle = suiteTitle,
            executedAt = executedAt,
            git = git,
            cards = cards.toList(),
            scope = scopeEntries.toList(),
            sections = sectionList.toList(),
            artifacts = artifactList.toList(),
            generatedAt = generatedAt
        )

}

fun report(block: ReportBuilder.() -> Unit): ReportModel =
    ReportBuilder().apply(block).build()
