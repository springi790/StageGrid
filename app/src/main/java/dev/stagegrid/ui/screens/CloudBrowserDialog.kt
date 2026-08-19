package dev.stagegrid.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.cloud.CloudFolderRepository

private data class CloudPathNode(val documentId: String, val name: String)

@Composable
fun CloudBrowserDialog(
    onDismiss: () -> Unit,
    onImportZip: (Uri) -> Unit,
    onImportFiles: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { CloudFolderRepository(context.applicationContext) }

    var treeUri by remember { mutableStateOf(repository.linkedTreeUri()) }
    var path by remember { mutableStateOf<List<CloudPathNode>>(emptyList()) }
    var entries by remember { mutableStateOf<List<CloudFolderRepository.Entry>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableIntStateOf(0) }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { repository.link(uri) }
                .onSuccess {
                    treeUri = uri
                    path = emptyList()
                    entries = emptyList()
                    selectedIds = emptySet()
                    error = null
                    refreshNonce++
                }
                .onFailure { error = it.message ?: "Could not keep access to the selected folder." }
        }
    }

    LaunchedEffect(treeUri, path.lastOrNull()?.documentId, refreshNonce) {
        val uri = treeUri ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            if (path.isEmpty()) {
                val root = repository.rootInfo(uri)
                path = listOf(CloudPathNode(root.documentId, root.displayName))
                return@LaunchedEffect
            }
            entries = repository.listChildren(uri, path.last().documentId)
            selectedIds = emptySet()
        } catch (t: Throwable) {
            entries = emptyList()
            error = t.message ?: "The linked folder could not be read."
        } finally {
            loading = false
        }
    }

    val uri = treeUri
    if (uri == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.cloud_folder_title)) },
            text = { Text(stringResource(R.string.cloud_folder_explanation)) },
            confirmButton = {
                Button(onClick = { folderLauncher.launch(null) }) {
                    Text(stringResource(R.string.cloud_choose_folder))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        )
        return
    }

    val selectedEntries = entries.filter { !it.isDirectory && it.documentId in selectedIds }
    val currentPath = path.joinToString(" / ") { it.name }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_folder_title)) },
        text = {
            Column {
                Text(stringResource(R.string.cloud_folder_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(currentPath, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    if (path.size > 1) {
                        OutlinedButton(onClick = { path = path.dropLast(1) }) { Text(stringResource(R.string.cloud_back)) }
                    }
                    OutlinedButton(onClick = { refreshNonce++ }) { Text(stringResource(R.string.cloud_refresh)) }
                    OutlinedButton(onClick = {
                        val fileIds = entries.filterNot { it.isDirectory }.map { it.documentId }.toSet()
                        selectedIds = if (selectedIds.containsAll(fileIds) && fileIds.isNotEmpty()) emptySet() else fileIds
                    }) { Text(stringResource(R.string.cloud_select_all)) }
                }
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    CircularProgressIndicator()
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loading && entries.isEmpty() && error == null) {
                    Text(stringResource(R.string.cloud_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(entries, key = { it.documentId }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        path = path + CloudPathNode(entry.documentId, entry.name)
                                    } else {
                                        selectedIds = selectedIds.toMutableSet().also { set ->
                                            if (!set.add(entry.documentId)) set.remove(entry.documentId)
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            if (entry.isDirectory) {
                                Text("DIR", fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                            } else {
                                Checkbox(
                                    checked = entry.documentId in selectedIds,
                                    onCheckedChange = {
                                        selectedIds = selectedIds.toMutableSet().also { set ->
                                            if (!set.add(entry.documentId)) set.remove(entry.documentId)
                                        }
                                    },
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(entry.name)
                                entry.sizeBytes?.let { Text(formatCloudSize(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { folderLauncher.launch(uri) }) { Text(stringResource(R.string.cloud_change_folder)) }
                    TextButton(onClick = {
                        repository.unlink()
                        treeUri = null
                        path = emptyList()
                        entries = emptyList()
                        selectedIds = emptySet()
                    }) { Text(stringResource(R.string.cloud_unlink)) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedEntries.isNotEmpty() && !loading,
                onClick = {
                    val archives = selectedEntries.filter(repository::isArchive)
                    if (archives.isNotEmpty() && selectedEntries.size != 1) {
                        error = context.getString(R.string.cloud_archive_mix_error)
                        return@Button
                    }
                    if (archives.size == 1) onImportZip(archives.first().uri)
                    else onImportFiles(selectedEntries.map { it.uri })
                    onDismiss()
                },
            ) { Text(stringResource(R.string.cloud_download_import, selectedEntries.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

private fun formatCloudSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
