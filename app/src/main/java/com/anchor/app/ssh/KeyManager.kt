package com.anchor.app.ssh

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class KeyManager(private val context: Context) {
    private val keysDir: File
        get() = File(context.filesDir, "ssh_keys").also { it.mkdirs() }

    private fun privateKeyFile(): File = File(keysDir, "id_ecdsa")
    private fun publicKeyFile(): File = File(keysDir, "id_ecdsa.pub")

    fun hasKey(): Boolean = privateKeyFile().exists() && publicKeyFile().exists()

    fun getPublicKeyString(): String? {
        val f = publicKeyFile()
        return if (f.exists()) f.readText().trim() else null
    }

    fun getPrivateKeyPath(): String = privateKeyFile().absolutePath

    suspend fun generateKey(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            val keyPair = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)

            val privOut = ByteArrayOutputStream()
            keyPair.writePrivateKey(privOut)
            privateKeyFile().writeBytes(privOut.toByteArray())

            val pubOut = ByteArrayOutputStream()
            keyPair.writePublicKey(pubOut, "anchor@android")
            val pubKey = pubOut.toString("UTF-8").trim()
            publicKeyFile().writeText(pubKey)

            keyPair.dispose()
            pubKey
        }
    }

    suspend fun deployKeyViaSession(ssh: SshManager): Result<Unit> {
        val pubKey = getPublicKeyString()
            ?: return Result.failure(IllegalStateException("No key generated"))

        val escaped = pubKey.replace("'", "'\\''")
        val command = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
            "printf '%s\\n' '${escaped}' >> ~/.ssh/authorized_keys && " +
            "chmod 600 ~/.ssh/authorized_keys"

        return ssh.exec(command).map { }
    }

    fun addIdentity(jsch: JSch) {
        if (hasKey()) {
            jsch.addIdentity(getPrivateKeyPath())
        }
    }
}
