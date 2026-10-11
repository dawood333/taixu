package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.model.AiModelProfileExport
import top.wkbin.taixu.harness.ModelContextWindows
import top.wkbin.taixu.ui.components.IconTile
import top.wkbin.taixu.ui.components.ProviderBadge
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeCheckbox
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton
import top.wkbin.taixu.ui.components.RuntimeTextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar
import java.util.Locale

/**
 * Model Profiles管理全屏独立页面
 */
@Composable
fun ModelProfilesScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showImportDialog by remember { mutableStateOf(false) }
    var showExportAllDialog by remember { mutableStateOf(false) }
    var singleExportModel by remember { mutableStateOf<AiModelEntity?>(null) }
    var modelPendingDelete by remember { mutableStateOf<AiModelEntity?>(null) }
    var importing by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "Model Profiles",
                onBack = onBack,
                actions = {
                    // 📥 导入按钮
                    RuntimeIconButton(
                        onClick = { showImportDialog = true },
                        contentDescription = "Import model configuration",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Download,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // 📤 导出全部按钮
                    RuntimeIconButton(
                        onClick = {
                            if (models.isEmpty()) {
                                Toast.makeText(context, "No model profiles to export", Toast.LENGTH_SHORT).show()
                            } else {
                                showExportAllDialog = true
                            }
                        },
                        contentDescription = "Export all model configurations",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.OpenInNew,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        ModelProfilesContent(
            modifier = Modifier.padding(padding),
            models = models,
            onCreate = onCreate,
            onEdit = { model -> onEdit(model.id) },
            onActivate = viewModel::setActiveModel,
            onDelete = { model -> modelPendingDelete = model },
            onExportSingle = { model -> singleExportModel = model },
        )
    }

    // 导入弹窗
    if (showImportDialog) {
        ModelImportDialog(
            onDismiss = { showImportDialog = false },
            importing = importing,
            models = models,
            parseProfiles = viewModel::parseProfilesFromJson,
            onImportJson = { jsonStr, mode ->
                if (importing) return@ModelImportDialog
                importing = true
                coroutineScope.launch {
                    val result = viewModel.importProfilesFromJson(jsonStr, mode)
                    importing = false
                    result.fold(
                        onSuccess = { count ->
                            Toast.makeText(context, "Successfully imported $count model profiles", Toast.LENGTH_SHORT).show()
                            showImportDialog = false
                        },
                        onFailure = { _ ->
                            Toast.makeText(context, "Import failed: the JSON could not be parsed or the configuration is invalid. Check it and try again.", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            },
        )
    }

    // 批量导出弹窗
    if (showExportAllDialog) {
        ModelExportDialog(
            title = "Bulk export model profiles",
            subtitle = "Preparing to export ${models.size} model profile configurations",
            onDismiss = { showExportAllDialog = false },
            onGenerateJson = { includeKeys ->
                viewModel.exportAllProfilesJson(includeKeys)
            },
        )
    }

    // 单项导出弹窗
    singleExportModel?.let { targetModel ->
        ModelExportDialog(
            title = "Export model profile configuration",
            subtitle = "Model: ${targetModel.name} (${targetModel.provider})",
            defaultFileName = "taixu_model_${targetModel.name.replace(" ", "_")}.json",
            onDismiss = { singleExportModel = null },
            onGenerateJson = { includeKeys ->
                viewModel.exportSingleProfileJson(targetModel.id, includeKeys) ?: ""
            },
        )
    }

    // Delete确认弹窗
    modelPendingDelete?.let { target ->
        RuntimeAlertDialog(
            onDismissRequest = { modelPendingDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Alert, Modifier.size(20.dp), MaterialTheme.colorScheme.error)
                    Text("DeleteModel Profiles", fontWeight = FontWeight.Bold)
                }
            },
            text = { Text("Delete model profile “${target.name}”? This also removes the API key saved for this model.") },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        viewModel.deleteModel(target.id)
                        modelPendingDelete = null
                    },
                ) {
                    Text("Confirm delete")
                }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { modelPendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelProfilesContent(
    modifier: Modifier,
    models: List<AiModelEntity>,
    onCreate: () -> Unit,
    onEdit: (AiModelEntity) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (AiModelEntity) -> Unit,
    onExportSingle: (AiModelEntity) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            RuntimeButton(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
                    Text("Add model profile", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (models.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconTile(RuntimeIconName.Model, color = MaterialTheme.colorScheme.primary, size = 42.dp)
                    Text("No model profiles yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap Add above or import OpenAI, DeepSeek, Claude, or local model configuration from the top-right menu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(models, key = { it.id }) { model ->
            ModelProfileCard(
                model = model,
                onEdit = { onEdit(model) },
                onActivate = { onActivate(model.id) },
                onDelete = { onDelete(model) },
                onExport = { onExportSingle(model) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelProfileCard(
    model: AiModelEntity,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (model.isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        },
        onClick = onEdit,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        // 头部：Provider Badge + 标题 + 激活状态 + 更多菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderBadge(providerIdOrName = model.provider, size = 24.dp)

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = model.provider,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (model.isActive) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = "Active",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            } else {
                RuntimeTextButton(
                    onClick = onActivate,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Set active", style = MaterialTheme.typography.labelMedium)
                }
            }

            // More actions下拉菜单
            Box {
                RuntimeIconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(32.dp),
                    contentDescription = "More actions",
                ) {
                    RuntimeIcon(
                        name = RuntimeIconName.More,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuntimeIcon(RuntimeIconName.Edit, Modifier.size(16.dp))
                                Text("Edit profile")
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuntimeIcon(RuntimeIconName.OpenInNew, Modifier.size(16.dp))
                                Text("Export as JSON")
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                    )
                    if (!model.isActive) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RuntimeIcon(RuntimeIconName.Check, Modifier.size(16.dp))
                                    Text("Set active")
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onActivate()
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(16.dp), MaterialTheme.colorScheme.error)
                                Text("Delete profile", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }

        // 模型列表标签
        val modelList = remember(model.model) {
            model.model.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (modelList.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                modelList.take(3).forEach { modelId ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = modelId,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (modelList.size > 3) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = "+${modelList.size - 3} more",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        // 元数据摘要
        // 元数据摘要：显式配置优先，否则展示主流模型自动适配值。
        val explicitCtxTokens = model.contextTokens
        val autoCtxTokens = if (explicitCtxTokens == null) {
            ModelContextWindows.resolve(model.model, model.provider)
        } else null
        val metadataSummary = buildList {
            if (model.baseUrl.isNotBlank()) add(model.baseUrl)
            if (model.apiKeyCount > 0) add("${model.apiKeyCount} Key")
            if (model.requestsPerMinutePerKey > 0) add("${model.requestsPerMinutePerKey} RPM/Key")
            explicitCtxTokens?.let { add("${formatProfileContextWindow(it)} context") }
            autoCtxTokens?.let { add("Automatic ${formatProfileContextWindow(it)} context") }
        }.joinToString(" • ")

        if (metadataSummary.isNotBlank()) {
            Text(
                text = metadataSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}
}

private fun formatProfileContextWindow(tokens: Int): String = when {
    tokens <= 0 -> "0"
    tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens % 1_000 == 0 -> "${tokens / 1_000}k"
    else -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
}
