package com.anchor.app.ssh

import android.content.Context
import java.io.File

class HostKeyStore(private val context: Context) {
    private val file: File
        get() = File(context.filesDir, "known_hosts").also {
            if (!it.exists()) it.createNewFile()
        }

    fun getFingerprint(host: String, port: Int): String? {
        val key = hostKey(host, port)
        return file.readLines()
            .firstOrNull { it.startsWith("$key ") }
            ?.substringAfter(" ")
    }

    fun saveFingerprint(host: String, port: Int, fingerprint: String) {
        val key = hostKey(host, port)
        val lines = file.readLines().toMutableList()
        lines.removeAll { it.startsWith("$key ") }
        lines.add("$key $fingerprint")
        file.writeText(lines.joinToString("\n") + "\n")
    }

    fun removeFingerprint(host: String, port: Int) {
        val key = hostKey(host, port)
        val lines = file.readLines().filterNot { it.startsWith("$key ") }
        file.writeText(lines.joinToString("\n") + "\n")
    }

    private fun hostKey(host: String, port: Int): String {
        return if (port == 22) host else "[$host]:$port"
    }
}
