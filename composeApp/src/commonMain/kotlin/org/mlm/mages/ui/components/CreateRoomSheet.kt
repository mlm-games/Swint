package org.mlm.mages.ui.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.paragraph.Alignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRoomSheet(
    onCreate: (name: String?, topic: String?, invitees: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var inviteeInput by remember { mutableStateOf("") }
    var invitees by remember { mutableStateOf(listOf<String>()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("New room", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text("Topic (optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = inviteeInput,
                    onValueChange = { inviteeInput = it },
                    label = { Text("@user:server (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val v = inviteeInput.trim()
                        if (isValidMxid(v) && v !in invitees) {
                            invitees = invitees + v
                            inviteeInput = ""
                        }
                    },
                    enabled = isValidMxid(inviteeInput.trim())
                ) { Icon(Icons.Default.Add, contentDescription = "Add") }
            }

            if (invitees.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    invitees.forEach { mxid ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(mxid) },
                            trailingIcon = {
                                IconButton(onClick = { invitees = invitees - mxid }, modifier = Modifier.size(18.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                                }
                            }
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onCreate(
                        name.ifBlank { null },
                        topic.ifBlank { null },
                        invitees
                    )
                }) { Text("Create") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun isValidMxid(s: String) = s.startsWith("@") && ":" in s && s.length > 3