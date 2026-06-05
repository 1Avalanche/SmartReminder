package com.anastasiyaa.smartreminder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val history = ApiSample.history
    val clipboard = LocalClipboardManager.current
    val stats = remember(history) { history.computeModelStats() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onBack) { Text("← Назад") }
                TextButton(
                    onClick = {
                        val text = buildString {
                            if (stats.isNotEmpty()) {
                                appendLine("=== Аналитика ===")
                                appendLine()
                                append(stats.statsAsPlainText())
                                appendLine()
                                appendLine()
                            }
                            append(history.asPlainText())
                        }
                        clipboard.setText(AnnotatedString(text))
                    },
                    enabled = history.isNotEmpty() || stats.isNotEmpty(),
                ) { Text("Копировать всё") }
            }
            Text("История запросов", style = MaterialTheme.typography.titleLarge)
        }
        if (stats.isNotEmpty()) {
            AnalyticsSection(stats)
        }

        if (history.isEmpty()) {
            Text(
                "Пока пусто.",
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(history.asReversed()) { item ->
                    HistoryItemRow(item)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HistoryItemRow(item: HistoryItem) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionBlock(title = "Ввод пользователя", content = item.prompt)

        val restrictionLines = item.restrictionLines()
        if (restrictionLines.isNotEmpty()) {
            SectionBlock(title = "Ограничения", content = restrictionLines.joinToString("\n"))
        }

        SectionBlock(
            title = "Тело запроса",
            content = item.requestBody,
            monospace = true,
        )

        SectionBlock(title = "Ответ от API", content = item.responseText())
    }
}

@Composable
private fun SectionBlock(
    title: String,
    content: String,
    monospace: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            content,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

private fun HistoryItem.restrictionLines(): List<String> = buildList {
    maxTokens.value?.let { add("max_tokens: $it") }
    temperature?.let { add("temperature: $it") }
    if (answerFormat != AnswerFormat.None) {
        add("answer_format: ${answerFormat.name}")
    }
    maxCharacters.value?.let { add("max_characters: $it") }
    if (stopSequence.value.isNotEmpty()) {
        add("stop: ${stopSequence.value}")
    }
}

private fun HistoryItem.responseText(): String = result.fold(
    onSuccess = { it.content },
    onFailure = { "Error: ${it.message ?: "Unknown error"}" },
)

private fun List<HistoryItem>.asPlainText(): String =
    asReversed().joinToString(separator = "\n\n---\n\n") { item ->
        buildString {
            appendLine("Ввод пользователя:")
            appendLine(item.prompt)
            val restrictions = item.restrictionLines()
            if (restrictions.isNotEmpty()) {
                appendLine()
                appendLine("Ограничения:")
                restrictions.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("Тело запроса:")
            appendLine(item.requestBody)
            appendLine()
            append("Ответ от API:")
            append('\n')
            append(item.responseText())
        }
    }

private fun List<ModelStats>.statsAsPlainText(): String {
    if (isEmpty()) return ""
    return buildString {
        appendLine("По времени ответа (от быстрых к медленным):")
        sortedBy { it.avgTimeMs }.forEachIndexed { i, s ->
            appendLine("  ${i + 1}. ${s.modelName.modelDisplayName()} — ${"%.1f".format(s.avgTimeMs / 1000.0)}с")
        }
        appendLine()
        appendLine("По токенам (от меньших к большим):")
        sortedBy { it.avgTokens }.forEachIndexed { i, s ->
            appendLine("  ${i + 1}. ${s.modelName.modelDisplayName()} — ${"%.0f".format(s.avgTokens)}")
        }
        appendLine()
        appendLine("По стоимости (от дешёвых к дорогим):")
        sortedBy { it.avgCost }.forEachIndexed { i, s ->
            appendLine("  ${i + 1}. ${s.modelName.modelDisplayName()} — ${"%.6f".format(s.avgCost)}\$")
        }
    }
}

private data class ModelStats(
    val modelName: String,
    val avgTimeMs: Double,
    val avgTokens: Double,
    val avgCost: Double,
)

private fun List<HistoryItem>.computeModelStats(): List<ModelStats> {
    val groups = filter { it.result.isSuccess }
        .mapNotNull { item ->
            val resp = item.result.getOrNull() ?: return@mapNotNull null
            val cost = resp.cost?.toDoubleOrNull() ?: return@mapNotNull null
            resp to cost
        }
        .groupBy { (resp, _) -> resp.model }

    if (groups.isEmpty()) return emptyList()

    return groups.map { (model, entries) ->
        val times = entries.map { it.first.elapsedMs.toDouble() }
        val tokens = entries.mapNotNull { it.first.totalTokens?.toDouble() }
        val costs = entries.map { it.second }
        ModelStats(
            modelName = model,
            avgTimeMs = times.average(),
            avgTokens = if (tokens.isEmpty()) 0.0 else tokens.average(),
            avgCost = costs.average(),
        )
    }
}

private fun String.modelDisplayName(): String {
    val model = Model.values().find { it.modelId == this }
    return model?.name ?: substringAfterLast('/')
}

@Composable
private fun AnalyticsSection(stats: List<ModelStats>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Аналитика", style = MaterialTheme.typography.titleSmall)

        RankingBlock(
            title = "По времени ответа:",
            items = stats.sortedBy { it.avgTimeMs },
            format = { "%.1fс".format(it.avgTimeMs / 1000.0) },
        )
        RankingBlock(
            title = "По токенам:",
            items = stats.sortedBy { it.avgTokens },
            format = { "%.0f".format(it.avgTokens) },
        )
        RankingBlock(
            title = "По стоимости:",
            items = stats.sortedBy { it.avgCost },
            format = { "%s\$".format("%.6f".format(it.avgCost)) },
        )
    }
}

@Composable
private fun RankingBlock(
    title: String,
    items: List<ModelStats>,
    format: (ModelStats) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall)
        items.forEachIndexed { index, stat ->
            Text(
                "${index + 1}. ${stat.modelName.modelDisplayName()} — ${format(stat)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
