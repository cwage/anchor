package com.anchor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anchor.app.ui.screens.AddHostScreen
import com.anchor.app.ui.screens.HostKeyDialog
import com.anchor.app.ui.screens.HostListScreen
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
        is UiState.HostList -> state.hostKeyPrompt
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

    // Show password prompt dialog
    if (uiState is UiState.HostList && (uiState as UiState.HostList).passwordPrompt) {
        PasswordPromptDialog(
            onSubmit = viewModel::submitPassword,
            onDismiss = viewModel::dismissPasswordPrompt
        )
    }

    when (val state = uiState) {
        is UiState.HostList -> {
            HostListScreen(
                hosts = state.hosts,
                onHostTap = viewModel::connectToHost,
                onAddHost = viewModel::openAddHost,
                onDeleteHost = viewModel::deleteHost,
                onEditHost = viewModel::editHost,
                onKeySetup = viewModel::openKeySetup,
                hasKey = state.hasKey,
                isConnecting = state.isConnecting,
                connectingHostId = state.connectingHostId,
                error = state.error
            )
        }
        is UiState.AddHost -> {
            BackHandler { viewModel.goHome() }
            AddHostScreen(
                onSave = viewModel::saveHost,
                onBack = viewModel::goHome,
                editingHost = state.editingHost
            )
        }
        is UiState.KeySetup -> {
            BackHandler { viewModel.goHome() }
            KeySetupScreen(
                hasKey = state.hasKey,
                publicKey = state.publicKey,
                onGenerateKey = viewModel::generateKey,
                onDeployKey = viewModel::deployKey,
                isGenerating = state.isGenerating,
                isDeploying = state.isDeploying,
                error = state.error,
                deploySuccess = state.deploySuccess,
                hosts = state.hosts,
                onBack = viewModel::goHome
            )
        }
        is UiState.SessionList -> {
            BackHandler { viewModel.disconnect() }
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
            BackHandler { viewModel.closeSession() }
            SessionViewScreen(
                sessionName = state.sessionName,
                paneContent = state.paneContent,
                onSendKeys = viewModel::sendKeys,
                onResizePane = viewModel::resizePane,
                onBack = viewModel::closeSession
            )
        }
    }
}

@Composable
fun PasswordPromptDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password Required") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(password) },
                enabled = password.isNotBlank()
            ) { Text("Connect") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
