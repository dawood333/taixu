package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.model.CcAgentState
import top.wkbin.taixu.core.model.CcAgentType
import top.wkbin.taixu.core.model.CcProviderProfile
import top.wkbin.taixu.ui.components.InfoRow
import top.wkbin.taixu.ui.components.NoticeBanner
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeFilledTonalButton
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton
import top.wkbin.taixu.ui.components.RuntimeTextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.StatusBadge
import top.wkbin.taixu.core.model.CcSwitchDaemonStatus

/**
 * 太墟 · CC-Switch 智能体中枢主界面
 *
 * 核心功能：
 * - 守护进程与反代服务启停监控（端口 19870）
 * - Claude Code / OpenClaw / Hermes / Codex 矩阵管理
 * - 一键热切换各 Agent 的 Provider（支持从太墟本地模型库同步）
 * - 版本查看与一键升降级
 * - 实时Install与编译构建日志监视（支持Copy全量报错）
 * - Token 记账与反向代理状态监控
 * - 原生 PTY 终端拉起与系统外部浏览器 Web Console访问
 */
@Composable
fun CcSwitchScreen(
    onBack: () -> Unit,
    onLaunchTerminal: (executable: String) -> Unit,
    onOpenBrowser: (url: String) -> Unit,
    viewModel: CcSwitchViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            RuntimeTopBar(
                title = "Agent Hub (CC-Switch)",
                statusText = if (state.isDaemonRunning) "Hub running · 19870" else "Hub not started",
                onBack = onBack,
                actions = {
                    RuntimeIconButton(
                        onClick = { viewModel.refreshStatus() },
                        enabled = !state.isOperating,
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Refresh,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 提示横幅
            state.errorMessage?.let { error ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoticeBanner(
                        text = error,
                        isError = true,
                        onDismiss = { viewModel.dismissMessage() },
                    )
                    if (state.installLogs.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            RuntimeOutlinedButton(
                                onClick = { viewModel.showInstallLogs() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(name = RuntimeIconName.Terminal, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("View and copy full error log", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            state.successMessage?.let { msg ->
                NoticeBanner(
                    text = msg,
                    isError = false,
                    onDismiss = { viewModel.dismissMessage() },
                )
            }

            // 1. 守护进程卡片
            DaemonServiceCard(
                state = state,
                onStart = { viewModel.startDaemon() },
                onStop = { viewModel.stopDaemon() },
                onRestart = { viewModel.restartDaemon() },
                onResetPassword = { viewModel.resetWebPassword(it) },
                onOpenWebConsole = {
                    val targetUrl = state.loopbackUrl.ifBlank { state.preferredWebUrl }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching {
                        context.startActivity(intent)
                    }.onFailure {
                        onOpenBrowser(targetUrl)
                    }
                },
            )

            // 2. Token & 代理记账看板
            state.daemonStatus?.let { daemon ->
                TokenUsageCard(daemon = daemon)
            }

            // 3. Local environment check矩阵
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Local environment check",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (state.isDaemonRunning) "CC-Switch manages CLI tools and Agent runtimes" else "Start the hub to check environments and versions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.installLogs.isNotEmpty()) {
                        RuntimeOutlinedButton(
                            onClick = { viewModel.showInstallLogs() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            RuntimeIcon(name = RuntimeIconName.Terminal, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Installation log", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }
                    if (state.isDaemonRunning) {
                        RuntimeFilledTonalButton(
                            onClick = { viewModel.refreshEnvironment() },
                            enabled = !state.isOperating && !state.isCheckingEnvironment,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            if (state.isCheckingEnvironment) {
                                RuntimeCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Checking...", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            } else {
                                RuntimeIcon(name = RuntimeIconName.Refresh, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Refresh checks", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.agents.forEach { agent ->
                    AgentCard(
                        agent = agent,
                        isDaemonRunning = state.isDaemonRunning,
                        isOperating = state.isOperating,
                        isInstalling = state.installingAgentType == agent.type,
                        onSwitchProvider = { viewModel.openSwitchProviderDialog(agent) },
                        onInstall = { viewModel.installOrUpgradeAgent(agent, targetVersion = null) },
                        onUpgrade = { viewModel.installOrUpgradeAgent(agent, targetVersion = agent.latestVersion) },
                        onShowLogs = { viewModel.showInstallLogs() },
                    )
                }
            }

            // 底部高级同步区
            AdvancedSyncCard(
                taiXuModels = state.taiXuModels,
                onImportModel = { viewModel.importTaiXuModel(it) },
            )
        }
    }

    // 切换 Provider 弹窗
    state.switchingAgent?.let { agent ->
        SwitchProviderDialog(
            agent = agent,
            providers = state.providers,
            taiXuModels = state.taiXuModels,
            onDismiss = { viewModel.dismissDialogs() },
            onSelectProvider = { provider -> viewModel.switchAgentProvider(agent, provider) },
            onImportAndSelect = { model ->
                viewModel.importTaiXuModel(model)
            },
        )
    }

    // 版本管理 / Upgrade弹窗
    state.upgradingAgent?.let { agent ->
        UpgradeVersionDialog(
            agent = agent,
            onDismiss = { viewModel.dismissDialogs() },
            onConfirmUpgrade = { version -> viewModel.installOrUpgradeAgent(agent, version) },
        )
    }

    // Install与构建实时日志弹窗
    if (state.showLogDialog) {
        InstallLogDialog(
            agentName = state.logAgentTitle,
            logs = state.installLogs,
            isOperating = state.isOperating,
            isError = state.errorMessage != null,
            onDismiss = { viewModel.dismissInstallLogs() },
            onClear = { viewModel.clearInstallLogs() },
            onCopy = { logsText ->
                copyText(context, logsText, "Copied full installation log (${state.installLogs.size} lines)")
            },
        )
    }
}

@Composable
private fun DaemonServiceCard(
    state: CcSwitchUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onResetPassword: (String) -> Unit,
    onOpenWebConsole: () -> Unit,
) {
    val context = LocalContext.current
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (state.isDaemonRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.isDaemonRunning) "CC-Switch is running" else "CC-Switch is not running",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusBadge(
                text = "Port ${state.servicePort}",
                color = if (state.isDaemonRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (state.isDaemonRunning) {
                "CC-Switch is listening on the local port and sandbox, providing reverse proxying, streaming protocol conversion, and unified model-provider switching for CLI tools."
            } else {
                "The hub is not running. Once started, it will inspect the sandbox and report installed CLI tools, latest versions, and model-provider status."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.isDaemonRunning) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Local proxy: http://127.0.0.1:${state.servicePort}${state.deviceLanIp?.let { " · LAN: http://$it:${state.servicePort}" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(10.dp))

            // Web Console凭据区域（完全扁平化，去除卡片套卡片）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Web console credentials (HTTP Basic authentication)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuntimeTextButton(
                        onClick = {
                            val creds = "${state.webUsername} / ${state.webPassword}"
                            copyText(context, creds, "Copied console username and password: $creds")
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                    RuntimeTextButton(
                        onClick = { onResetPassword("admin123") },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text("Reset password", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Username: ${state.webUsername}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Password: ${state.webPassword}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = "💡 If the external browser asks you to sign in, use the username and password above",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        if (state.isDaemonRunning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeFilledTonalButton(
                    onClick = onRestart,
                    enabled = !state.isOperating,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    RuntimeIcon(name = RuntimeIconName.Refresh, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Restart",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                RuntimeOutlinedButton(
                    onClick = onStop,
                    enabled = !state.isOperating,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    RuntimeIcon(name = RuntimeIconName.Close, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Stop",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                RuntimeButton(
                    onClick = {
                        val creds = "${state.webUsername} / ${state.webPassword}"
                        copyText(context, creds, "Login credentials copied: $creds")
                        onOpenWebConsole()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    RuntimeIcon(name = RuntimeIconName.OpenInNew, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Console",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        } else {
            RuntimeButton(
                onClick = onStart,
                enabled = !state.isOperating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isOperating) {
                    RuntimeCircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    RuntimeIcon(name = RuntimeIconName.Play, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Start hub service (port ${state.servicePort})")
            }
        }
    }
}

@Composable
private fun TokenUsageCard(daemon: CcSwitchDaemonStatus) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Today's proxy token usage",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${daemon.todayTokens.requestCount} requests",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Input tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${daemon.todayTokens.promptTokens}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("Output tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${daemon.todayTokens.completionTokens}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("Total tokens used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${daemon.todayTokens.totalTokens}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AgentAvatar(type: CcAgentType) {
    val (bg, fg, label) = when (type) {
        CcAgentType.CLAUDE_CODE -> Triple(Color(0xFFD97706).copy(alpha = 0.15f), Color(0xFFD97706), "CC")
        CcAgentType.CODEX -> Triple(Color(0xFF10B981).copy(alpha = 0.15f), Color(0xFF10B981), "CX")
        CcAgentType.GEMINI_CLI -> Triple(Color(0xFF3B82F6).copy(alpha = 0.15f), Color(0xFF3B82F6), "GM")
        CcAgentType.GROK_BUILD -> Triple(Color(0xFF8B5CF6).copy(alpha = 0.15f), Color(0xFF8B5CF6), "GK")
        CcAgentType.OPENCODE -> Triple(Color(0xFF06B6D4).copy(alpha = 0.15f), Color(0xFF06B6D4), "OC")
        CcAgentType.OPENCLAW -> Triple(Color(0xFFEC4899).copy(alpha = 0.15f), Color(0xFFEC4899), "CL")
        CcAgentType.HERMES -> Triple(Color(0xFFF97316).copy(alpha = 0.15f), Color(0xFFF97316), "HM")
        CcAgentType.PI -> Triple(Color(0xFF6366F1).copy(alpha = 0.15f), Color(0xFF6366F1), "PI")
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
            color = fg,
        )
    }
}

@Composable
private fun AgentCard(
    agent: CcAgentState,
    isDaemonRunning: Boolean,
    isOperating: Boolean,
    isInstalling: Boolean,
    onSwitchProvider: () -> Unit,
    onInstall: () -> Unit,
    onUpgrade: () -> Unit,
    onShowLogs: () -> Unit,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // 1. 顶部标题、头像与动作栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AgentAvatar(agent.type)

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = agent.type.displayName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = agent.type.category,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "$ ${agent.type.defaultExecutable}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右侧状态徽章与操作按钮（已完全去掉"一键切源"按钮）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    !isDaemonRunning -> {
                        StatusBadge(
                            text = "Not running",
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    agent.isChecking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RuntimeCircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Checking",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    isInstalling -> {
                        RuntimeOutlinedButton(
                            onClick = onShowLogs,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            RuntimeCircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Installing", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }
                    !agent.installed -> {
                        RuntimeFilledTonalButton(
                            onClick = onInstall,
                            enabled = !isOperating,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            RuntimeIcon(name = RuntimeIconName.Download, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Install", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }
                    agent.hasUpdate -> {
                        RuntimeButton(
                            onClick = onUpgrade,
                            enabled = !isOperating,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            RuntimeIcon(name = RuntimeIconName.ArrowUp, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Upgrade", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }
                    else -> {
                        StatusBadge(
                            text = "Ready",
                            color = Color(0xFF4CAF50),
                        )
                    }
                }
            }
        }

        // 2. 扁平精简信息区（彻底剔除卡片套卡片）
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 版本信息
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    text = "Version:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when {
                        !isDaemonRunning -> "--"
                        agent.isChecking -> "Checking..."
                        agent.installed -> agent.currentVersion ?: "Installed"
                        else -> "Not installed"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (agent.installed) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = when {
                        !isDaemonRunning -> MaterialTheme.colorScheme.outline
                        agent.installed -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.outline
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (agent.installed && agent.hasUpdate) {
                    Text(
                        text = "(Latest: ${agent.latestVersion ?: agent.type.defaultLatestVersion})",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Color(0xFFFF9800),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 绑定模型源（轻量点击项，可直接点击切换，取代突兀的一键切源按钮）
            if (isDaemonRunning) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isOperating && !isInstalling) { onSwitchProvider() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Model provider:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = agent.activeProviderName ?: if (agent.installed) "Default gateway" else "Not configured",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (agent.activeProviderName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RuntimeIcon(
                        name = RuntimeIconName.ChevronRight,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedSyncCard(
    taiXuModels: List<AiModelEntity>,
    onImportModel: (AiModelEntity) -> Unit,
) {
    if (taiXuModels.isEmpty()) return

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Integrated with the TaiXu model catalog",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            StatusBadge(
                text = "${taiXuModels.size} available providers",
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Tap to import a configured TaiXu model provider into CC-Switch without re-entering the API key or base URL:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        taiXuModels.take(4).forEachIndexed { index, model ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onImportModel(model) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${model.provider} · ${model.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RuntimeTextButton(
                    onClick = { onImportModel(model) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Import", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SwitchProviderDialog(
    agent: CcAgentState,
    providers: List<CcProviderProfile>,
    taiXuModels: List<AiModelEntity>,
    onDismiss: () -> Unit,
    onSelectProvider: (CcProviderProfile) -> Unit,
    onImportAndSelect: (AiModelEntity) -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch model provider for ${agent.type.displayName}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Configured CC-Switch providers:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (providers.isEmpty()) {
                    Text(
                        text = "No separate provider is configured. Select one from the TaiXu model catalog below to import and bind it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                providers.forEachIndexed { index, provider ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectProvider(provider) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("${provider.protocol} · ${provider.selectedModel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RuntimeTextButton(onClick = { onSelectProvider(provider) }) {
                            Text("Apply")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Bind a saved TaiXu model with one tap:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                taiXuModels.forEachIndexed { index, model ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onImportAndSelect(model) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("${model.provider} · ${model.model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RuntimeTextButton(onClick = { onImportAndSelect(model) }) {
                            Text("Sync & switch")
                        }
                    }
                }
            }
        },
        confirmButton = {
            RuntimeTextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun UpgradeVersionDialog(
    agent: CcAgentState,
    onDismiss: () -> Unit,
    onConfirmUpgrade: (String) -> Unit,
) {
    var versionInput by remember { mutableStateOf(agent.latestVersion ?: "") }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (agent.installed) "Upgrade ${agent.type.displayName}" else "Install ${agent.type.displayName}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoRow(label = "Current version", value = agent.currentVersion ?: "Not installed")
                InfoRow(label = "Recommended version", value = agent.latestVersion ?: agent.type.defaultLatestVersion.ifBlank { "latest" })

                OutlinedTextField(
                    value = versionInput,
                    onValueChange = { versionInput = it },
                    label = { Text("Target version (e.g. 1.0.0; leave blank for latest)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "After confirmation, the package manager will deploy and configure the selected version in the sandbox.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onConfirmUpgrade(versionInput.trim()) },
            ) {
                Text(if (agent.installed) "Confirm upgrade" else "Confirm install")
            }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun copyText(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("TaiXu", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

@Composable
private fun InstallLogDialog(
    agentName: String,
    logs: List<String>,
    isOperating: Boolean,
    isError: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Terminal, modifier = Modifier.size(18.dp))
                    Text(
                        text = "$agentName log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                when {
                    isOperating -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RuntimeCircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Running", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    isError -> {
                        StatusBadge(text = "Error/Failed", color = MaterialTheme.colorScheme.error)
                    }
                    logs.isNotEmpty() -> {
                        StatusBadge(text = "Completed", color = Color(0xFF4CAF50))
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    color = Color(0xFF161616),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (logs.isEmpty()) {
                            Text(
                                text = "No command output yet. Click Install or Upgrade to capture and display detailed build output from the sandbox in real time...",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF888888),
                                modifier = Modifier.padding(12.dp),
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                logs.forEach { line ->
                                    val textColor = when {
                                        line.contains("[-] ") || line.contains("ERR!") || line.contains("error:") || line.contains("FAILED") -> Color(0xFFEF5350)
                                        line.contains("[+] ") -> Color(0xFF81C784)
                                        line.contains("[!] ") || line.contains("WARN") -> Color(0xFFFFB74D)
                                        line.contains("[*] ") || line.contains("[Step ") -> Color(0xFF64B5F6)
                                        line.contains("[PATH] ") -> Color(0xFFBA68C8)
                                        else -> Color(0xFFDCDCDC)
                                    }
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall.copy(
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
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (logs.isNotEmpty()) {
                    RuntimeFilledTonalButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                    RuntimeButton(
                        onClick = { onCopy(logs.joinToString("\n")) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        RuntimeIcon(name = RuntimeIconName.Copy, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy all logs", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                } else {
                    RuntimeButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            }
        },
        dismissButton = {
            if (logs.isNotEmpty()) {
                RuntimeTextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
    )
}

