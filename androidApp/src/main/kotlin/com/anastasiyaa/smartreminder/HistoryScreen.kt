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
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val history = ApiSample.history
    val clipboard = LocalClipboardManager.current

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
                        clipboard.setText(AnnotatedString(history.asPlainText()))
                    },
                    enabled = history.isNotEmpty(),
                ) { Text("Копировать всё") }
            }
            Text("История запросов", style = MaterialTheme.typography.titleLarge)
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
    temperature.value?.let { add("temperature: $it") }
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
