package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.ui.settings.LocalizedText as Text
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.ui.components.IconTile
import top.wkbin.taixu.ui.components.MainDestination
import top.wkbin.taixu.ui.components.RuntimeBottomBar
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.liquidGlassContent
import top.wkbin.taixu.ui.theme.LocalLiquidGlassBackdrop

/**
 * TaiXu · Features配置 (TaiXu Settings & Models)
 */
@Composable
fun SettingsScreen(
    onNavigate: (MainDestination) -> Unit,
    onOpenAgentEco: () -> Unit,
    onOpenLinuxEnv: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSystemDev: () -> Unit,
    onOpenAboutCommunity: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenFeatureHub: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val developer by viewModel.developerMode.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val skills by viewModel.allSkills.collectAsStateWithLifecycle()
    val effectiveExecutionMode by viewModel.effectiveExecutionMode.collectAsStateWithLifecycle()
    val installedDistros by viewModel.installedDistros.collectAsStateWithLifecycle()
    val activeDistroId by viewModel.activeDistroId.collectAsStateWithLifecycle()
    val terminalFontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()

    val themeLabel = when (themeMode) {
        "light" -> "Light"
        "dark" -> "Dark"
        else -> "Follow system"
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val appVersionName = rememberAppVersion()

    val glassBackdrop = LocalLiquidGlassBackdrop.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "TaiXu · Features",
                statusText = "System Settings & Control Center",
            )
        },
        bottomBar = {
            if (glassBackdrop == null) {
                RuntimeBottomBar(MainDestination.Settings, onNavigate)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassContent()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 全局功能搜索快捷入口卡片
            item {
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenSearch,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Search,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Search features and settings...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.weight(1f),
                        )
                        RuntimeIcon(
                            name = RuntimeIconName.ChevronRight,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            item { LocalBackupSettingsCard() }
            item {
                Text(
                    text = "System & Configuration",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
                )
            }

            // 1. Agents & AI Models生态
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Brain,
                    accent = MaterialTheme.colorScheme.primary,
                    title = "Agents & AI Models",
                    subtitle = "Model profiles · Tool center · Skills and MCP",
                    badge = if (models.isEmpty()) "No model configured" else "${models.size} models · ${skills.count { it.isEnabled }} skills",
                    onClick = onOpenAgentEco,
                )
            }

            // 2. Linux 容器沙箱与存储
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Server,
                    accent = MaterialTheme.colorScheme.tertiary,
                    title = "Linux Containers & Storage",
                    subtitle = "Multiple distributions · host storage mapping · privileged execution",
                    badge = "${installedDistros.size} distributions · ${effectiveExecutionMode.shortLabel}",
                    onClick = onOpenLinuxEnv,
                )
            }

            // 3. Appearance, Text Size & Terminal
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Palette,
                    accent = MaterialTheme.colorScheme.secondary,
                    title = "Appearance, Text Size & Terminal",
                    subtitle = "深Light主题 · 应用字号缩放 · 终端配色与字体",
                    badge = "$themeLabel · ${terminalFontSize}sp",
                    onClick = onOpenAppearance,
                )
            }

            // 4. Background Persistence & Developer Diagnostics
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Admin,
                    accent = MaterialTheme.colorScheme.primary,
                    title = "Background Persistence & Developer Diagnostics",
                    subtitle = "Battery-optimization exemption · debugging monitor · PRoot console",
                    badge = if (developer) "Diagnostics enabled" else "Running normally",
                    onClick = onOpenSystemDev,
                )
            }

            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Sparkles,
                    accent = MaterialTheme.colorScheme.tertiary,
                    title = "Featured Features",
                    subtitle = "Build an app from one sentence · AI roundtable · Morning Sentinel · WebChat · A2UI",
                    badge = "5 项功能",
                    onClick = onOpenFeatureHub,
                )
            }

            // 5. 关于、更新与官方社区
            item {
                SettingsCategoryCard(
                    icon = RuntimeIconName.Community,
                    accent = MaterialTheme.colorScheme.secondary,
                    title = "关于、更新与官方社区",
                    subtitle = "检查新版本 · GitHub 开源仓库 · 官方 QQ 交流群",
                    badge = if (appVersionName == "unknown") "版本号未知 · 稳定版" else "v$appVersionName 稳定版",
                    onClick = onOpenAboutCommunity,
                )
            }
        }
    }
}

/**
 * 现代高质感大类导航卡片（紧凑精致）
 */
@Composable
private fun SettingsCategoryCard(
    icon: RuntimeIconName,
    accent: Color,
    title: String,
    subtitle: String,
    badge: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RuntimeCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconTile(
                icon = icon,
                size = 36.dp,
                color = accent,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                        color = accent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
                    )
                }
            }

            RuntimeIcon(
                name = RuntimeIconName.ChevronRight,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}
