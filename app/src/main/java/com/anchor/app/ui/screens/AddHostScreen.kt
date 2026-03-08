package com.anchor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.anchor.app.data.Host

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHostScreen(
    onSave: (label: String, hostname: String, port: Int, username: String) -> Unit,
    onBack: () -> Unit,
    editingHost: Host? = null
) {
    val isEditing = editingHost != null
    var label by remember { mutableStateOf(editingHost?.label ?: "") }
    var hostname by remember { mutableStateOf(editingHost?.hostname ?: "") }
    var port by remember { mutableStateOf(editingHost?.let { if (it.port != 22) it.port.toString() else "" } ?: "") }
    var username by remember { mutableStateOf(editingHost?.username ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Host" else "Add Host") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                placeholder = { Text("My Server") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text("Hostname / IP") },
                placeholder = { Text("10.10.15.3") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                placeholder = { Text("22") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrect = false),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 22
                    val lbl = label.ifBlank { "${username}@${hostname}" }
                    onSave(lbl, hostname.trim(), p, username.trim())
                },
                enabled = hostname.isNotBlank() && username.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isEditing) "Update Host" else "Save Host")
            }
        }
    }
}
