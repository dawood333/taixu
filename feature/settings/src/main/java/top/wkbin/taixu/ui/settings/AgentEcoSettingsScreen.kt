package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.ui.settings.LocalizedText as Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTopBar

/**
 * 二级子页 1：智能体与 AI 模型生态
 */
@Composable
fun AgentEcoSettingsScreen(
    onBack: () -> Unit,
    onOpenModelProfiles: () -> Unit,
    onOpenLocalLlm: () -> Unit,
    onOpenToolCenter: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenSubagentSettings: () -> Unit,
    onOpenSkillSettings: () -> Unit,
    onOpenMcpSettings: () -> Unit,
    onOpenQuickPhrases: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenPhoneAgent: () -> Unit,
    onOpenCcSwitch: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val skills by viewModel.allSkills.collectAsStateWithLifecycle()
    val phrases by viewModel.quickPhrases.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("Agents & Models", onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "Model Profiles & Providers",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Model,
                        title = "Manage model profiles",
                        subtitle = "Configure API keys and endpoints for OpenAI, DeepSeek, Claude, and local LLMs",
                        value = if (models.isEmpty()) "Not configured" else "${models.size} models",
                        onClick = onOpenModelProfiles,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Cpu,
                        title = "Local LLM",
                        subtitle = "Import or download GGUF models for offline inference with llama.cpp on ARM64 devices",
                        onClick = onOpenLocalLlm,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Chat,
                        title = "Quick phrases & commands",
                        subtitle = "Customize quick-start cards and frequently used prompt templates on the Agent Hub home screen",
                        value = "${phrases.count { it.isEnabled }} enabled",
                        onClick = onOpenQuickPhrases,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Speed,
                        title = "Usage analytics",
                        subtitle = "Token usage, activity heatmap, model rankings, and topic rankings",
                        onClick = onOpenStats,
                    )
                }
            }

            item {
                Text(
                    text = "Virtual screen",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    PhoneAgentSettingsEntry(onOpen = onOpenPhoneAgent)
                }
            }

            item {
                Text(
                    text = "Tools & Plugins",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Wrench,
                        title = "Plugin & System Tool Center",
                        subtitle = "Install llama.cpp, QEMU, and low-level development tools",
                        onClick = onOpenToolCenter,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Sparkles,
                        title = "Agent Hub (CC-Switch)",
                        subtitle = "Manage Claude Code, OpenClaw, and Hermes versions and switch between model providers",
                        onClick = onOpenCcSwitch,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(icon = RuntimeIconName.Bot, title = "Agent Execution & Context", subtitle = "Reasoning stream, context compaction, tool-call limits, and system prompts", onClick = onOpenAgentSettings)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(icon = RuntimeIconName.Bot, title = "Subagent Roles", subtitle = "Automatic delegation policies and available subagent roles", onClick = onOpenSubagentSettings)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(icon = RuntimeIconName.Sparkles, title = "Skills & Plugins", subtitle = "Manage skill prompts, script packages, and runtime plugins", value = "${skills.count { it.isEnabled }} skills", onClick = onOpenSkillSettings)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Network,
                        title = "MCP Protocols & Services",
                        subtitle = "Manage Model Context Protocol services such as SQLite, Git, and Fetch",
                        onClick = onOpenMcpSettings,
                    )
                }
            }
        }
    }
}
