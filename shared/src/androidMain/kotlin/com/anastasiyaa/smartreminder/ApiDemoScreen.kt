package com.anastasiyaa.smartreminder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Success(val post: Post) : UiState
    data class Error(val message: String) : UiState
}

@Composable
fun ApiDemoScreen() {
    var state by remember { mutableStateOf<UiState>(UiState.Idle) }
    val scope = rememberCoroutineScope()

    fun load() {
        state = UiState.Loading
        scope.launch {
            state = ApiSample.fetchPost()
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it.message ?: "Unknown error") },
                )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PaddingValues(16.dp))
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OkHttp sample", style = MaterialTheme.typography.titleLarge)
        Text("GET https://jsonplaceholder.typicode.com/posts/1", style = MaterialTheme.typography.bodySmall)
        Button(onClick = ::load, enabled = state !is UiState.Loading) {
            Text("Fetch post")
        }
        when (val current = state) {
            is UiState.Idle -> Text("Tap the button to load a post.")
            is UiState.Loading -> Text("Loading...")
            is UiState.Error -> Text("Error: ${current.message}", color = MaterialTheme.colorScheme.error)
            is UiState.Success -> {
                Text("id: ${current.post.id}  userId: ${current.post.userId}")
                Text(current.post.title, style = MaterialTheme.typography.titleMedium)
                Text(current.post.body)
            }
        }
    }
}
