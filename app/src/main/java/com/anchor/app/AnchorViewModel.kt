package com.anchor.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.app.ssh.HostKeyInfo
import com.anchor.app.ssh.HostKeyStore
import com.anchor.app.ssh.KeyManager
import com.anchor.app.ssh.SshManager
import com.anchor.app.ssh.TmuxSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val isChanged: Boolean,
    val oldFingerprint: String? = null
)

sealed class UiState {
    data class Connect(
        val isConnecting: Boolean = false,
        val error: String? = null,
        val hasKey: Boolean = false,
        val hostKeyPrompt: HostKeyPrompt? = null
    ) : UiState()

    data class KeySetup(
        val hasKey: Boolean = false,
        val publicKey: String? = null,
        val isGenerating: Boolean = false,
        val isDeploying: Boolean = false,
        val error: String? = null,
        val deploySuccess: Boolean = false,
        val hostKeyPrompt: HostKeyPrompt? = null
    ) : UiState()

    data class SessionList(
        val sessions: List<TmuxSession> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val hostLabel: String = ""
    ) : UiState()

    data class SessionView(
        val sessionName: String,
        val paneContent: String = "",
    ) : UiState()
}

class AnchorViewModel(application: Application) : AndroidViewModel(application) {
    private val keyManager = KeyManager(application)
    private val hostKeyStore = HostKeyStore(application)
    private val ssh = SshManager().also {
        it.keyManager = keyManager
        it.hostKeyStore = hostKeyStore
    }
    private val _uiState = MutableStateFlow<UiState>(
        UiState.Connect(hasKey = keyManager.hasKey())
    )
    val uiState: StateFlow<UiState> = _uiState

    private var pollJob: Job? = null
    private var hostLabel: String = ""

    private var pendingHost: String = ""
    private var pendingPort: Int = 22
    private var pendingUsername: String = ""
    private var pendingPassword: String? = null
    private var pendingAction: String = ""

    fun connect(host: String, port: Int, username: String, password: String) {
        pendingHost = host
        pendingPort = port
        pendingUsername = username
        pendingPassword = password.ifBlank { null }
        pendingAction = "connect"

        viewModelScope.launch {
            _uiState.value = UiState.Connect(isConnecting = true, hasKey = keyManager.hasKey())
            doConnect(false)
        }
    }

    private suspend fun doConnect(hostKeyAccepted: Boolean) {
        when (val result = ssh.connect(pendingHost, pendingPort, pendingUsername, pendingPassword, hostKeyAccepted)) {
            is SshManager.ConnectResult.Success -> {
                hostLabel = "$pendingUsername@$pendingHost"
                _uiState.value = UiState.SessionList(isLoading = true, hostLabel = hostLabel)
                refreshSessions()
            }
            is SshManager.ConnectResult.NeedsHostKeyConfirmation -> {
                val info = result.info
                val prompt = HostKeyPrompt(info.host, info.port, info.keyType, info.fingerprint, info.isChanged, info.oldFingerprint)
                when (pendingAction) {
                    "connect" -> _uiState.value = UiState.Connect(hasKey = keyManager.hasKey(), hostKeyPrompt = prompt)
                    "deploy" -> _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), hostKeyPrompt = prompt)
                }
            }
            is SshManager.ConnectResult.Failed -> {
                when (pendingAction) {
                    "connect" -> _uiState.value = UiState.Connect(error = result.error, hasKey = keyManager.hasKey())
                    "deploy" -> _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), error = result.error)
                }
            }
        }
    }

    fun acceptHostKey() {
        val prompt = when (val state = _uiState.value) {
            is UiState.Connect -> state.hostKeyPrompt
            is UiState.KeySetup -> state.hostKeyPrompt
            else -> null
        } ?: return

        hostKeyStore.saveFingerprint(prompt.host, prompt.port, prompt.fingerprint)

        viewModelScope.launch {
            when (pendingAction) {
                "connect" -> {
                    _uiState.value = UiState.Connect(isConnecting = true, hasKey = keyManager.hasKey())
                    doConnect(true)
                }
                "deploy" -> {
                    _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), isDeploying = true)
                    doDeploy(true)
                }
            }
        }
    }

    fun rejectHostKey() {
        val error = "Connection rejected: host key not trusted"
        when (pendingAction) {
            "connect" -> _uiState.value = UiState.Connect(error = error, hasKey = keyManager.hasKey())
            "deploy" -> _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), error = error)
        }
    }

    fun openKeySetup() {
        _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString())
    }

    fun closeKeySetup() {
        _uiState.value = UiState.Connect(hasKey = keyManager.hasKey())
    }

    fun generateKey() {
        viewModelScope.launch {
            val current = _uiState.value
            if (current is UiState.KeySetup) {
                _uiState.value = current.copy(isGenerating = true, error = null)
            }
            val result = keyManager.generateKey()
            result.fold(
                onSuccess = { pubKey ->
                    _uiState.value = UiState.KeySetup(hasKey = true, publicKey = pubKey)
                },
                onFailure = { e ->
                    Log.e("Anchor", "Key generation failed", e)
                    _uiState.value = UiState.KeySetup(
                        hasKey = keyManager.hasKey(),
                        publicKey = keyManager.getPublicKeyString(),
                        error = e.message ?: "Key generation failed"
                    )
                }
            )
        }
    }

    fun deployKey(host: String, port: Int, username: String, password: String) {
        pendingHost = host
        pendingPort = port
        pendingUsername = username
        pendingPassword = password
        pendingAction = "deploy"

        viewModelScope.launch {
            val current = _uiState.value
            if (current is UiState.KeySetup) {
                _uiState.value = current.copy(isDeploying = true, error = null, deploySuccess = false)
            }
            doDeploy(false)
        }
    }

    private suspend fun doDeploy(hostKeyAccepted: Boolean) {
        // Connect first to verify host key
        when (val result = ssh.connect(pendingHost, pendingPort, pendingUsername, pendingPassword, hostKeyAccepted)) {
            is SshManager.ConnectResult.Success -> {
                // Connected, now deploy the key via the open session
                val deployResult = keyManager.deployKeyViaSession(ssh)
                ssh.disconnect()
                deployResult.fold(
                    onSuccess = {
                        _uiState.value = UiState.KeySetup(hasKey = true, publicKey = keyManager.getPublicKeyString(), deploySuccess = true)
                    },
                    onFailure = { e ->
                        _uiState.value = UiState.KeySetup(hasKey = true, publicKey = keyManager.getPublicKeyString(), error = e.message ?: "Deploy failed")
                    }
                )
            }
            is SshManager.ConnectResult.NeedsHostKeyConfirmation -> {
                val info = result.info
                _uiState.value = UiState.KeySetup(
                    hasKey = keyManager.hasKey(),
                    publicKey = keyManager.getPublicKeyString(),
                    hostKeyPrompt = HostKeyPrompt(info.host, info.port, info.keyType, info.fingerprint, info.isChanged, info.oldFingerprint)
                )
            }
            is SshManager.ConnectResult.Failed -> {
                _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), error = result.error)
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            val current = _uiState.value
            if (current is UiState.SessionList) {
                _uiState.value = current.copy(isLoading = true, error = null)
            }
            val result = ssh.listTmuxSessions()
            result.fold(
                onSuccess = { sessions ->
                    _uiState.value = UiState.SessionList(sessions = sessions, hostLabel = hostLabel)
                },
                onFailure = { e ->
                    _uiState.value = UiState.SessionList(error = e.message ?: "Failed to list sessions", hostLabel = hostLabel)
                }
            )
        }
    }

    fun openSession(session: TmuxSession) {
        _uiState.value = UiState.SessionView(sessionName = session.name)
        startPolling(session.name)
    }

    fun closeSession() {
        stopPolling()
        _uiState.value = UiState.SessionList(isLoading = true, hostLabel = hostLabel)
        refreshSessions()
    }

    fun sendKeys(keys: String) {
        val state = _uiState.value
        if (state is UiState.SessionView) {
            viewModelScope.launch {
                ssh.sendKeys(state.sessionName, keys)
                delay(200)
                capturePane(state.sessionName)
            }
        }
    }

    fun disconnect() {
        stopPolling()
        ssh.disconnect()
        _uiState.value = UiState.Connect(hasKey = keyManager.hasKey())
    }

    private fun startPolling(sessionName: String) {
        stopPolling()
        pollJob = viewModelScope.launch {
            while (true) {
                capturePane(sessionName)
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun capturePane(sessionName: String) {
        val result = ssh.capturePane(sessionName)
        result.onSuccess { content ->
            val state = _uiState.value
            if (state is UiState.SessionView && state.sessionName == sessionName) {
                _uiState.value = state.copy(paneContent = content)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        ssh.disconnect()
    }
}
