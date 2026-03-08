package com.anchor.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.app.data.Host

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HostListScreen(
    hosts: List<Host>,
    onHostTap: (Host) -> Unit,
    onAddHost: () -> Unit,
    onDeleteHost: (Host) -> Unit,
    onEditHost: (Host) -> Unit,
    onKeySetup: () -> Unit,
    hasKey: Boolean,
    isConnecting: Boolean = false,
    connectingHostId: Long? = null,
    error: String? = null
) {
    var hostToDelete by remember { mutableStateOf<Host?>(null) }

    if (hostToDelete != null) {
        AlertDialog(
            onDismissRequest = { hostToDelete = null },
            title = { Text("Delete host?") },
            text = { Text("Remove ${hostToDelete!!.label} from saved hosts?") },
            confirmButton = {
                Button(onClick = {
                    onDeleteHost(hostToDelete!!)
                    hostToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { hostToDelete = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anchor") },
                actions = {
                    IconButton(onClick = onKeySetup) {
                        Icon(Icons.Default.Key, contentDescription = "SSH Key Setup")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHost) {
                Icon(Icons.Default.Add, contentDescription = "Add host")
            }
        }
    ) { padding ->
        if (hosts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No saved hosts",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!hasKey) {
                        Text(
                            text = "Set up an SSH key first",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (error != null) {
                    item {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                items(hosts, key = { it.id }) { host ->
                    val isThisConnecting = isConnecting && connectingHostId == host.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (!isConnecting) onHostTap(host) },
                                onLongClick = { hostToDelete = host }
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = host.label,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${host.username}@${host.hostname}:${host.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onEditHost(host) }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (isThisConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
