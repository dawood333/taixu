package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.ui.settings.LocalizedText as Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeIconButton as IconButton
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeSwitch as Switch
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.SectionHeader
import top.wkbin.taixu.core.model.EnvironmentVariable
import top.wkbin.taixu.core.model.RuntimeState

@Composable
fun EnvironmentVariableSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val entries by viewModel.environmentVariables.collectAsStateWithLifecycle()
    val values by viewModel.environmentValues.collectAsStateWithLifecycle()
    val effectiveEntries by viewModel.effectiveEnvironment.collectAsStateWithLifecycle()
    val privacyMode by viewModel.environmentPrivacyMode.collectAsStateWithLifecycle()
    val loading by viewModel.environmentLoading.collectAsStateWithLifecycle()
    val error by viewModel.environmentError.collectAsStateWithLifecycle()
    val activeDistroId by viewModel.activeDistroId.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    // 对象状态仅Save可 Bundle 化的字段，旋转后按 key/entry 恢复
    var showEditor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var editingKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var editorInitialKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var editorInitialValue by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var deleteKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingKey?.let { key -> entries.firstOrNull { it.id == key } }
    val showDelete = deleteKey?.let { key -> entries.firstOrNull { it.id == key } }

    fun openEnvironmentEditor(
        entry: EnvironmentVariable?,
        key: String,
        value: String,
    ) {
        viewModel.clearEnvironmentError()
        editingKey = entry?.id
        editorInitialKey = key
        editorInitialValue = value
        showEditor = true
    }

    if (showEditor) {
        EnvironmentVariableEditor(
            entry = editing,
            initialKey = editorInitialKey,
            currentValue = editorInitialValue,
            error = error,
            onDismiss = {
                viewModel.clearEnvironmentError()
                showEditor = false
                editingKey = null
            },
            onSave = { key, value, note ->
                if (editing == null) viewModel.addEnvironmentVariable(key, value, note) { if (it) { showEditor = false } }
                else viewModel.updateEnvironmentVariable(editing.id, key, value, note) { if (it) { showEditor = false; editingKey = null } }
            },
        )
    }
    showDelete?.let { entry ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteKey = null },
            title = { Text("Delete environment variable") },
            text = { Text("Delete ${entry.key}?") },
            confirmButton = { TextButton(onClick = { viewModel.deleteEnvironmentVariable(entry.id); deleteKey = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteKey = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                "Environment Variables",
                onBack,
                actions = {
                    IconButton(onClick = { viewModel.refreshEnvironmentVariables() }, contentDescription = "Refresh environment variables", enabled = !loading && runtimeState is RuntimeState.Ready) {
                        RuntimeIcon(RuntimeIconName.Refresh)
                    }
                    IconButton(onClick = { openEnvironmentEditor(null, "", "") }, contentDescription = "Add environment variable", enabled = !loading && runtimeState is RuntimeState.Ready) {
                        RuntimeIcon(RuntimeIconName.Plus)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Shield,
                        title = "Agent privacy redaction",
                        subtitle = "Values are redacted only when sent to the Agent or written to conversations; this page still shows them in plain text",
                        trailing = { Switch(checked = privacyMode, onCheckedChange = viewModel::setEnvironmentPrivacyMode) },
                    )
                }
            }
            item {
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                    borderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        RuntimeIcon(RuntimeIconName.Alert, tint = MaterialTheme.colorScheme.tertiary)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Warning · Changing environment variables may cause runtime problems",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                "Incorrectly overriding JAVA_HOME, GRADLE_HOME, LANG, or similar variables may prevent the terminal, build tools, or plugins from starting. Change only variables you understand; critical TaiXu runtime variables are protected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "User variables are stored in Linux /etc/profile.d for $activeDistroId and take effect the next time a command or terminal session starts. Values are stored with restricted file permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { message ->
                item {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            message,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item {
                SectionHeader(
                    title = "User variables",
                    subtitle = "Editable TaiXu user configuration",
                    trailing = { Text(entries.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            if (loading && entries.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (entries.isEmpty()) {
                item { RuntimeCard(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("No user variables yet", style = MaterialTheme.typography.titleMedium); Text("Tap + in the top-right corner to add one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            } else {
                items(entries, key = { it.id }) { entry ->
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openEnvironmentEditor(entry, entry.key, values[entry.key].orEmpty()) },
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.key,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (entry.note.isNotBlank()) {
                                    Text(
                                        entry.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    values[entry.key].orEmpty().ifEmpty { "(empty)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { openEnvironmentEditor(entry, entry.key, values[entry.key].orEmpty()) }, contentDescription = "Edit ${entry.key}") { RuntimeIcon(RuntimeIconName.Edit) }
                            IconButton(onClick = { deleteKey = entry.id }, contentDescription = "Delete ${entry.key}") { RuntimeIcon(RuntimeIconName.Trash, tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item {
                SectionHeader(
                    title = "Effective environment",
                    subtitle = "Variables and values visible to new commands",
                    trailing = { Text(effectiveEntries.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            if (loading && effectiveEntries.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (effectiveEntries.isEmpty()) {
                item {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Text("No readable runtime environment is available", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item {
                    SettingsGroup {
                        effectiveEntries.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            val managedEntry = entries.firstOrNull { it.key == entry.key }
                            SettingsRow(
                                icon = RuntimeIconName.Key,
                                title = entry.key,
                                subtitle = entry.value.ifEmpty { "(empty)" },
                                onClick = {
                                    if (managedEntry == null) {
                                        openEnvironmentEditor(null, entry.key, entry.value)
                                    } else {
                                        openEnvironmentEditor(managedEntry, managedEntry.key, values[managedEntry.key].orEmpty())
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentVariableEditor(
    entry: EnvironmentVariable?,
    initialKey: String,
    currentValue: String,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var key by androidx.compose.runtime.saveable.rememberSaveable(entry?.id, initialKey) { mutableStateOf(initialKey) }
    var value by androidx.compose.runtime.saveable.rememberSaveable(entry?.id, initialKey) { mutableStateOf(currentValue) }
    var note by androidx.compose.runtime.saveable.rememberSaveable(entry?.id, initialKey) { mutableStateOf(entry?.note.orEmpty()) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry != null) "Edit environment variable" else if (initialKey.isNotBlank()) "Configure environment variable" else "Add environment variable") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    RuntimeIcon(RuntimeIconName.Alert, Modifier.size(18.dp), MaterialTheme.colorScheme.tertiary)
                    Text(
                        if (entry == null && initialKey.isNotBlank()) {
                            "This overrides the current Linux value in the user configuration. Incorrect values may prevent related commands from running."
                        } else {
                            "Changes affect newly launched commands and terminals. Make sure the variable name and value are correct."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                OutlinedTextField(value = key, onValueChange = { key = it.uppercase() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, value, note) }, enabled = key.isNotBlank() && (entry != null || value.isNotEmpty())) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
