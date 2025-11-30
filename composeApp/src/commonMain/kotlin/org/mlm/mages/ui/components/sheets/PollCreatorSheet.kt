package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollCreatorSheet(
    onCreatePoll: (question: String, answers: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var question by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf(listOf("", "")) }

    val isValid = question.isNotBlank() && answers.count { it.isNotBlank() } >= 2

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .padding(bottom = Spacing.xxl)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Create Poll",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close")
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // Question
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("Question") },
                placeholder = { Text("Ask something...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                leadingIcon = { Icon(Icons.Default.Poll, null) }
            )

            Spacer(Modifier.height(Spacing.lg))

            Text(
                "Options (minimum 2)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(Spacing.sm))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 250.dp)
            ) {
                itemsIndexed(answers) { index, answer ->
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { newValue ->
                            answers = answers.toMutableList().apply { set(index, newValue) }
                        },
                        label = { Text("Option ${index + 1}") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (answers.size > 2) {
                                IconButton(onClick = {
                                    answers = answers.toMutableList().apply { removeAt(index) }
                                }) {
                                    Icon(Icons.Default.Close, "Remove option")
                                }
                            }
                        }
                    )
                }

                if (answers.size < 10) {
                    item {
                        TextButton(
                            onClick = { answers = answers + "" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(Spacing.sm))
                            Text("Add option")
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        val validAnswers = answers.filter { it.isNotBlank() }
                        if (question.isNotBlank() && validAnswers.size >= 2) {
                            onCreatePoll(question.trim(), validAnswers.map { it.trim() })
                            onDismiss()
                        }
                    },
                    enabled = isValid
                ) {
                    Icon(Icons.Default.Send, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Create Poll")
                }
            }
        }
    }
}