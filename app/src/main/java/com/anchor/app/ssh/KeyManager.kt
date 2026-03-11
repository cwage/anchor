package com.anchor.app.ssh

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class KeyManager(private val context: Context) {
    companion object {
        private const val KEYSTORE_ALIAS = "anchor_ssh_key_encryption"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    private val keysDir: File
        get() = File(context.filesDir, "ssh_keys").also { it.mkdirs() }

    private fun encryptedKeyFile(): File = File(keysDir, "id_ecdsa.enc")
    private fun ivFile(): File = File(keysDir, "id_ecdsa.iv")
    private fun publicKeyFile(): File = File(keysDir, "id_ecdsa.pub")
    private fun legacyPrivateKeyFile(): File = File(keysDir, "id_ecdsa")

    fun hasKey(): Boolean =
        (encryptedKeyFile().exists() || legacyPrivateKeyFile().exists()) && publicKeyFile().exists()

    fun hasEncryptedKey(): Boolean = encryptedKeyFile().exists() && publicKeyFile().exists()

    fun needsMigration(): Boolean =
        legacyPrivateKeyFile().exists() && !encryptedKeyFile().exists()

    fun getPublicKeyString(): String? {
        val f = publicKeyFile()
        return if (f.exists()) f.readText().trim() else null
    }

    fun isBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    fun getBiometricStatus(): Int {
        return BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    }

    private fun ensureKeyStoreKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) return

        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(0)
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    fun getEncryptionCipher(): Cipher {
        try {
            ensureKeyStoreKey()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val key = keyStore.getKey(KEYSTORE_ALIAS, null)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteEncryptedKey()
            throw BiometricKeyInvalidatedException(
                "Biometric enrollment changed. Please regenerate your SSH key."
            )
        }
    }

    fun getDecryptionCipher(): Cipher {
        try {
            ensureKeyStoreKey()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val key = keyStore.getKey(KEYSTORE_ALIAS, null)
            val ivf = ivFile()
            if (!ivf.exists()) {
                deleteEncryptedKey()
                throw BiometricKeyInvalidatedException(
                    "Encrypted key data is corrupt. Please regenerate your SSH key."
                )
            }
            val iv = ivf.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteEncryptedKey()
            throw BiometricKeyInvalidatedException(
                "Biometric enrollment changed. Please regenerate your SSH key."
            )
        }
    }

    suspend fun generateKey(cipher: Cipher): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            val keyPair = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)

            val privOut = ByteArrayOutputStream()
            keyPair.writePrivateKey(privOut)
            val privateKeyBytes = privOut.toByteArray()

            // Encrypt private key with biometric-bound AES key
            val encrypted = cipher.doFinal(privateKeyBytes)
            encryptedKeyFile().writeBytes(encrypted)
            ivFile().writeBytes(cipher.iv)

            // Clean up legacy plaintext key if present
            legacyPrivateKeyFile().delete()

            // Public key stored as-is
            val pubOut = ByteArrayOutputStream()
            keyPair.writePublicKey(pubOut, "anchor@android")
            val pubKey = pubOut.toString("UTF-8").trim()
            publicKeyFile().writeText(pubKey)

            keyPair.dispose()
            privateKeyBytes.fill(0)
            pubKey
        }
    }

    fun migrateKey(cipher: Cipher): ByteArray {
        val plainBytes = legacyPrivateKeyFile().readBytes()
        val encrypted = cipher.doFinal(plainBytes)
        encryptedKeyFile().writeBytes(encrypted)
        ivFile().writeBytes(cipher.iv)
        legacyPrivateKeyFile().delete()
        return plainBytes
    }

    fun decryptPrivateKey(cipher: Cipher): ByteArray {
        val encrypted = encryptedKeyFile().readBytes()
        return cipher.doFinal(encrypted)
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

    private fun deleteEncryptedKey() {
        encryptedKeyFile().delete()
        ivFile().delete()
    }

    fun deleteAllKeys() {
        encryptedKeyFile().delete()
        ivFile().delete()
        legacyPrivateKeyFile().delete()
        publicKeyFile().delete()
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        } catch (_: Exception) {
        }
    }
}

class BiometricKeyInvalidatedException(message: String) : Exception(message)
