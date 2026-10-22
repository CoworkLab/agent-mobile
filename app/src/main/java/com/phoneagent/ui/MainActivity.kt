package com.phoneagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoneagent.PhoneAgentApp
import com.phoneagent.ui.logs.LogViewerScreen
import com.phoneagent.ui.logs.LogViewerViewModel
import com.phoneagent.ui.theme.PhoneAgentTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val logger = PhoneAgentApp.logger()

        setContent {
            PhoneAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val vm: LogViewerViewModel = viewModel(
                        factory = LogViewerViewModel.factory(logger, applicationContext),
                    )
                    LogViewerScreen(viewModel = vm)
                }
            }
        }
    }
}
