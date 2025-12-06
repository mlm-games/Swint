package org.mlm.mages.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.mlm.mages.MainActivity
import org.mlm.mages.MatrixService
import org.mlm.mages.platform.AppCtx
import org.mlm.mages.ui.ForwardableRoom
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.theme.Spacing
import java.io.File
import java.io.FileOutputStream

class ShareReceiverActivity : ComponentActivity() {

    private val service: MatrixService by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if logged in
        if (!service.isLoggedIn()) {
            // Redirect to login
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val sharedContent = parseIntent(intent)

        if (sharedContent == null) {
            finish()
            return
        }

        setContent {
            MainTheme {
                ShareReceiverScreen(
                    sharedContent = sharedContent,
                    service = service,
                    onDismiss = { finish() },
                    onSent = { roomName ->
                        finish()
                    }
                )
            }
        }
    }

    private fun parseIntent(intent: Intent): SharedContent? {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val mimeType = intent.type ?: "text/plain"

                when {
                    mimeType.startsWith("text/") -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                        if (text != null) {
                            SharedContent.Text(
                                text = if (subject != null) "$subject\n\n$text" else text
                            )
                        } else null
                    }
                    else -> {
                        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                        }

                        if (uri != null) {
                            SharedContent.SingleFile(
                                uri = uri,
                                mimeType = mimeType,
                                fileName = getFileName(uri),
                                caption = intent.getStringExtra(Intent.EXTRA_TEXT)
                            )
                        } else null
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val mimeType = intent.type ?: "*/*"
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }

                if (!uris.isNullOrEmpty()) {
                    SharedContent.MultipleFiles(
                        files = uris.map { uri ->
                            SharedFile(
                                uri = uri,
                                mimeType = contentResolver.getType(uri) ?: mimeType,
                                fileName = getFileName(uri)
                            )
                        }
                    )
                } else null
            }
            else -> null
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "shared_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}

// Shared content types
sealed class SharedContent {
    data class Text(val text: String) : SharedContent()

    data class SingleFile(
        val uri: Uri,
        val mimeType: String,
        val fileName: String,
        val caption: String?
    ) : SharedContent()

    data class MultipleFiles(
        val files: List<SharedFile>
    ) : SharedContent()
}

data class SharedFile(
    val uri: Uri,
    val mimeType: String,
    val fileName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareReceiverScreen(
    sharedContent: SharedContent,
    service: MatrixService,
    onDismiss: () -> Unit,
    onSent: (roomName: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<ForwardableRoom>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Load rooms
    LaunchedEffect(Unit) {
        rooms = withContext(Dispatchers.IO) {
            try {
                service.port.listRooms().map { room ->
                    ForwardableRoom(
                        roomId = room.id,
                        name = room.name,
                        avatarUrl = room.avatarUrl,
                        isDm = room.isDm,
                        lastActivity = 0L  // TODO
                    )
                }.sortedByDescending { it.lastActivity }
            } catch (e: Exception) {
                emptyList()
            }
        }
        isLoading = false
    }

    // Show error
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            errorMessage = null
        }
    }

    val filteredRooms = remember(rooms, searchQuery) {
        if (searchQuery.isBlank()) rooms
        else rooms.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share to...") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Cancel")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Preview of shared content
            SharePreview(
                content = sharedContent,
                modifier = Modifier.padding(Spacing.md)
            )

            HorizontalDivider()

            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                placeholder = { Text("Search rooms...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            // Room list
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                isSending -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Sending...")
                        }
                    }
                }
                filteredRooms.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchQuery.isNotBlank()) "No rooms found"
                            else "No rooms available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filteredRooms, key = { it.roomId }) { room ->
                            ShareRoomItem(
                                room = room,
                                onClick = {
                                    scope.launch {
                                        isSending = true
                                        val success = sendSharedContent(
                                            service = service,
                                            roomId = room.roomId,
                                            content = sharedContent
                                        )
                                        isSending = false
                                        if (success) {
                                            onSent(room.name)
                                        } else {
                                            errorMessage = "Failed to send"
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharePreview(content: SharedContent, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (content) {
                is SharedContent.Text -> Icons.Default.TextFields
                is SharedContent.SingleFile -> when {
                    content.mimeType.startsWith("image/") -> Icons.Default.Image
                    content.mimeType.startsWith("video/") -> Icons.Default.Videocam
                    content.mimeType.startsWith("audio/") -> Icons.Default.AudioFile
                    else -> Icons.Default.AttachFile
                }
                is SharedContent.MultipleFiles -> Icons.Default.Folder
            }

            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                when (content) {
                    is SharedContent.Text -> {
                        Text(
                            "Text message",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            content.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is SharedContent.SingleFile -> {
                        Text(
                            content.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            content.mimeType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        content.caption?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    is SharedContent.MultipleFiles -> {
                        Text(
                            "${content.files.size} files",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            content.files.take(3).joinToString(", ") { it.fileName },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareRoomItem(room: ForwardableRoom, onClick: () -> Unit) {
    Surface(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (room.isDm) {
                        Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    } else {
                        Text(
                            room.name.take(2).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (room.isDm) "Direct message" else "Room",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.Send,
                contentDescription = "Send",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private suspend fun sendSharedContent(
    service: MatrixService,
    roomId: String,
    content: SharedContent
): Boolean = withContext(Dispatchers.IO) {
    try {
        when (content) {
            is SharedContent.Text -> {
                service.sendMessage(roomId, content.text)
            }
            is SharedContent.SingleFile -> {
                val ctx = AppCtx.get() ?: return@withContext false
                val tempFile = copyUriToTempFile(ctx , content.uri, content.fileName)
                val success = service.sendAttachmentFromPath(
                    roomId = roomId,
                    path = tempFile.absolutePath,
                    mime = content.mimeType,
                    filename = content.fileName
                ) { _, _ -> }
                tempFile.delete()

                // Send caption if present
                if (success && !content.caption.isNullOrBlank()) {
                    service.sendMessage(roomId, content.caption)
                }
                success
            }
            is SharedContent.MultipleFiles -> {
                var allSuccess = true
                for (file in content.files) {
                    val ctx = AppCtx.get() ?: return@withContext false
                    val tempFile = copyUriToTempFile(ctx, file.uri, file.fileName)
                    val success = service.sendAttachmentFromPath(
                        roomId = roomId,
                        path = tempFile.absolutePath,
                        mime = file.mimeType,
                        filename = file.fileName
                    ) { _, _ -> }
                    tempFile.delete()
                    if (!success) allSuccess = false
                }
                allSuccess
            }
        }
    } catch (e: Exception) {
        false
    }
}

private fun copyUriToTempFile(context: Context, uri: Uri, fileName: String): File {
    val tempDir = File(context.cacheDir, "share_temp")
    tempDir.mkdirs()
    val tempFile = File(tempDir, fileName)

    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
        }
    }

    return tempFile
}