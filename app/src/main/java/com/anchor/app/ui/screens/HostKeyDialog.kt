package com.anchor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anchor.app.HostKeyPrompt

@Composable
fun HostKeyDialog(
    prompt: HostKeyPrompt,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(
                if (prompt.isChanged) "HOST KEY CHANGED" else "Unknown Host",
                fontWeight = if (prompt.isChanged) FontWeight.Bold else FontWeight.Normal,
                color = if (prompt.isChanged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (prompt.isChanged) {
                    Text(
                        "WARNING: The host key for ${prompt.host} has changed. " +
                            "This could indicate a man-in-the-middle attack.",
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("Previous fingerprint:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        prompt.oldFingerprint ?: "unknown",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Text("New fingerprint:", style = MaterialTheme.typography.labelMedium)
                } else {
                    Text(
                        "The authenticity of host '${prompt.host}' can't be established."
                    )
                    Text("${prompt.keyType} key fingerprint:", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    prompt.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Text(
                    "Are you sure you want to continue connecting?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (prompt.isChanged) {
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Accept Anyway")
                }
            } else {
                Button(onClick = onAccept) {
                    Text("Trust & Connect")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject) {
                Text("Reject")
            }
        }
    )
}
