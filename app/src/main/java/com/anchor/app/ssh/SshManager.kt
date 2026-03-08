package com.anchor.app.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import android.util.Log
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
    init {
        // Disable slow algorithm availability checks that take ~7s on Android
        JSch.setConfig("CheckKexes", "")
        JSch.setConfig("CheckSignatures", "")
        JSch.setConfig("CheckCiphers", "")
    }
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
            var t = System.currentTimeMillis()
            val jsch = JSch()
            keyManager?.addIdentity(jsch)
            Log.d("Anchor", "SSH setup: ${System.currentTimeMillis() - t}ms")

            val store = hostKeyStore
            val savedFingerprint = store?.getFingerprint(host, port)
            val isKnownHost = savedFingerprint != null

            // For unknown hosts: probe for host key without sending credentials
            if (!hostKeyAlreadyAccepted && !isKnownHost && store != null) {
                t = System.currentTimeMillis()
                val probe = jsch.getSession(username, host, port)
                val probeConfig = Properties()
                probeConfig["StrictHostKeyChecking"] = "no"
                probeConfig["PreferredAuthentications"] = "none"
                probe.setConfig(probeConfig)
                probe.timeout = 10_000
                try {
                    probe.connect()
                } catch (_: Exception) {
                    // "none" auth always fails, but we got the host key
                }
                Log.d("Anchor", "Probe connect: ${System.currentTimeMillis() - t}ms")
                val probeKey = probe.hostKey
                probe.disconnect()

                if (probeKey == null) {
                    return@withContext ConnectResult.Failed("Unable to retrieve host key")
                }

                val fingerprint = probeKey.getFingerPrint(jsch)
                val keyType = probeKey.type
                pendingHostKeyInfo = HostKeyInfo(host, port, keyType, fingerprint, false)
                return@withContext ConnectResult.NeedsHostKeyConfirmation(pendingHostKeyInfo!!)
            }

            // Authenticated connection
            t = System.currentTimeMillis()
            val s = jsch.getSession(username, host, port)
            Log.d("Anchor", "getSession: ${System.currentTimeMillis() - t}ms")
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
            t = System.currentTimeMillis()
            s.connect()
            Log.d("Anchor", "s.connect(): ${System.currentTimeMillis() - t}ms")

            // Verify fingerprint — for known hosts, check against stored; detect changes
            val hostKey = s.hostKey
            val fingerprint = hostKey.getFingerPrint(jsch)
            if (isKnownHost && fingerprint != savedFingerprint) {
                s.disconnect()
                pendingHostKeyInfo = HostKeyInfo(host, port, hostKey.type, fingerprint, true, savedFingerprint)
                return@withContext ConnectResult.NeedsHostKeyConfirmation(pendingHostKeyInfo!!)
            }
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
            val t = System.currentTimeMillis()
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
            Log.d("Anchor", "exec(${command.take(40)}): ${System.currentTimeMillis() - t}ms")
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

    private fun shellEscape(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    suspend fun capturePane(sessionName: String): Result<String> {
        return exec("tmux capture-pane -t ${shellEscape(sessionName)} -p -S -50").map { output ->
            output.trimEnd()
        }
    }

    suspend fun resizePane(sessionName: String, cols: Int, rows: Int): Result<String> {
        val safeCols = cols.coerceIn(1, 500)
        val safeRows = rows.coerceIn(1, 500)
        return exec("tmux resize-window -t ${shellEscape(sessionName)} -x $safeCols -y $safeRows")
    }

    suspend fun sendKeys(sessionName: String, keys: String): Result<String> {
        return exec("tmux send-keys -t ${shellEscape(sessionName)} -l ${shellEscape(keys)} && tmux send-keys -t ${shellEscape(sessionName)} Enter")
    }
}
