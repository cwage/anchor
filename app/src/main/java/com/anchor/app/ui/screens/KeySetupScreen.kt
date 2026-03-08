package com.anchor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anchor.app.data.Host

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeySetupScreen(
    hasKey: Boolean,
    publicKey: String?,
    onGenerateKey: () -> Unit,
    onDeployKey: (host: Host, password: String) -> Unit,
    isGenerating: Boolean,
    isDeploying: Boolean,
    error: String?,
    deploySuccess: Boolean,
    hosts: List<Host>,
    onBack: () -> Unit
) {
    var selectedHost by remember { mutableStateOf<Host?>(null) }
    var password by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH Key Setup") },
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Key generation section
            Text("1. Generate Key", style = MaterialTheme.typography.titleMedium)

            if (hasKey && publicKey != null) {
                Text(
                    "Key exists:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Text(
                        text = publicKey,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedButton(
                    onClick = onGenerateKey,
                    enabled = !isGenerating
                ) {
                    Text("Regenerate Key")
                }
            } else {
                Button(
                    onClick = onGenerateKey,
                    enabled = !isGenerating
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Generate ECDSA Key")
                }
            }

            HorizontalDivider()

            // Key deployment section
            Text("2. Deploy to Server", style = MaterialTheme.typography.titleMedium)

            if (!hasKey) {
                Text(
                    "Generate a key first",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (hosts.isEmpty()) {
                Text(
                    "Add a host first from the home screen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Select a host and enter your password to deploy the key. Future connections will use the key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedHost?.let { "${it.label} (${it.username}@${it.hostname})" } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Host") },
                        placeholder = { Text("Select a host") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        hosts.forEach { host ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(host.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${host.username}@${host.hostname}:${host.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    selectedHost = host
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrect = false
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (deploySuccess) {
                    Text(
                        text = "Key deployed successfully! You can now connect without a password.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Button(
                    onClick = {
                        selectedHost?.let { onDeployKey(it, password) }
                    },
                    enabled = !isDeploying && selectedHost != null && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isDeploying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isDeploying) "Deploying..." else "Deploy Key")
                }
            }
        }
    }
}
