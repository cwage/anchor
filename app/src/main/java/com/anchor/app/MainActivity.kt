package com.anchor.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anchor.app.ui.screens.AddHostScreen
import com.anchor.app.ui.screens.HostKeyDialog
import com.anchor.app.ui.screens.HostListScreen
import com.anchor.app.ui.screens.KeySetupScreen
import com.anchor.app.ui.screens.SessionListScreen
import com.anchor.app.ui.screens.SessionViewScreen
import com.anchor.app.ui.theme.AnchorTheme

class MainActivity : FragmentActivity() {
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
    val biometricRequest by viewModel.biometricRequest.collectAsState()

    // Handle biometric prompt
    BiometricGate(
        request = biometricRequest,
        onSuccess = { cipher -> viewModel.onBiometricSuccess(cipher) },
        onError = { error -> viewModel.onBiometricError(error) },
        onCancelled = { viewModel.onBiometricCancelled() }
    )

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
                onNextWindow = viewModel::nextWindow,
                onPreviousWindow = viewModel::previousWindow,
                onBack = viewModel::closeSession
            )
        }
    }
}

@Composable
fun BiometricGate(
    request: BiometricRequest?,
    onSuccess: (javax.crypto.Cipher) -> Unit,
    onError: (String) -> Unit,
    onCancelled: () -> Unit
) {
    if (request == null) return

    val activity = LocalContext.current as FragmentActivity

    DisposableEffect(request) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val cipher = result.cryptoObject?.cipher
                    if (cipher != null) {
                        onSuccess(cipher)
                    } else {
                        onError("Authentication succeeded but cipher unavailable")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        onCancelled()
                    } else {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // Individual attempt failed; prompt stays open for retry
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Anchor")
            .setSubtitle(request.subtitle)
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(request.cipher))

        onDispose {
            prompt.cancelAuthentication()
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
