package com.anchor.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.app.data.AppDatabase
import com.anchor.app.data.Host
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

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val isChanged: Boolean,
    val oldFingerprint: String? = null
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
    private val keyManager = KeyManager(application)
    private val hostKeyStore = HostKeyStore(application)
    private val ssh = SshManager().also {
        it.keyManager = keyManager
        it.hostKeyStore = hostKeyStore
    }
    private val db = AppDatabase.getInstance(application)
    private val hostDao = db.hostDao()

    private val _uiState = MutableStateFlow<UiState>(UiState.HostList(hasKey = keyManager.hasKey()))
    val uiState: StateFlow<UiState> = _uiState

    private var pollJob: Job? = null
    private var hostLabel: String = ""

    private var pendingHost: Host? = null
    private var pendingPassword: String? = null
    private var pendingAction: String = ""

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

            doConnect(false)
        }
    }

    private suspend fun doConnect(hostKeyAccepted: Boolean) {
        val host = pendingHost ?: return
        when (val result = ssh.connect(host.hostname, host.port, host.username, pendingPassword, hostKeyAccepted)) {
            is SshManager.ConnectResult.Success -> {
                hostDao.updateLastConnected(host.id, System.currentTimeMillis())
                hostLabel = "${host.username}@${host.hostname}"
                _uiState.value = UiState.SessionList(isLoading = true, hostLabel = host.label)
                refreshSessions()
            }
            is SshManager.ConnectResult.NeedsHostKeyConfirmation -> {
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
                val hosts = hostDao.getAll().first()
                val needsPassword = result.error.contains("Auth fail", ignoreCase = true) && pendingPassword == null
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
            val hosts = hostDao.getAll().first()
            val result = keyManager.generateKey()
            result.fold(
                onSuccess = { pubKey ->
                    _uiState.value = UiState.KeySetup(hasKey = true, publicKey = pubKey, hosts = hosts)
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
        when (val result = ssh.connect(host.hostname, host.port, host.username, pendingPassword, hostKeyAccepted)) {
            is SshManager.ConnectResult.Success -> {
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
                _uiState.value = UiState.KeySetup(hasKey = keyManager.hasKey(), publicKey = keyManager.getPublicKeyString(), error = result.error, hosts = hosts)
            }
        }
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
    }
}
