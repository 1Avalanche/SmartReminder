package com.anastasiyaa.smartreminder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Success(val response: ChatResponse) : UiState
    data class Error(val message: String) : UiState
}

@Composable
fun ApiDemoScreen() {
    var prompt by remember { mutableStateOf("Say hello in one short sentence.") }
    var state by remember { mutableStateOf<UiState>(UiState.Idle) }
    val scope = rememberCoroutineScope()

    fun ask() {
        if (prompt.isBlank()) return
        state = UiState.Loading
        scope.launch {
            state = ApiSample.ask(prompt)
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it.message ?: "Unknown error") },
                )
        }
    }

    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .padding(top = 80.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("DeepSeek sample", style = MaterialTheme.typography.titleLarge)
            Text("POST https://api.deepseek.com/v1/chat/completions", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is UiState.Loading,
            )
            Button(
                onClick = ::ask,
                enabled = state !is UiState.Loading && prompt.isNotBlank(),
            ) {
                Text("Ask DeepSeek")
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
                }
            }
        }
    }
}
