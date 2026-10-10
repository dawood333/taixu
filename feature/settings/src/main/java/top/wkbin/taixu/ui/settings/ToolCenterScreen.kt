package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.ui.components.RuntimeAlertDialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.wkbin.taixu.ui.components.SpotlightGuideOverlay
import top.wkbin.taixu.ui.components.rememberSpotlightAnchor
import top.wkbin.taixu.ui.components.spotlightAnchor
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import top.wkbin.taixu.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import top.wkbin.taixu.ui.components.RuntimeIconButton as IconButton
import top.wkbin.taixu.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.wkbin.taixu.ui.settings.LocalizedText as Text
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import top.wkbin.taixu.core.database.ToolEntity
import top.wkbin.taixu.core.model.ToolState
import top.wkbin.taixu.core.tools.ToolInstallProgress
import top.wkbin.taixu.core.tools.ToolVerification
import top.wkbin.taixu.ui.components.NoticeBanner
import top.wkbin.taixu.ui.components.InfoRow
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.StatusBadge
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import top.wkbin.taixu.core.tools.LocalPluginImportState
import top.wkbin.taixu.feature.components.R as ComponentsR
import top.wkbin.taixu.feature.settings.R

/**
 * 太墟 · 插件与 AI 工具中心 (Tool & Plugin Center)
 */
@Composable
fun ToolCenterScreen(
    onBack: () -> Unit,
    onLaunchPty: (toolId: String) -> Unit,
    onOpenToolDetail: (toolId: String) -> Unit = {},
    onStartAiHealing: (toolId: String, toolName: String, errorLogs: List<String>) -> Unit = { _, _, _ -> },
    viewModel: ToolCenterViewModel = koinViewModel(),
) {
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val installProgress by viewModel.installProgress.collectAsStateWithLifecycle()
    val verifications by viewModel.verifications.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val viewingLogsToolId by viewModel.viewingLogsToolId.collectAsStateWithLifecycle()
    val toolLogs by viewModel.toolLogs.collectAsStateWithLifecycle()

    val installedComponentIds by viewModel.installedComponentIds.collectAsStateWithLifecycle()
    val activeBundle by viewModel.activeBundle.collectAsStateWithLifecycle()
    val selectedComponents by viewModel.selectedComponents.collectAsStateWithLifecycle()
    val isInstallingComponents by viewModel.isInstallingComponents.collectAsStateWithLifecycle()
    val componentInstallProgress by viewModel.componentInstallProgress.collectAsStateWithLifecycle()
    val componentInstallLog by viewModel.componentInstallLog.collectAsStateWithLifecycle()
    val localPluginImport by viewModel.localPluginImport.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val operationError by viewModel.operationError.collectAsStateWithLifecycle()
    var showBundleInstallLog by remember { mutableStateOf(false) }
    var pendingUninstallId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUninstallName by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importLocalPlugin)
    }

    // 首次进入引导：高亮顶栏「Import offline plugin package」文件夹按钮
    val importGuideShown by viewModel.importGuideShown.collectAsStateWithLifecycle()
    val importAnchor = rememberSpotlightAnchor()

    val categories = listOf(
        "ALL" to "All tools",
        "BUNDLES" to "Full-stack development bundles",
        "SOURCE_LOCAL" to "Local plugins",
        "SOURCE_REMOTE" to "Online plugins",
        "CODING_AGENT" to "Coding assistants",
        "AI_AGENT" to "Agent ecosystem",
    )

    val showBundles = selectedCategory == "ALL" || selectedCategory == "BUNDLES"
    val showTools = selectedCategory != "BUNDLES"

    val filteredTools = tools.filter { tool ->
        when (selectedCategory) {
            "ALL" -> true
            "SOURCE_LOCAL" -> viewModel.toolSource(tool.id) == "LOCAL"
            "SOURCE_REMOTE" -> viewModel.toolSource(tool.id) != "LOCAL"
            else -> tool.category.equals(selectedCategory, ignoreCase = true)
        }
    }

    // 遮罩与 Scaffold 放在同一 Box 下（同层兄弟节点），保证遮罩坐标与按钮 boundsInRoot 同一参照系
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "Plugins & Tool Center",
                statusText = "${tools.count { it.state == ToolState.INSTALLED.name }} tools ready",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.spotlightAnchor(importAnchor),
                        contentDescription = "Import offline plugin package",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.FolderOpen,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.syncRegistry()
                            viewModel.refreshInstalledStatus()
                        },
                        enabled = !isSyncing,
                        contentDescription = if (isSyncing) "Refreshing plugin catalog" else "Refresh plugin catalog",
                    ) {
                        if (isSyncing) {
                            RuntimeCircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            RuntimeIcon(
                                name = RuntimeIconName.Refresh,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Category Filter Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(categories) { (catId, label) ->
                    val isSelected = selectedCategory == catId
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setCategory(catId) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (operationError != null) {
                    item(key = "tool_center_operation_error") {
                        NoticeBanner(
                            text = operationError.orEmpty(),
                            isError = true,
                            onDismiss = viewModel::consumeOperationError,
                        )
                    }
                }

                if (localPluginImport !is LocalPluginImportState.Idle) {
                    item {
                        val state = localPluginImport
                        val isImporting = state is LocalPluginImportState.Reading ||
                            state is LocalPluginImportState.Importing
                        val isError = state is LocalPluginImportState.Failed
                        val isPending = state is LocalPluginImportState.PendingConfirmation
                        val containerColor = when {
                            isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                            isImporting -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        }
                        val contentColor = when {
                            isError -> MaterialTheme.colorScheme.onErrorContainer
                            isImporting -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onTertiaryContainer
                        }
                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = containerColor,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (isImporting) {
                                        RuntimeCircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = contentColor,
                                        )
                                    } else {
                                        RuntimeIcon(
                                            name = if (isError) RuntimeIconName.Alert else RuntimeIconName.Check,
                                            modifier = Modifier.size(20.dp),
                                            tint = contentColor,
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = when (state) {
                                                is LocalPluginImportState.Reading -> "正在读取Local plugins信息"
                                                is LocalPluginImportState.PendingConfirmation -> "等待确认InstallLocal plugins"
                                                is LocalPluginImportState.Importing -> "正在InstallLocal plugins"
                                                is LocalPluginImportState.Succeeded -> "Local pluginsInstall完成"
                                                is LocalPluginImportState.AlreadyImported -> "Plugin imported"
                                                is LocalPluginImportState.Failed -> "Local pluginsInstall失败"
                                                LocalPluginImportState.Idle -> ""
                                            },
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = contentColor,
                                        )
                                        Text(
                                            text = when (state) {
                                                is LocalPluginImportState.Reading -> "${state.fileName}: reading manifest.json; plugin assets have not been copied or extracted yet"
                                                is LocalPluginImportState.PendingConfirmation -> "${state.fileName}: manifest read. Confirm installation in the dialog."
                                                is LocalPluginImportState.Importing -> {
                                                    val percent = state.progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                                                    "${state.fileName}$percent · ${state.currentEntry ?: "Extracting plugin assets"}，Large ARM64 plugins may take several minutes. Keep the app open."
                                                }
                                                is LocalPluginImportState.Succeeded -> "${state.pluginName} v${state.version} extracted, installed, and verified"
                                                is LocalPluginImportState.AlreadyImported -> "${state.pluginName} v${state.version} already exists; no need to import it again. Install or retry it from the plugin list below."
                                                is LocalPluginImportState.Failed -> state.message
                                                LocalPluginImportState.Idle -> ""
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = contentColor,
                                            maxLines = if (isError) 5 else 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (!isImporting && !isPending) {
                                        TextButton(onClick = viewModel::clearLocalPluginImportState) {
                                            Text("Close", color = contentColor)
                                        }
                                    }
                                }
                                if (isImporting && state is LocalPluginImportState.Importing) {
                                    LinearProgressIndicator(
                                        progress = { state.progress ?: 0.03f },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = contentColor,
                                    )
                                }
                                if (state is LocalPluginImportState.Reading) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = contentColor,
                                    )
                                }
                            }
                        }
                    }
                }

                // 🚀 后台装配进行中提示卡片 (Background Installing Banner)
                if (isInstallingComponents || componentInstallLog.isNotEmpty()) {
                    item {
                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    RuntimeCircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isInstallingComponents) "Installing development bundle in the background..." else "Most recent development bundle installation",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = componentInstallProgress ?: if (isInstallingComponents) "Batch installation is running in the background. You can switch to other screens." else "Installation finished. View the full log for details.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                }
                                if (isInstallingComponents) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                TextButton(
                                    onClick = { showBundleInstallLog = true },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) {
                                    Text("View installation log", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                // 1. 聚合大套件专区 (Plugin Bundles)
                if (showBundles) {
                    item {
                        Text(
                            text = "Full-stack developer bundles",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }

                    items(viewModel.pluginBundles, key = { it.id }) { bundle ->
                        val installedCount = bundle.components.count { it.id in installedComponentIds }
                        val isCoreReady = bundle.components.filter { it.isRequired }.all { it.id in installedComponentIds }

                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewModel.openBundleSetup(bundle) },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            borderColor = if (isCoreReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isCoreReady) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        RuntimeIcon(
                                            name = when (bundle.iconName) {
                                                "Android" -> RuntimeIconName.Android
                                                "Flutter" -> RuntimeIconName.Flutter
                                                "Globe" -> RuntimeIconName.Globe
                                                "Search" -> RuntimeIconName.Search
                                                else -> RuntimeIconName.Code
                                            },
                                            modifier = Modifier.size(22.dp),
                                            tint = if (isCoreReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = bundle.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f, fill = false),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isCoreReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                modifier = Modifier.wrapContentWidth(),
                                            ) {
                                                Text(
                                                    text = if (isCoreReady) "Ready ($installedCount/${bundle.components.size})" else "Not installed ($installedCount/${bundle.components.size})",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = if (isCoreReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                        Text(
                                            text = bundle.summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                // 子组件标签胶囊列表（FlowRow 自动换行，绝不挤压）
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    bundle.components.forEach { comp ->
                                        val isInstalled = comp.id in installedComponentIds
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isInstalled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                if (comp.isRequired) {
                                                    RuntimeIcon(RuntimeIconName.Shield, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary)
                                                }
                                                Text(
                                                    text = if (comp.isRequired) "${comp.name} (required)" else comp.name,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = if (isInstalled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    FilledTonalButton(
                                        onClick = { viewModel.openBundleSetup(bundle) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            RuntimeIcon(RuntimeIconName.Tune, Modifier.size(14.dp))
                                            Text(
                                                text = if (isCoreReady) "Manage components" else "Install bundle",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. 独立 Agent 工具与服务专区
                if (showTools && filteredTools.isNotEmpty()) {
                    if (showBundles) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Agent ecosystem与服务 (Agents & Services)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }

                    items(filteredTools, key = { it.id }) { tool ->
                        ToolCard(
                            tool = tool,
                            source = viewModel.toolSource(tool.id),
                            progress = installProgress[tool.id],
                            verification = verifications[tool.id],
                            onInstall = { viewModel.installTool(tool.id) },
                            onUpdate = { viewModel.updateTool(tool.id) },
                            onUninstall = {
                                pendingUninstallId = tool.id
                                pendingUninstallName = tool.name
                            },
                            onVerify = { viewModel.verifyTool(tool.id) },
                            onLaunch = { onLaunchPty(tool.id) },
                            onViewLogs = { viewModel.viewLogs(tool.id) },
                            onOpenDetail = { onOpenToolDetail(tool.id) },
                            onStartAiHealing = {
                                // 与Logs弹窗「AI repair」入口一致：始终传入该工具的全量Logs
                                viewModel.fullToolLogs(tool.id) { logs ->
                                    onStartAiHealing(tool.id, tool.name, logs)
                                }
                            },
                        )
                    }
                }

                // 分类下无任何工具时给出空态提示，避免列表区完全空白
                if (showTools && filteredTools.isEmpty()) {
                    item(key = "tool_center_empty_category") {
                        val emptyTitle = androidx.compose.ui.res.stringResource(R.string.settings_tool_center_empty_category_title)
                        val emptySubtitle = androidx.compose.ui.res.stringResource(R.string.settings_tool_center_empty_category_subtitle)
                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            contentPadding = PaddingValues(24.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Package,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                                Text(
                                    text = emptyTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = emptySubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // A package is only committed/installed after explicit confirmation.
        (localPluginImport as? LocalPluginImportState.PendingConfirmation)?.let { pending ->
            val manifest = pending.manifest
            RuntimeAlertDialog(
                onDismissRequest = viewModel::cancelLocalPluginImport,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Extension, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                        Text("确认InstallLocal plugins", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(manifest.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfoRow("Version", manifest.version, isCode = false)
                        InfoRow("Source", "Local plugins", isCode = false)
                        InfoRow("Architecture", manifest.architectures.joinToString(", "), isCode = false)
                        InfoRow("Package size", pending.archiveSizeBytes?.let { formatBytes(it) } ?: "Unknown", isCode = false)
                        InfoRow("Offline mode", if (manifest.offlineOnly) "Yes (do not fetch online dependencies)" else "No", isCode = false)
                        Text("Assets are extracted and installation scripts run only after you tap Install. Canceling leaves the plugin directory unchanged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelLocalPluginImport) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = viewModel::confirmLocalPluginImport) { Text("Install") }
                },
            )
        }

        // Install Logs Dialog
        if (viewingLogsToolId != null) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val toolId = viewingLogsToolId ?: ""
            val toolName = tools.firstOrNull { it.id == toolId }?.name ?: toolId
            val hasErrors = toolLogs.any { it.event.contains("FAIL", ignoreCase = true) || it.message.startsWith("ERR") }

            RuntimeAlertDialog(
                onDismissRequest = { viewModel.viewLogs(null) },
                title = { Text("Tool execution log ($toolName)") },
                text = {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 360.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (toolLogs.isEmpty()) {
                                Text(
                                    text = "No related event logs",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            } else {
                                toolLogs.forEach { log ->
                                    Text(
                                        text = "[${log.event}] ${log.message}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                        ),
                                        color = if (log.event.contains("FAIL", ignoreCase = true) || log.message.startsWith("ERR:")) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasErrors) {
                            Button(
                                onClick = {
                                    val fullLogs = toolLogs.map { "[${it.event}] ${it.message}" }
                                    viewModel.viewLogs(null)
                                    onStartAiHealing(toolId, toolName, fullLogs)
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Brain,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("🧠 AI repair", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        TextButton(
                            onClick = {
                                val fullLogs = toolLogs.joinToString("\n") { "[${it.event}] ${it.message}" }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("tool_logs", fullLogs)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            enabled = toolLogs.isNotEmpty(),
                        ) {
                            Text("Copy")
                        }
                        TextButton(
                            onClick = { viewModel.clearLogs(toolId) },
                            enabled = toolLogs.isNotEmpty(),
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { viewModel.viewLogs(null) }) {
                            Text("Close")
                        }
                    }
                },
            )
        }

        if (showBundleInstallLog) {
            val context = androidx.compose.ui.platform.LocalContext.current
            RuntimeAlertDialog(
                onDismissRequest = { showBundleInstallLog = false },
                title = { Text("开发套件InstallLogs") },
                text = {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (componentInstallLog.isEmpty()) {
                                Text("InstallLogs正在接收...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            } else {
                                componentInstallLog.forEach { line ->
                                    val isError = line.contains("error", ignoreCase = true) ||
                                        line.contains("failed", ignoreCase = true) ||
                                        line.contains("失败") ||
                                        line.contains("returned an error", ignoreCase = true) ||
                                        line.startsWith("E:")
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("TaiXu Bundle Install Log", componentInstallLog.joinToString("\n")))
                                // 与工具LogsCopy行为保持一致，Copy后给出反馈
                                Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            enabled = componentInstallLog.isNotEmpty(),
                        ) { Text("Copy") }
                        TextButton(onClick = { showBundleInstallLog = false }) { Text("Close") }
                    }
                },
            )
        }

        // 卡片级Uninstall二次确认（与工具详情页同一弹窗组件）
        pendingUninstallId?.let { uninstallId ->
            UninstallToolConfirmDialog(
                toolName = pendingUninstallName.orEmpty(),
                onConfirm = {
                    viewModel.uninstallTool(uninstallId)
                    pendingUninstallId = null
                    pendingUninstallName = null
                },
                onDismiss = {
                    pendingUninstallId = null
                    pendingUninstallName = null
                },
            )
        }

        activeBundle?.let { bundle ->
            BundleComponentSetupDialog(
                bundle = bundle,
                installedComponentIds = installedComponentIds,
                selectedComponents = selectedComponents,
                isInstallingComponents = isInstallingComponents,
                componentInstallProgress = componentInstallProgress,
                onToggle = viewModel::toggleComponent,
                onInstall = viewModel::installActiveBundleComponents,
                onReinstall = viewModel::reinstallComponent,
                onUninstall = viewModel::uninstallComponent,
                uninstallBlockedReason = viewModel::uninstallBlockedReason,
                onDismiss = viewModel::closeBundleSetup,
            )
        }
    }

    // 首次进入引导遮罩：高亮顶栏「Import offline plugin package」入口（组件内部等待按钮完成布局测量后才绘制）
    if (!importGuideShown) {
        SpotlightGuideOverlay(
            anchor = importAnchor,
            title = "Import offline plugin package",
            message = "Have you downloaded the complete offline plugin package? Tap the folder icon in the top-right and select the ZIP file to import it. " +
                "Install Android, Flutter, and decompilation tools in one go, without a network connection.",
            icon = RuntimeIconName.FolderOpen,
            onDismiss = viewModel::markImportGuideShown,
        )
    }
    } // Box
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}



/**
 * 工具品牌专属视觉 Avatar
 */
@Composable
private fun ToolBrandAvatar(
    toolId: String,
    category: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 46.dp,
) {
    val key = toolId.lowercase().trim()
    val (logoRes, emoji, brandColor) = when {
        key.contains("claude") || key.contains("anthropic") ->
            Triple(ComponentsR.drawable.components_ic_provider_anthropic, null, Color(0xFFD97757))
        key.contains("codex") || key.contains("openai") ->
            Triple(ComponentsR.drawable.components_ic_provider_openai, null, Color(0xFF10A37F))
        key.contains("android") ->
            Triple(null, "🤖", Color(0xFF3DDC84))
        key.contains("devtools") || key.contains("base-devtools") ->
            Triple(null, "⚡", Color(0xFF0284C7))
        key.contains("hello") ->
            Triple(null, "🧪", Color(0xFF10B981))
        key.contains("deepseek") ->
            Triple(ComponentsR.drawable.components_ic_provider_deepseek, null, Color(0xFF4D6BFE))
        key.contains("ollama") ->
            Triple(ComponentsR.drawable.components_ic_provider_ollama, null, Color(0xFF334155))
        else -> when (category) {
            "CODING_AGENT" -> Triple(null, "💻", Color(0xFF6366F1))
            "AI_AGENT" -> Triple(null, "🤖", Color(0xFF0EA5E9))
            else -> Triple(null, "📦", Color(0xFF64748B))
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(brandColor.copy(alpha = 0.12f))
            .border(1.dp, brandColor.copy(alpha = 0.28f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (logoRes != null) {
            Box(
                modifier = Modifier
                    .size(size * 0.72f)
                    .clip(CircleShape)
                    .background(brandColor),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(logoRes),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        } else if (emoji != null) {
            Text(
                text = emoji,
                fontSize = (size.value * 0.44f).sp,
            )
        } else {
            RuntimeIcon(
                name = RuntimeIconName.Package,
                modifier = Modifier.size(size * 0.48f),
                tint = brandColor,
            )
        }
    }
}

/**
 * 单个工具与插件展示卡片
 */
@Composable
private fun ToolCard(
    tool: ToolEntity,
    source: String,
    progress: ToolInstallProgress?,
    verification: ToolVerification?,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onVerify: () -> Unit,
    onLaunch: () -> Unit,
    onViewLogs: () -> Unit,
    onOpenDetail: () -> Unit,
    onStartAiHealing: () -> Unit = {},
) {
    val isInstalled = tool.state == ToolState.INSTALLED.name || tool.state == ToolState.UPDATE_AVAILABLE.name
    val isInstalling = tool.state == ToolState.INSTALLING.name
    val isUpdateAvailable = tool.state == ToolState.UPDATE_AVAILABLE.name
    val isFailed = tool.state == ToolState.FAILED.name

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenDetail,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: Avatar + Title/Publisher + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    ToolBrandAvatar(
                        toolId = tool.id,
                        category = tool.category,
                        size = 46.dp,
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val displayVersion = tool.installedVersion ?: tool.manifestVersion
                            if (displayVersion.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        text = "v$displayVersion",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                            Surface(
                                color = if (source == "LOCAL") {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = if (source == "LOCAL") "Local plugins" else "Online plugins",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (source == "LOCAL") {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }

                        Text(
                            text = "${tool.publisher} · ${when (tool.category) {
                                "CODING_AGENT" -> "Coding agent"
                                "AI_AGENT" -> "AI agent"
                                "DEVELOPER_TOOL" -> "Developer toolkit"
                                else -> tool.category
                            }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Status Badge
                StatusBadge(
                    text = when {
                        isInstalling -> "Install中"
                        isUpdateAvailable -> "Update available"
                        isInstalled -> "Ready"
                        isFailed -> "Install失败"
                        else -> "未Install"
                    },
                    color = when {
                        isUpdateAvailable -> MaterialTheme.colorScheme.primary
                        isInstalled -> successStatusColor()
                        isInstalling -> MaterialTheme.colorScheme.tertiary
                        isFailed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            }

            // Description with comfortable reading line height
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Installing Progress Bar
            if (isInstalling && progress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = progress.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        progress.progress?.let { p ->
                            Text(
                                text = "${(p * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress.progress ?: 0.5f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Verification info (if any)
            if (verification != null && !verification.healthy) {
                NoticeBanner(text = "Self-check failed: ${verification.detail}", isError = true)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))

            // Actions row: Spacious, distinct secondary buttons on left, prominent action on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Secondary actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = onOpenDetail,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("Configure", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }

                    TextButton(
                        onClick = onViewLogs,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text("Logs", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }

                    if (isInstalled) {
                        TextButton(
                            onClick = onUninstall,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text("Uninstall", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                        }
                    }
                }

                // Primary actions (Install / Retry / Update)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isUpdateAvailable) {
                        Button(
                            onClick = onUpdate,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text("Update", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    } else if (isFailed) {
                        Button(
                            onClick = onStartAiHealing,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Brain,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text("AI repair", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = onInstall,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Download,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text("重试", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    } else if (!isInstalled && !isInstalling) {
                        Button(
                            onClick = onInstall,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Download,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Install", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}