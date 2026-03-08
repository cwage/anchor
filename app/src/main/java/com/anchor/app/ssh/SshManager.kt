package com.anchor.app.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Properties

data class TmuxSession(
    val name: String,
    val windows: Int,
    val created: String,
    val attached: Boolean
)

data class HostKeyInfo(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val isChanged: Boolean,
    val oldFingerprint: String? = null
)

class SshManager {
    private var session: Session? = null
    var keyManager: KeyManager? = null
    var hostKeyStore: HostKeyStore? = null

    // Set by the ViewModel before connect; checked during connection
    var onHostKeyVerify: ((HostKeyInfo) -> Unit)? = null
    private var pendingHostKeyInfo: HostKeyInfo? = null
    private var hostKeyAccepted: Boolean? = null

    fun setPendingHostKeyDecision(accepted: Boolean) {
        hostKeyAccepted = accepted
    }

    fun getPendingHostKeyInfo(): HostKeyInfo? = pendingHostKeyInfo

    sealed class ConnectResult {
        data object Success : ConnectResult()
        data class NeedsHostKeyConfirmation(val info: HostKeyInfo) : ConnectResult()
        data class Failed(val error: String) : ConnectResult()
    }

    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String? = null,
        hostKeyAlreadyAccepted: Boolean = false
    ): ConnectResult = withContext(Dispatchers.IO) {
        try {
            val jsch = JSch()
            keyManager?.addIdentity(jsch)
            val s = jsch.getSession(username, host, port)
            if (password != null) {
                s.setPassword(password)
            }
            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            config["PreferredAuthentications"] = if (keyManager?.hasKey() == true) {
                "publickey,password"
            } else {
                "password"
            }
            s.setConfig(config)
            s.timeout = 10_000
            s.connect()

            // Check host key after successful connect
            val hostKey = s.hostKey
            val fingerprint = hostKey.getFingerPrint(jsch)
            val keyType = hostKey.type
            val store = hostKeyStore

            if (!hostKeyAlreadyAccepted && store != null) {
                val savedFingerprint = store.getFingerprint(host, port)
                when {
                    savedFingerprint == null -> {
                        // Unknown host — disconnect and ask user
                        s.disconnect()
                        pendingHostKeyInfo = HostKeyInfo(host, port, keyType, fingerprint, false)
                        return@withContext ConnectResult.NeedsHostKeyConfirmation(pendingHostKeyInfo!!)
                    }
                    savedFingerprint != fingerprint -> {
                        // Changed key — disconnect and warn user
                        s.disconnect()
                        pendingHostKeyInfo = HostKeyInfo(host, port, keyType, fingerprint, true, savedFingerprint)
                        return@withContext ConnectResult.NeedsHostKeyConfirmation(pendingHostKeyInfo!!)
                    }
                    // else: matches, proceed
                }
            }

            // Save/update fingerprint
            store?.saveFingerprint(host, port, fingerprint)
            session = s
            pendingHostKeyInfo = null
            ConnectResult.Success
        } catch (e: Exception) {
            ConnectResult.Failed(e.message ?: "Connection failed")
        }
    }

    fun isConnected(): Boolean = session?.isConnected == true

    fun disconnect() {
        session?.disconnect()
        session = null
    }

    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val s = session ?: throw IllegalStateException("Not connected")
            val channel = s.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.inputStream = null
            val output = ByteArrayOutputStream()
            channel.outputStream = output
            val errStream = ByteArrayOutputStream()
            channel.setErrStream(errStream)
            channel.connect(10_000)
            while (!channel.isClosed) {
                Thread.sleep(50)
            }
            channel.disconnect()
            val result = output.toString("UTF-8")
            val err = errStream.toString("UTF-8")
            if (channel.exitStatus != 0 && result.isBlank()) {
                throw RuntimeException(err.ifBlank { "Command failed with exit code ${channel.exitStatus}" })
            }
            result
        }
    }

    suspend fun listTmuxSessions(): Result<List<TmuxSession>> = withContext(Dispatchers.IO) {
        val result = exec("tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_created_string}|#{session_attached}'")
        result.map { output ->
            output.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val parts = line.split("|", limit = 4)
                    TmuxSession(
                        name = parts.getOrElse(0) { "unknown" },
                        windows = parts.getOrElse(1) { "1" }.toIntOrNull() ?: 1,
                        created = parts.getOrElse(2) { "" },
                        attached = parts.getOrElse(3) { "0" } == "1"
                    )
                }
        }
    }

    suspend fun capturePane(sessionName: String): Result<String> {
        return exec("tmux capture-pane -t '$sessionName' -p -S -50").map { output ->
            output.trimEnd()
        }
    }

    suspend fun sendKeys(sessionName: String, keys: String): Result<String> {
        val escaped = keys.replace("'", "'\\''")
        return exec("tmux send-keys -t '$sessionName' '$escaped' Enter")
    }
}
