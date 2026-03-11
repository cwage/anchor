package com.anchor.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.app.data.AppDatabase
import com.anchor.app.data.Host
import com.anchor.app.ssh.BiometricKeyInvalidatedException
import com.anchor.app.ssh.HostKeyInfo
import com.anchor.app.ssh.HostKeyStore
import com.anchor.app.ssh.KeyManager
import com.anchor.app.ssh.SshManager
import com.anchor.app.ssh.TmuxSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val isChanged: Boolean,
    val oldFingerprint: String? = null
)

sealed class BiometricPurpose {
    data object Connect : BiometricPurpose()
    data object Generate : BiometricPurpose()
    data object Migrate : BiometricPurpose()
}

data class BiometricRequest(
    val cipher: Cipher,
    val purpose: BiometricPurpose,
    val subtitle: String
)

sealed class UiState {
    data class HostList(
        val hosts: List<Host> = emptyList(),
        val hasKey: Boolean = false,
        val isConnecting: Boolean = false,
        val connectingHostId: Long? = null,
        val error: String? = null,
        val hostKeyPrompt: HostKeyPrompt? = null,
        val passwordPrompt: Boolean = false
    ) : UiState()

    data class AddHost(val editingHost: Host? = null) : UiState()

    data class KeySetup(
        val hasKey: Boolean = false,
        val publicKey: String? = null,
        val isGenerating: Boolean = false,
        val isDeploying: Boolean = false,
        val error: String? = null,
        val deploySuccess: Boolean = false,
        val hostKeyPrompt: HostKeyPrompt? = null,
        val hosts: List<Host> = emptyList()
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
    val keyManager = KeyManager(application)
    private val hostKeyStore = HostKeyStore(application)
    private val ssh = SshManager().also {
        it.hostKeyStore = hostKeyStore
    }
    private val db = AppDatabase.getInstance(application)
    private val hostDao = db.hostDao()

    private val _uiState = MutableStateFlow<UiState>(UiState.HostList(hasKey = keyManager.hasKey()))
    val uiState: StateFlow<UiState> = _uiState

    private val _biometricRequest = MutableStateFlow<BiometricRequest?>(null)
    val biometricRequest: StateFlow<BiometricRequest?> = _biometricRequest

    private var pollJob: Job? = null
    private var hostLabel: String = ""

    private var pendingHost: Host? = null
    private var pendingPassword: String? = null
    private var pendingAction: String = ""

    // Decrypted key bytes held in memory only during connection flow
    private var decryptedKeyBytes: ByteArray? = null

    init {
        viewModelScope.launch {
            hostDao.getAll().collect { hosts ->
                val current = _uiState.value
                if (current is UiState.HostList) {
                    _uiState.value = current.copy(hosts = hosts, hasKey = keyManager.hasKey())
                }
            }
        }
    }

    fun connectToHost(host: Host, password: String? = null) {
        pendingHost = host
        pendingPassword = password
        pendingAction = "connect"

        viewModelScope.launch {
            _uiState.value = (_uiState.value as? UiState.HostList)?.copy(
                isConnecting = true,
                connectingHostId = host.id,
                error = null,
                passwordPrompt = false
            ) ?: return@launch

            when {
                keyManager.needsMigration() -> requestBiometric(
                    BiometricPurpose.Migrate,
                    "Secure your existing SSH key"
                )
                keyManager.hasEncryptedKey() -> requestBiometric(
                    BiometricPurpose.Connect,
                    "Authenticate to connect to ${host.label}"
                )
                else -> doConnect(false)
            }
        }
    }

    private fun requestBiometric(purpose: BiometricPurpose, subtitle: String) {
        try {
            val cipher = when (purpose) {
                is BiometricPurpose.Connect -> keyManager.getDecryptionCipher()
                is BiometricPurpose.Generate -> keyManager.getEncryptionCipher()
                is BiometricPurpose.Migrate -> keyManager.getEncryptionCipher()
            }
            _biometricRequest.value = BiometricRequest(cipher, purpose, subtitle)
        } catch (e: BiometricKeyInvalidatedException) {
            handleBiometricKeyInvalidated(e.message ?: "Biometric key invalidated")
        } catch (e: Exception) {
            handleBiometricError("Failed to prepare authentication: ${e.message}")
        }
    }

    fun onBiometricSuccess(cipher: Cipher) {
        val request = _biometricRequest.value ?: return
        _biometricRequest.value = null

        viewModelScope.launch {
            try {
                when (request.purpose) {
                    is BiometricPurpose.Connect -> {
                        decryptedKeyBytes = withContext(Dispatchers.IO) {
                            keyManager.decryptPrivateKey(cipher)
                        }
                        doConnect(false)
                    }
                    is BiometricPurpose.Generate -> {
                        val hosts = hostDao.getAll().first()
                        val result = keyManager.generateKey(cipher)
                        result.fold(
                            onSuccess = { pubKey ->
                                _uiState.value = UiState.KeySetup(
                                    hasKey = true,
                                    publicKey = pubKey,
                                    hosts = hosts
                                )
                            },
                            onFailure = { e ->
                                Log.e("Anchor", "Key generation failed", e)
                                _uiState.value = UiState.KeySetup(
                                    hasKey = keyManager.hasKey(),
                                    publicKey = keyManager.getPublicKeyString(),
                                    error = e.message ?: "Key generation failed",
                                    hosts = hosts
                                )
                            }
                        )
                    }
                    is BiometricPurpose.Migrate -> {
                        decryptedKeyBytes = withContext(Dispatchers.IO) {
                            keyManager.migrateKey(cipher)
                        }
                        doConnect(false)
                    }
                }
            } catch (e: Exception) {
                Log.e("Anchor", "Biometric operation failed", e)
                onBiometricError(e.message ?: "Operation failed")
            }
        }
    }

    fun onBiometricError(error: String) {
        _biometricRequest.value = null
        when (val state = _uiState.value) {
            is UiState.HostList -> {
                _uiState.value = state.copy(isConnecting = false, error = error)
            }
            is UiState.KeySetup -> {
                _uiState.value = state.copy(isGenerating = false, error = error)
            }
            else -> {}
        }
    }

    fun onBiometricCancelled() {
        _biometricRequest.value = null
        when (val state = _uiState.value) {
            is UiState.HostList -> {
                _uiState.value = state.copy(isConnecting = false)
            }
            is UiState.KeySetup -> {
                _uiState.value = state.copy(isGenerating = false)
            }
            else -> {}
        }
    }

    private fun clearDecryptedKey() {
        decryptedKeyBytes?.fill(0)
        decryptedKeyBytes = null
    }

    private suspend fun doConnect(hostKeyAccepted: Boolean) {
        val host = pendingHost ?: return
        val result = ssh.connect(
            host.hostname,
            host.port,
            host.username,
            pendingPassword,
            hostKeyAccepted,
            decryptedKeyBytes
        )
        when (result) {
            is SshManager.ConnectResult.Success -> {
                clearDecryptedKey()
                pendingPassword = null
                hostDao.updateLastConnected(host.id, System.currentTimeMillis())
                hostLabel = "${host.username}@${host.hostname}"
                _uiState.value = UiState.SessionList(isLoading = true, hostLabel = host.label)
                refreshSessions()
            }
            is SshManager.ConnectResult.NeedsHostKeyConfirmation -> {
                // Keep decryptedKeyBytes alive for retry after host key acceptance
                val info = result.info
                val prompt = HostKeyPrompt(info.host, info.port, info.keyType, info.fingerprint, info.isChanged, info.oldFingerprint)
                val hosts = hostDao.getAll().first()
                _uiState.value = UiState.HostList(
                    hosts = hosts,
                    hasKey = keyManager.hasKey(),
                    hostKeyPrompt = prompt
                )
            }
            is SshManager.ConnectResult.Failed -> {
                clearDecryptedKey()
                val hosts = hostDao.getAll().first()
                val needsPassword = result.error.contains("Auth fail", ignoreCase = true) && pendingPassword == null
                pendingPassword = null
                _uiState.value = UiState.HostList(
                    hosts = hosts,
                    hasKey = keyManager.hasKey(),
                    error = if (needsPassword) null else result.error,
                    passwordPrompt = needsPassword
                )
            }
        }
    }

    fun submitPassword(password: String) {
        val host = pendingHost ?: return
        connectToHost(host, password)
    }

    fun dismissPasswordPrompt() {
        viewModelScope.launch {
            val hosts = hostDao.getAll().first()
            _uiState.value = UiState.HostList(hosts = hosts, hasKey = keyManager.hasKey())
        }
    }

    fun acceptHostKey() {
        val prompt = when (val state = _uiState.value) {
            is UiState.HostList -> state.hostKeyPrompt
            is UiState.KeySetup -> state.hostKeyPrompt
            else -> null
        } ?: return

        hostKeyStore.saveFingerprint(prompt.host, prompt.port, prompt.fingerprint)

        viewModelScope.launch {
            when (pendingAction) {
                "connect" -> {
                    val hosts = hostDao.getAll().first()
                    _uiState.value = UiState.HostList(
                        hosts = hosts,
                        hasKey = keyManager.hasKey(),
                        isConnecting = true,
                        connectingHostId = pendingHost?.id
                    )
                    doConnect(true)
                }
                "deploy" -> {
                    val hosts = hostDao.getAll().first()
                    _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), isDeploying = true, hosts = hosts)
                    doDeploy(true)
                }
            }
        }
    }

    fun rejectHostKey() {
        clearDecryptedKey()
        viewModelScope.launch {
            val error = "Connection rejected: host key not trusted"
            when (pendingAction) {
                "connect" -> {
                    val hosts = hostDao.getAll().first()
                    _uiState.value = UiState.HostList(hosts = hosts, hasKey = keyManager.hasKey(), error = error)
                }
                "deploy" -> {
                    val hosts = hostDao.getAll().first()
                    _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), error = error, hosts = hosts)
                }
            }
        }
    }

    // Host management
    fun openAddHost() {
        _uiState.value = UiState.AddHost()
    }

    fun editHost(host: Host) {
        _uiState.value = UiState.AddHost(editingHost = host)
    }

    fun saveHost(label: String, hostname: String, port: Int, username: String) {
        viewModelScope.launch {
            val current = _uiState.value
            if (current is UiState.AddHost && current.editingHost != null) {
                hostDao.update(current.editingHost.copy(label = label, hostname = hostname, port = port, username = username))
            } else {
                hostDao.insert(Host(label = label, hostname = hostname, port = port, username = username))
            }
            val hosts = hostDao.getAll().first()
            _uiState.value = UiState.HostList(hosts = hosts, hasKey = keyManager.hasKey())
        }
    }

    fun deleteHost(host: Host) {
        viewModelScope.launch {
            hostDao.delete(host)
        }
    }

    fun goHome() {
        stopPolling()
        ssh.disconnect()
        clearDecryptedKey()
        pendingPassword = null
        viewModelScope.launch {
            val hosts = hostDao.getAll().first()
            _uiState.value = UiState.HostList(hosts = hosts, hasKey = keyManager.hasKey())
        }
    }

    // Key setup
    fun openKeySetup() {
        viewModelScope.launch {
            val hosts = hostDao.getAll().first()
            _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), hosts = hosts)
        }
    }

    fun generateKey() {
        viewModelScope.launch {
            val current = _uiState.value
            if (current is UiState.KeySetup) {
                _uiState.value = current.copy(isGenerating = true, error = null)
            }

            if (!keyManager.isBiometricAvailable()) {
                val hosts = hostDao.getAll().first()
                _uiState.value = UiState.KeySetup(
                    hasKey = keyManager.hasKey(),
                    publicKey = keyManager.getPublicKeyString(),
                    error = "Biometric authentication required. Please set up fingerprint in device settings.",
                    hosts = hosts
                )
                return@launch
            }

            requestBiometric(BiometricPurpose.Generate, "Authenticate to generate SSH key")
        }
    }

    fun deployKey(host: Host, password: String) {
        pendingPassword = password
        pendingAction = "deploy"
        pendingHost = host

        viewModelScope.launch {
            val current = _uiState.value
            if (current is UiState.KeySetup) {
                _uiState.value = current.copy(isDeploying = true, error = null, deploySuccess = false)
            }
            doDeploy(false)
        }
    }

    private suspend fun doDeploy(hostKeyAccepted: Boolean) {
        val host = pendingHost ?: return
        val hosts = hostDao.getAll().first()
        // Deploy uses password auth — no private key needed
        when (val result = ssh.connect(host.hostname, host.port, host.username, pendingPassword, hostKeyAccepted)) {
            is SshManager.ConnectResult.Success -> {
                pendingPassword = null
                val deployResult = keyManager.deployKeyViaSession(ssh)
                ssh.disconnect()
                deployResult.fold(
                    onSuccess = {
                        _uiState.value = UiState.KeySetup(hasKey = true, publicKey = keyManager.getPublicKeyString(), deploySuccess = true, hosts = hosts)
                    },
                    onFailure = { e ->
                        _uiState.value = UiState.KeySetup(hasKey = true, publicKey = keyManager.getPublicKeyString(), error = e.message ?: "Deploy failed", hosts = hosts)
                    }
                )
            }
            is SshManager.ConnectResult.NeedsHostKeyConfirmation -> {
                val info = result.info
                _uiState.value = UiState.KeySetup(
                    hasKey = keyManager.hasKey(),
                    publicKey = keyManager.getPublicKeyString(),
                    hostKeyPrompt = HostKeyPrompt(info.host, info.port, info.keyType, info.fingerprint, info.isChanged, info.oldFingerprint),
                    hosts = hosts
                )
            }
            is SshManager.ConnectResult.Failed -> {
                pendingPassword = null
                _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), error = result.error, hosts = hosts)
            }
        }
    }

    private fun handleBiometricKeyInvalidated(message: String) {
        viewModelScope.launch {
            when (val state = _uiState.value) {
                is UiState.HostList -> {
                    _uiState.value = state.copy(isConnecting = false, error = message)
                }
                is UiState.KeySetup -> {
                    _uiState.value = state.copy(
                        isGenerating = false,
                        error = message,
                        hasKey = keyManager.hasKey(),
                        publicKey = keyManager.getPublicKeyString()
                    )
                }
                else -> {}
            }
        }
    }

    private fun handleBiometricError(message: String) {
        handleBiometricKeyInvalidated(message)
    }

    // Session management
    fun refreshSessions() {
        viewModelScope.launch {
            val current = _uiState.value
            if (current is UiState.SessionList) {
                _uiState.value = current.copy(isLoading = true, error = null)
            }
            val result = ssh.listTmuxSessions()
            result.fold(
                onSuccess = { sessions ->
                    val label = (current as? UiState.SessionList)?.hostLabel ?: hostLabel
                    _uiState.value = UiState.SessionList(sessions = sessions, hostLabel = label)
                },
                onFailure = { e ->
                    val label = (current as? UiState.SessionList)?.hostLabel ?: hostLabel
                    _uiState.value = UiState.SessionList(error = e.message ?: "Failed to list sessions", hostLabel = label)
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

    fun resizePane(cols: Int, rows: Int) {
        val state = _uiState.value
        if (state is UiState.SessionView) {
            viewModelScope.launch {
                ssh.resizePane(state.sessionName, cols, rows)
            }
        }
    }

    fun nextWindow() {
        val state = _uiState.value
        if (state is UiState.SessionView) {
            viewModelScope.launch {
                ssh.nextWindow(state.sessionName)
                delay(200)
                capturePane(state.sessionName)
            }
        }
    }

    fun previousWindow() {
        val state = _uiState.value
        if (state is UiState.SessionView) {
            viewModelScope.launch {
                ssh.previousWindow(state.sessionName)
                delay(200)
                capturePane(state.sessionName)
            }
        }
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
        clearDecryptedKey()
        viewModelScope.launch {
            val hosts = hostDao.getAll().first()
            _uiState.value = UiState.HostList(hosts = hosts, hasKey = keyManager.hasKey())
        }
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
        clearDecryptedKey()
    }
}
