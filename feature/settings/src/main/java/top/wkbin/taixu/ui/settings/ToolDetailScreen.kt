package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import top.wkbin.taixu.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.wkbin.taixu.ui.components.RuntimeSwitch as Switch
import top.wkbin.taixu.ui.settings.LocalizedText as Text
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.model.ToolState
import top.wkbin.taixu.ui.components.CodeBlockRow
import top.wkbin.taixu.ui.components.InfoRow
import top.wkbin.taixu.ui.components.NoticeBanner
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.StatusBadge
import kotlinx.coroutines.delay
import top.wkbin.taixu.core.database.ToolEntity
import top.wkbin.taixu.core.model.ToolManifest
import top.wkbin.taixu.feature.components.R

/**
 * 太墟 · 插件Configuration details页 (Tool Detail & Configuration Screen)
 *
 * 功能概览：
 * - 工具概览与状态
 * - 网关/服务管理（Web 类工具）
 * - 一键ApplyModel configuration
 * - 自Start开关
 * - 生成带 Token 的Access URL
 * - 工具元信息面板
 * - Actions按钮（自检、终端、卸载）
 */
@Composable
fun ToolDetailScreen(
    toolId: String,
    onBack: () -> Unit,
    onLaunchTerminal: (toolId: String) -> Unit,
    onStartAiHealing: (toolId: String, toolName: String, errorLogs: List<String>) -> Unit = { _, _, _ -> },
    viewModel: ToolDetailViewModel = koinViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(toolId) {
        viewModel.setToolId(toolId)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tool = state.tool
    val manifest = state.manifest

    // 加载中与「Tool not found」区分：首次数据未就绪时显示进度圈，超时仍未找到才判定为不存在
    var hasLoadedOnce by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(tool) {
        if (tool != null) hasLoadedOnce = true
    }
    // Uninstall tool属于破坏性Actions，需二次确认
    var showUninstallConfirm by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    if (showUninstallConfirm && tool != null) {
        UninstallToolConfirmDialog(
            toolName = tool.name,
            onConfirm = {
                showUninstallConfirm = false
                viewModel.uninstallTool()
            },
            onDismiss = { showUninstallConfirm = false },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = tool?.name ?: toolId,
                statusText = "Configuration details",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        if (tool == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (!hasLoadedOnce) {
                    // 工具列表尚未加载完成：显示加载中而非「未找到」
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Text("Loading tool information…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Tool not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── 错误提示 / AI 自愈入口 ──
            if (tool.state == ToolState.FAILED.name || state.error != null) {
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Brain,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Tool installation/environment check failed",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = state.error ?: "Ask the TaiXu Agent to diagnose and repair the issue inside the PRoot sandbox",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onStartAiHealing(
                                    tool.id,
                                    tool.name,
                                    state.error?.let { listOf(it) } ?: emptyList(),
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("Run AI repair", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // ── 1. 工具概览卡片 ──
            ToolOverviewCard(
                tool = tool,
                manifest = manifest,
                gatewayRunning = state.gatewayRunning,
                gatewayOperating = state.gatewayOperating,
            )

            // ── 2. 网关/服务管理（仅 Web 类工具） ──
            if (state.isWebService) {
                GatewayManagementCard(
                    running = state.gatewayRunning,
                    operating = state.gatewayOperating,
                    port = state.servicePort,
                    onStart = viewModel::startGateway,
                    onStop = viewModel::stopGateway,
                )

                // ── 2.5 Live service console log ──
                ServiceLogsCard(
                    logs = state.serviceLogs,
                    running = state.gatewayRunning,
                    onClear = viewModel::clearServiceLogs,
                    onCopy = { text ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("service_logs", text))
                        Toast.makeText(context, "Console log copied", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // ── 3. Model configuration（仅 AI 类工具：AI Agent / Coding Agent，非 AI 的纯开发工具不需要模型注入） ──
            if (tool.category == "AI_AGENT" || tool.category == "CODING_AGENT") {
                ModelApplyCard(
                    toolName = tool.name,
                    models = state.models,
                    appliedModelId = state.appliedModelId,
                    applying = state.applyingModel,
                    onApply = viewModel::applyModel,
                )
            }

            // ── 4. 自Start配置 ──
            if (state.isWebService) {
                AutoStartCard(
                    enabled = state.autoStartEnabled,
                    toolName = tool.name,
                    onToggle = viewModel::toggleAutoStart,
                )
            }

            // ── 5. 带 Token Access URL（仅 Web 类工具，支持LAN IP / 0.0.0.0 / 127.0.0.1） ──
            if (state.isWebService) {
                AccessLinkCard(
                    state = state,
                    running = state.gatewayRunning,
                    onGenerate = viewModel::generateToken,
                    onClear = viewModel::clearToken,
                    onCopy = { url ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("tool_url", url))
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // ── 6. 工具元信息面板 ──
            if (manifest != null) {
                ToolMetadataCard(tool = tool, manifest = manifest)
            }

            // ── 7. Actions区 ──
            ToolActionsCard(
                isInstalled = tool.state == ToolState.INSTALLED.name || tool.state == ToolState.UPDATE_AVAILABLE.name,
                onLaunchTerminal = { onLaunchTerminal(toolId) },
                onUninstall = { showUninstallConfirm = true },
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// 子组件
// ════════════════════════════════════════════════════════════════════════

/**
 * 工具品牌专属视觉 Avatar（复用 ToolCenterScreen 的映射逻辑）
 */
@Composable
private fun DetailToolAvatar(
    toolId: String,
    category: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp,
) {
    val key = toolId.lowercase().trim()
    val (logoRes, emoji, brandColor) = when {
        key.contains("claude") || key.contains("anthropic") ->
            Triple(R.drawable.components_ic_provider_anthropic, null, Color(0xFFD97757))
        key.contains("codex") || key.contains("openai") ->
            Triple(R.drawable.components_ic_provider_openai, null, Color(0xFF10A37F))
        key.contains("android") ->
            Triple(null, "🤖", Color(0xFF3DDC84))
        key.contains("devtools") || key.contains("base-devtools") ->
            Triple(null, "⚡", Color(0xFF0284C7))
        key.contains("hello") ->
            Triple(null, "🧪", Color(0xFF10B981))
        else -> when (category) {
            "CODING_AGENT" -> Triple(null, "💻", Color(0xFF6366F1))
            "AI_AGENT" -> Triple(null, "🤖", Color(0xFF0EA5E9))
            else -> Triple(null, "📦", Color(0xFF64748B))
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(brandColor.copy(alpha = 0.12f))
            .border(1.dp, brandColor.copy(alpha = 0.28f), RoundedCornerShape(16.dp)),
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
                        .padding(6.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        } else if (emoji != null) {
            Text(text = emoji, fontSize = (size.value * 0.44f).sp)
        } else {
            RuntimeIcon(
                name = RuntimeIconName.Package,
                modifier = Modifier.size(size * 0.48f),
                tint = brandColor,
            )
        }
    }
}

/** 1. 工具概览 */
@Composable
private fun ToolOverviewCard(
    tool: ToolEntity,
    manifest: ToolManifest?,
    gatewayRunning: Boolean,
    gatewayOperating: Boolean,
) {
    val isInstalled = tool.state == ToolState.INSTALLED.name || tool.state == ToolState.UPDATE_AVAILABLE.name
    val rawVersion = (tool.installedVersion ?: tool.manifestVersion).trim()
    val formattedVersion = if (rawVersion.isNotBlank()) {
        var clean = rawVersion
        if (clean.startsWith(tool.name, ignoreCase = true)) {
            clean = clean.substring(tool.name.length).trim()
        }
        if (clean.startsWith("v", ignoreCase = true)) {
            clean = clean.substring(1).trim()
        }
        if (clean.isNotBlank()) "v$clean" else null
    } else null

    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                DetailToolAvatar(toolId = tool.id, category = tool.category)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${tool.publisher} · ${
                                when (tool.category) {
                                    "CODING_AGENT" -> "Coding agent"
                                    "AI_AGENT" -> "AI agent"
                                    "DEVELOPER_TOOL" -> "Developer toolkit"
                                    else -> tool.category
                                }
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (formattedVersion != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    text = formattedVersion,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            StatusBadge(
                text = when {
                    gatewayOperating -> "Starting"
                    gatewayRunning -> "Running"
                    tool.state == ToolState.UPDATE_AVAILABLE.name -> "Update available"
                    isInstalled -> "Ready"
                    tool.state == ToolState.FAILED.name -> "Error"
                    else -> "Not installed"
                },
                color = when {
                    gatewayOperating -> warningStatusColor()
                    gatewayRunning -> successStatusColor()
                    isInstalled -> successStatusColor()
                    tool.state == ToolState.FAILED.name -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                },
                pulsing = gatewayRunning || gatewayOperating,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = tool.description,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 2. 网关/服务管理 */
@Composable
private fun GatewayManagementCard(
    running: Boolean,
    operating: Boolean,
    port: Int?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeIcon(
                        name = RuntimeIconName.Globe,
                        modifier = Modifier.size(18.dp),
                        tint = if (running) successStatusColor() else MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = "Gateway service",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val statusColor = when {
                        running -> successStatusColor()
                        operating -> warningStatusColor()
                        else -> MaterialTheme.colorScheme.outline
                    }
                    val statusText = when {
                        running -> "Running"
                        operating -> "Starting…"
                        else -> "Stopped"
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            running -> successStatusColor()
                            operating -> warningStatusColor()
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (port != null) {
                        Text(
                            text = "· Port $port",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (operating) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
            } else if (running) {
                OutlinedButton(
                    onClick = onStop,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    RuntimeIcon(name = RuntimeIconName.Stop, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = onStart,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    RuntimeIcon(name = RuntimeIconName.Play, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Gateway service控制台实时日志卡片 */
@Composable
private fun ServiceLogsCard(
    logs: List<String>,
    running: Boolean,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val logScrollState = rememberScrollState()

    // 当有新日志写入时自动滚动到底部
    androidx.compose.runtime.LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logScrollState.animateScrollTo(logScrollState.maxValue)
        }
    }

    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.Terminal,
                    modifier = Modifier.size(18.dp),
                    tint = if (running) successStatusColor() else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Live service console log",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (logs.isNotEmpty()) {
                    IconButton(
                        onClick = { onCopy(logs.joinToString("\n")) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(30.dp),
                        contentDescription = "Copy service log",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Copy,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(30.dp),
                        contentDescription = "Clear service log",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Trash,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF14171A),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2E33)),
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无服务日志。点击【Start】网关后将在此实时输出控制台信息与Error报错。",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = Color(0xFF71767B),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(logScrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    logs.forEach { line ->
                        val textColor = when {
                            line.contains("[TaiXu]") -> Color(0xFF00B4D8)
                            line.contains("error", ignoreCase = true) || line.contains("fail", ignoreCase = true) || line.contains("ERR", ignoreCase = true) -> Color(0xFFFF6B6B)
                            line.contains("warn", ignoreCase = true) -> Color(0xFFFFD166)
                            line.contains("http://", ignoreCase = true) || line.contains("https://", ignoreCase = true) -> Color(0xFF06D6A0)
                            else -> Color(0xFFE0E0E0)
                        }
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

/** 3. 一键ApplyModel configuration */
@Composable
private fun ModelApplyCard(
    toolName: String,
    models: List<AiModelEntity>,
    appliedModelId: String?,
    applying: Boolean,
    onApply: (AiModelEntity) -> Unit,
) {
    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.Chat,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Model configuration",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = "AI plugin",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Inject the configured AI model (API key, base URL, and model) into $toolName. Changes take effect after restarting the plugin.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (models.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            NoticeBanner(text = "No model profiles configured. Go to Settings → Model Profiles to add Claude, OpenAI, or DeepSeek.")
        } else {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                models.forEach { model ->
                    val isApplied = model.id == appliedModelId
                    val isActive = model.isActive

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            isApplied -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            isActive -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        border = if (isApplied) {
                            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        } else null,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (isActive) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                text = "Active",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                    if (isApplied) {
                                        Surface(
                                            color = successStatusColor().copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                text = "Applied",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                color = successStatusColor(),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "${model.provider} · ${model.model.ifBlank { "Default model" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (applying) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else if (!isApplied) {
                                FilledTonalButton(
                                    onClick = { onApply(model) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "Apply",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                }
                            } else {
                                RuntimeIcon(
                                    name = RuntimeIconName.Check,
                                    modifier = Modifier.size(20.dp),
                                    tint = successStatusColor(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 4. 自Start配置 */
@Composable
private fun AutoStartCard(
    enabled: Boolean,
    toolName: String,
    onToggle: (Boolean) -> Unit,
) {
    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "随Apply自Start",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Linux 环境就绪后自动Start $toolName Gateway service",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

/** 5. 带 Token Access URL（支持LAN IP / 0.0.0.0 / 127.0.0.1 模式） */
@Composable
private fun AccessLinkCard(
    state: ToolDetailUiState,
    running: Boolean,
    onGenerate: () -> Unit,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var selectedMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("LAN") } // "LAN", "0.0.0.0", "127.0.0.1"

    val currentUrl = when (selectedMode) {
        "LAN" -> state.lanAccessUrl ?: state.allInterfacesAccessUrl ?: state.loopbackAccessUrl
        "0.0.0.0" -> state.allInterfacesAccessUrl
        else -> state.loopbackAccessUrl
    }

    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.Globe,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Access URL",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Surface(
                color = if (running) successStatusColor().copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = if (running) "Bound to 0.0.0.0" else "服务未Start",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = if (running) successStatusColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = if (running) {
                "Gateway service已绑定全网卡 (0.0.0.0)。同局域网设备打开【LAN IP 链接】即可直接Actions，无需 localhost 映射。"
            } else {
                "服务尚未Start，请先在【Gateway service】卡片中点击Start，Start成功后将在此展示可Access URL。Token 可提前生成，Start时自动注入。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))

        // 地址模式选择 (LAN IP / 0.0.0.0 / 127.0.0.1)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "LAN" to "LAN IP",
                "0.0.0.0" to "0.0.0.0",
                "127.0.0.1" to "127.0.0.1",
            ).forEach { (modeKey, label) ->
                val isSelected = selectedMode == modeKey
                Surface(
                    onClick = { selectedMode = modeKey },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .align(Alignment.CenterVertically),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }

        if (selectedMode == "LAN" && !state.deviceLanIp.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "设备LAN IP：${state.deviceLanIp}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 2.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        if (running && currentUrl != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = { onCopy(currentUrl) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(32.dp),
                        contentDescription = "复制Access URL",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Copy,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else if (!running) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    text = "Start网关后此处将展示可Access URL",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onGenerate,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (state.accessToken != null) "Regenerate token" else "Generate secure token",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            if (state.accessToken != null) {
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        "Clear token",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 6. 工具元信息 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ToolMetadataCard(
    tool: ToolEntity,
    manifest: ToolManifest,
) {
    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimeIcon(
                name = RuntimeIconName.Settings,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Tool information",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(label = "Tool ID", value = tool.id)
            InfoRow(
                label = "Start类型",
                value = when (manifest.launchType) {
                    "web" -> "Web Gateway service"
                    "pty" -> "Interactive terminal"
                    "one_shot" -> "One-shot command"
                    else -> manifest.launchType
                },
                isCode = false,
            )
            manifest.servicePort?.let { InfoRow(label = "Service port", value = it.toString()) }
            InfoRow(label = "Installation method", value = manifest.installMethod, isCode = false)

            manifest.launchCommand?.let {
                CodeBlockRow(label = "Start命令", code = it)
            }
            manifest.verifyCommand?.let {
                CodeBlockRow(label = "Verification command", code = it)
            }

            CodeBlockRow(label = "Installation path", code = "/opt/taixu/tools/${tool.id}")
            CodeBlockRow(label = "Data directory", code = "/opt/taixu/data/${tool.id}")

            if (manifest.dependencies.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                Text(
                    text = "Dependencies",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    manifest.dependencies.forEach { dep ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = dep,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            if (manifest.environment.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                Text(
                    text = "Environment variables",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        manifest.environment.forEach { (key, value) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            if (tool.permissions.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                Text(
                    text = "Permissions",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tool.permissions.split(",").filter { it.isNotBlank() }.forEach { perm ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = perm.trim(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 7. Actions区 */
@Composable
private fun ToolActionsCard(
    isInstalled: Boolean,
    onLaunchTerminal: () -> Unit,
    onUninstall: () -> Unit,
) {
    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimeIcon(
                name = RuntimeIconName.More,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isInstalled) {
                FilledTonalButton(
                    onClick = onLaunchTerminal,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    RuntimeIcon(name = RuntimeIconName.Terminal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open terminal", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onUninstall,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    RuntimeIcon(name = RuntimeIconName.Trash, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Uninstall tool", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    text = "工具尚Not installed，请先在工具中心完成安装",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Uninstall tool二次确认弹窗（与 DistroManagementScreen 删除确认同款倒计时锁样式） */
@Composable
internal fun UninstallToolConfirmDialog(
    toolName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var countdown by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(3) }

    LaunchedEffect(toolName) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Uninstall $toolName?",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "• 将移除该工具及其在沙箱内的Data directory",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "• You can reinstall it from the Tool Center at any time",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = countdown == 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                ),
            ) {
                Text(
                    text = if (countdown > 0) "确认卸载 (${countdown}s)" else "确认卸载",
                    color = if (countdown == 0) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onError.copy(alpha = 0.6f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
    )
}

