package com.anastasiyaa.smartreminder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            var showHistory by remember { mutableStateOf(false) }
            if (showHistory) {
                HistoryScreen(onBack = { showHistory = false })
            } else {
                ApiDemoScreen(onShowHistory = { showHistory = true })
            }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    ApiDemoScreen()
}