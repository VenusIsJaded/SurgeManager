package com.aliucord.manager.ui.components.dialogs

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.surgecord.manager.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallOptionsBottomSheet(
    onDismiss: () -> Unit,
    onAutoInstall: () -> Unit,
    onLocalApkSelected: (Uri) -> Unit
) {
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take a persistable permission so the URI stays readable after this
            // callback returns and across coroutine dispatches.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable permissions — that's fine,
                // the URI is still readable within the same process session.
            }
            // Dismiss first so we don't call onDismiss() on a dead sheet later.
            onDismiss()
            onLocalApkSelected(uri)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Install SurgeCord", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 16.dp, start = 8.dp))
            OptionCard("Auto-Install (Latest)", "Automatically fetch and install the newest version.", R.drawable.ic_sync) { onAutoInstall(); onDismiss() }
            OptionCard("Select Local APK/APKM", "Pick an APK or APKM file already on your device.", R.drawable.ic_download) {
                filePicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
            }
        }
    }
}

@Composable
private fun OptionCard(title: String, desc: String, icon: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(id = icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
