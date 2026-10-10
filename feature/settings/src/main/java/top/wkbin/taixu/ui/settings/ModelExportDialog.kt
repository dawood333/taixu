package top.wkbin.taixu.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.wkbin.taixu.ui.components.*

/** Generate off the UI thread; failures leave the dialog open and never echo credential-bearing errors. */
@Composable
fun ModelExportDialog(
    title: String,
    subtitle: String,
    defaultFileName: String = "taixu_models_export.json",
    onDismiss: () -> Unit,
    onGenerateJson: suspend (includeKeys: Boolean) -> String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeKeys by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingFile
        pendingFile = null
        if (uri != null && content != null) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri) ?: error("Export destination unavailable")
                        output.bufferedWriter().use { it.write(content) }
                    }
                    Toast.makeText(context, "Export file saved successfully", Toast.LENGTH_SHORT).show()
                    onDismiss()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { failed = true
                } finally { busy = false }
            }
        }
    }
    fun generate(action: (String) -> Unit) {
        if (busy) return
        val credentials = includeKeys
        busy = true
        failed = false
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) { onGenerateJson(credentials) }
                check(content.isNotBlank())
                action(content)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failed = true
            } finally { busy = false }
        }
    }
    RuntimeAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(subtitle)
                Row {
                    RuntimeCheckbox(checked = includeKeys, enabled = !busy, onCheckedChange = { includeKeys = it })
                    Column(Modifier.weight(1f)) {
                        Text("Includes API keys and custom request headers", style = MaterialTheme.typography.titleSmall)
                        Text(if (includeKeys) "Contains sensitive credentials. Store securely and never share publicly." else "API keys and custom request headers are excluded by default",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (includeKeys) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                RuntimeOutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    generate { content ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            ?: error("Clipboard unavailable")
                        clipboard.setPrimaryClip(ClipData.newPlainText("TaiXu Model Config", content))
                        Toast.makeText(context, "JSON configuration copied to clipboard", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }) { Text("Copy JSON to clipboard") }
                RuntimeOutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    generate { content -> pendingFile = content; createFile.launch(defaultFileName) }
                }) { Text("Save as .json file") }
                RuntimeOutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    generate { content ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "TaiXu Model Profiles")
                            putExtra(Intent.EXTRA_TEXT, content)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share model configuration"))
                        onDismiss()
                    }
                }) { Text("System share") }
                if (busy) RuntimeCircularProgressIndicator(Modifier.size(20.dp))
                if (failed) Text("Export failed. Check credentials, configuration, and file permissions, then try again.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { RuntimeTextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } },
    )
}
