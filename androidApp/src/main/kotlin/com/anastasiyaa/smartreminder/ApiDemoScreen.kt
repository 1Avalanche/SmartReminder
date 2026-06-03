package com.anastasiyaa.smartreminder

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Success(val response: ChatResponse) : UiState
    data class Error(val message: String) : UiState
}

@Composable
fun ApiDemoScreen(onShowHistory: () -> Unit = {}) {
    var prompt by remember { mutableStateOf("") }
    var maxTokens by remember { mutableStateOf(MaxTokens.None) }
    var temperature by remember { mutableStateOf(Temperature.None) }
    var answerFormat by remember { mutableStateOf(AnswerFormat.None) }
    var maxCharacters by remember { mutableStateOf(MaxCharacters.None) }
    var stopSequence by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<UiState>(UiState.Idle) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun ask() {
        if (prompt.isBlank()) return
        state = UiState.Loading
        scope.launch {
            state = ApiSample.ask(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                answerFormat = answerFormat,
                maxCharacters = maxCharacters,
                stopSequence = StopSequence(stopSequence),
            )
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it.message ?: "Unknown error") },
                )
        }
    }

    val loading = state is UiState.Loading

    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .padding(top = 80.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
//            Text(
//                "Введи запрос и параметры-ограничения",
//                style = MaterialTheme.typography.titleLarge,
//            )
//            RestrictionSelector(
//                title = "max_tokens",
//                options = MaxTokens.entries,
//                selected = maxTokens,
//                onSelected = { maxTokens = it },
//                label = { it.value?.toString() ?: "—" },
//            )
//            RestrictionSelector(
//                title = "temperature",
//                options = Temperature.entries,
//                selected = temperature,
//                onSelected = { temperature = it },
//                label = { it.value?.toString() ?: "—" },
//            )
//            RestrictionSelector(
//                title = "answer_format",
//                options = AnswerFormat.entries,
//                selected = answerFormat,
//                onSelected = { answerFormat = it },
//                label = { if (it.query == null) "—" else it.name },
//            )
//            RestrictionSelector(
//                title = "max_characters",
//                options = MaxCharacters.entries,
//                selected = maxCharacters,
//                onSelected = { maxCharacters = it },
//                label = { it.value?.toString() ?: "—" },
//            )
//            OutlinedTextField(
//                value = stopSequence,
//                onValueChange = { stopSequence = it },
//                label = { Text("стоп-слово") },
//                modifier = Modifier.fillMaxWidth(),
//                enabled = !loading,
//                trailingIcon = if (stopSequence.isNotEmpty()) {
//                    { ClearInputButton(onClick = { stopSequence = "" }) }
//                } else null,
//            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                trailingIcon = if (prompt.isNotEmpty()) {
                    { ClearInputButton(onClick = { prompt = "" }) }
                } else null,
            )
            Button(
                onClick = ::ask,
                enabled = !loading && prompt.isNotBlank(),
            ) {
                Text("Ask DeepSeek")
            }
            OutlinedButton(onClick = onShowHistory) {
                Text("Показать историю запросов")
            }
            when (val current = state) {
                is UiState.Idle -> Text("Type a prompt and tap Ask DeepSeek.")
                is UiState.Loading -> Text("Loading...")
                is UiState.Error -> Text("Error: ${current.message}", color = MaterialTheme.colorScheme.error)
                is UiState.Success -> {
                    if (current.response.model.isNotEmpty()) {
                        Text("model: ${current.response.model}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(current.response.content)
                    if (current.response.content.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(current.response.content))
                            },
                        ) {
                            Text("Копировать ответ")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClearInputButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(24.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text("x")
    }
}

@Composable
private fun <T> RestrictionSelector(
    title: String,
    options: Iterable<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
}
