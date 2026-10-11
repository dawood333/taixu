package top.wkbin.taixu.ui.settings

/**
 * 工具安装/更新失败时，辅助生成 AI Agent 沙箱诊断与自愈 Prompt 的工具类
 */
object ToolSelfHealingHelper {
    fun buildHealingPrompt(
        toolId: String,
        toolName: String,
        errorLogs: List<String>,
    ): String = buildString {
        appendLine("[System tool installation failure diagnosis and sandbox self-healing task]")
        appendLine("- Target tool: $toolName ($toolId)")
        appendLine("- Expected installation path: /opt/taixu/tools/$toolId")
        appendLine("- Relevant specialist capability: @Linux Sandbox Operations Specialist")
        if (errorLogs.isNotEmpty()) {
            appendLine("- Failure context and log output:")
            appendLine("```")
            errorLogs.takeLast(25).forEach { appendLine(it) }
            appendLine("```")
        }
        appendLine()
        appendLine("[Agent self-healing objectives and action guide]:")
        appendLine("1. Analyze the PRoot sandbox error above (for example, broken dpkg dependencies, stale locks, blocked downloads, missing commandLinks symlinks, or missing environment components).")
        appendLine("2. Use the base tool to run targeted repair commands (for example, clear stale locks under /var/lib/dpkg, run dpkg --configure -a or apt-get --fix-broken install, download from a fallback source, or restore missing symlinks).")
        appendLine("3. After repair, verify the tool in the terminal (for example, run /opt/taixu/bin/$toolId or the corresponding binary) and report the diagnosis and recovery results in this session.")
    }
}
