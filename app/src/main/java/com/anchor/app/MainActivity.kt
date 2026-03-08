package com.anchor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anchor.app.ui.screens.ConnectScreen
import com.anchor.app.ui.screens.HostKeyDialog
import com.anchor.app.ui.screens.KeySetupScreen
import com.anchor.app.ui.screens.SessionListScreen
import com.anchor.app.ui.screens.SessionViewScreen
import com.anchor.app.ui.theme.AnchorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AnchorTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AnchorApp()
                }
            }
        }
    }
}

@Composable
fun AnchorApp(viewModel: AnchorViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    // Show host key dialog if any state has a prompt
    val hostKeyPrompt = when (val state = uiState) {
        is UiState.Connect -> state.hostKeyPrompt
        is UiState.KeySetup -> state.hostKeyPrompt
        else -> null
    }
    if (hostKeyPrompt != null) {
        HostKeyDialog(
            prompt = hostKeyPrompt,
            onAccept = viewModel::acceptHostKey,
            onReject = viewModel::rejectHostKey
        )
    }

    when (val state = uiState) {
        is UiState.Connect -> {
            ConnectScreen(
                onConnect = viewModel::connect,
                isConnecting = state.isConnecting,
                error = state.error,
                hasKey = state.hasKey,
                onKeySetup = viewModel::openKeySetup
            )
        }
        is UiState.KeySetup -> {
            KeySetupScreen(
                hasKey = state.hasKey,
                publicKey = state.publicKey,
                onGenerateKey = viewModel::generateKey,
                onDeployKey = viewModel::deployKey,
                isGenerating = state.isGenerating,
                isDeploying = state.isDeploying,
                error = state.error,
                deploySuccess = state.deploySuccess,
                onBack = viewModel::closeKeySetup
            )
        }
        is UiState.SessionList -> {
            SessionListScreen(
                sessions = state.sessions,
                isLoading = state.isLoading,
                error = state.error,
                hostLabel = state.hostLabel,
                onSessionTap = viewModel::openSession,
                onRefresh = viewModel::refreshSessions,
                onDisconnect = viewModel::disconnect
            )
        }
        is UiState.SessionView -> {
            SessionViewScreen(
                sessionName = state.sessionName,
                paneContent = state.paneContent,
                onSendKeys = viewModel::sendKeys,
                onBack = viewModel::closeSession
            )
        }
    }
}
