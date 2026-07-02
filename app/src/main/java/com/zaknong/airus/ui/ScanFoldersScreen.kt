package com.zaknong.airus.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaknong.airus.R
import com.zaknong.airus.database.AppDatabase
import com.zaknong.airus.database.entity.ScanFolder
import com.zaknong.airus.scanner.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ScanFoldersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val folders by db.scanFolderDao().allFolders.observeAsState(emptyList())
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistable permission
            context.contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val path = getFullPathFromUri(context, it) ?: it.toString()
            val displayName = if (path.startsWith("content://")) {
                // Try to get display name from DocumentFile
                try {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it)?.name ?: "External Folder"
                } catch (e: Exception) {
                    "External Folder"
                }
            } else {
                java.io.File(path).name
            }

            scope.launch {
                val folder = ScanFolder(it.toString(), displayName)
                withContext(Dispatchers.IO) {
                    db.scanFolderDao().insert(folder)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Spacer(modifier = Modifier.height(64.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right), // Use existing
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).rotateDegrees(180f),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Scan Folders",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Text(
            text = "Select specific folders to scan for music. If empty, all music on device will be scanned.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        Button(
            onClick = { launcher.launch(null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(painterResource(R.drawable.ic_folder), null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Folder")
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(folders) { folder ->
                FolderItemRow(folder, onDelete = {
                    scope.launch(Dispatchers.IO) {
                        db.scanFolderDao().delete(folder)
                    }
                })
            }
        }
        
        Button(
            onClick = { 
                com.zaknong.airus.scanner.ScanManager.getInstance(context).startScan()
                onBack()
            },
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Rescan Now")
        }
    }
}

@Composable
fun FolderItemRow(folder: ScanFolder, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(R.drawable.ic_folder), null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = folder.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(text = folder.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun Modifier.rotateDegrees(degrees: Float) = this.then(
    Modifier.rotate(degrees)
)

private fun getFullPathFromUri(context: Context, uri: Uri): String? {
    if (DocumentsContract.isTreeUri(uri)) {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val split = documentId.split(":")
        val type = split[0]
        if ("primary".equals(type, ignoreCase = true)) {
            return "/storage/emulated/0/" + split[1]
        }
    }
    return null
}
