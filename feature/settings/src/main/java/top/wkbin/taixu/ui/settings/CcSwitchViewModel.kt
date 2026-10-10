package top.wkbin.taixu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.ToolSettingsRepository
import top.wkbin.taixu.core.model.CcAgentState
import top.wkbin.taixu.core.model.CcAgentType
import top.wkbin.taixu.core.model.CcProviderProfile
import top.wkbin.taixu.core.model.CcSwitchDaemonStatus
import top.wkbin.taixu.core.network.CcSwitchClient
import top.wkbin.taixu.core.model.RuntimeName
import top.wkbin.taixu.core.model.RuntimeRequirement
import top.wkbin.taixu.core.tools.DependencyManager
import top.wkbin.taixu.core.tools.ProviderRepository
import top.wkbin.taixu.core.tools.ToolManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.update
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.net.Inet4Address
import java.net.NetworkInterface
import top.wkbin.taixu.core.common.result.AppResult

data class CcSwitchUiState(
    val isInstalled: Boolean = true,
    val isDaemonRunning: Boolean = false,
    val isOperating: Boolean = false,
    val isCheckingEnvironment: Boolean = false,
    val installingAgentType: CcAgentType? = null,
    val autoStartEnabled: Boolean = false,
    val servicePort: Int = 19870,
    val daemonStatus: CcSwitchDaemonStatus? = null,
    val agents: List<CcAgentState> = defaultAgents(),
    val providers: List<CcProviderProfile> = emptyList(),
    val taiXuModels: List<AiModelEntity> = emptyList(),
    val deviceLanIp: String? = null,
    val webUsername: String = "admin",
    val webPassword: String = "admin123",
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val switchingAgent: CcAgentState? = null,
    val upgradingAgent: CcAgentState? = null,
    val installLogs: List<String> = emptyList(),
    val showLogDialog: Boolean = false,
    val logAgentTitle: String = "Installation log",
) {
    val loopbackUrl: String get() = "http://127.0.0.1:$servicePort"
    val lanUrl: String? get() = deviceLanIp?.let { "http://$it:$servicePort" }
    val preferredWebUrl: String get() = lanUrl ?: loopbackUrl

    companion object {
        fun defaultAgents(): List<CcAgentState> = CcAgentType.entries.map { type ->
            CcAgentState(
                type = type,
                installed = false,
                currentVersion = null,
                latestVersion = null,
                activeProviderName = null,
                isChecking = false,
            )
        }
    }
}

class CcSwitchViewModel(
    private val toolManager: ToolManager,
    private val ccSwitchClient: CcSwitchClient,
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ProviderRepository,
    private val toolSettingsRepository: ToolSettingsRepository,
    private val linuxRuntime: LinuxRuntime,
    private val dependencyManager: DependencyManager,
    private val logger: AppLogger,
) : ViewModel() {

    private val toolId = "cc-switch"

    private val _isOperating = MutableStateFlow(false)
    private val _isDaemonRunning = MutableStateFlow(false)
    private val _isCheckingEnvironment = MutableStateFlow(false)
    private val _installingAgentType = MutableStateFlow<CcAgentType?>(null)
    private val _daemonStatus = MutableStateFlow<CcSwitchDaemonStatus?>(null)
    private val _agents = MutableStateFlow<List<CcAgentState>>(CcSwitchUiState.defaultAgents())
    private val _providers = MutableStateFlow<List<CcProviderProfile>>(emptyList())
    private val _deviceLanIp = MutableStateFlow<String?>(null)
    private val _webUsername = MutableStateFlow("admin")
    private val _webPassword = MutableStateFlow("admin123")
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _successMessage = MutableStateFlow<String?>(null)
    private val _switchingAgent = MutableStateFlow<CcAgentState?>(null)
    private val _upgradingAgent = MutableStateFlow<CcAgentState?>(null)
    private val _installLogs = MutableStateFlow<List<String>>(emptyList())
    private val _showLogDialog = MutableStateFlow(false)
    private val _logAgentTitle = MutableStateFlow("Agent Installation log")

    val uiState: StateFlow<CcSwitchUiState> = combine(
        linuxRuntime.activeDistroId,
        _isOperating,
        _isDaemonRunning,
        _isCheckingEnvironment,
        _installingAgentType,
        _daemonStatus,
        _agents,
        _providers,
        aiModelDao.observeAll(),
        _deviceLanIp,
        _webUsername,
        _webPassword,
        _errorMessage,
        _successMessage,
        _switchingAgent,
        _upgradingAgent,
        _installLogs,
        _showLogDialog,
        _logAgentTitle,
    ) { values ->
        val distroId = values[0] as String
        val operating = values[1] as Boolean
        val daemonRunning = values[2] as Boolean
        val checkingEnv = values[3] as Boolean
        val installingAgent = values[4] as CcAgentType?
        val daemonStatus = values[5] as CcSwitchDaemonStatus?
        @Suppress("UNCHECKED_CAST")
        val agents = values[6] as List<CcAgentState>
        @Suppress("UNCHECKED_CAST")
        val providers = values[7] as List<CcProviderProfile>
        @Suppress("UNCHECKED_CAST")
        val models = values[8] as List<AiModelEntity>
        val lanIp = values[9] as String?
        val username = values[10] as String
        val password = values[11] as String
        val err = values[12] as String?
        val success = values[13] as String?
        val switching = values[14] as CcAgentState?
        val upgrading = values[15] as CcAgentState?
        @Suppress("UNCHECKED_CAST")
        val logs = values[16] as List<String>
        val showDialog = values[17] as Boolean
        val logTitle = values[18] as String

        CcSwitchUiState(
            isInstalled = true,
            isDaemonRunning = daemonRunning,
            isOperating = operating,
            isCheckingEnvironment = checkingEnv,
            installingAgentType = installingAgent,
            autoStartEnabled = false,
            daemonStatus = daemonStatus,
            agents = agents,
            providers = providers,
            taiXuModels = models,
            deviceLanIp = lanIp,
            webUsername = username,
            webPassword = password,
            errorMessage = err,
            successMessage = success,
            switchingAgent = switching,
            upgradingAgent = upgrading,
            installLogs = logs,
            showLogDialog = showDialog,
            logAgentTitle = logTitle,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CcSwitchUiState())

    init {
        refreshStatus()
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(3500)
                try {
                    val running = toolManager.isGatewayRunning(toolId)
                    val lanIp = detectLanIp()
                    val wasRunning = _isDaemonRunning.value
                    withContext(Dispatchers.Main.immediate) {
                        _isDaemonRunning.value = running
                        _deviceLanIp.value = lanIp
                        if (!running && wasRunning) {
                            _daemonStatus.value = null
                        }
                    }
                    if (running && !wasRunning) {
                        fetchDaemonData()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val lanIp = detectLanIp()
            val running = toolManager.isGatewayRunning(toolId)
            withContext(Dispatchers.Main.immediate) {
                _isDaemonRunning.value = running
                _deviceLanIp.value = lanIp
                if (!running) {
                    _daemonStatus.value = null
                }
            }
            fetchDaemonData()
        }
    }

    fun refreshEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            fetchDaemonData()
        }
    }

    private suspend fun fetchDaemonData() {
        withContext(Dispatchers.Main.immediate) {
            _isCheckingEnvironment.value = true
            _agents.value = _agents.value.map { it.copy(isChecking = true) }
        }
        try {
            val isRunning = toolManager.isGatewayRunning(toolId)
            val statusRes = if (isRunning) ccSwitchClient.getStatus() else Result.failure(IllegalStateException("Daemon not running"))
            val agentsRes = if (isRunning) ccSwitchClient.getAgents() else Result.failure(IllegalStateException("Daemon not running"))
            val providersRes = if (isRunning) ccSwitchClient.getProviders() else Result.failure(IllegalStateException("Daemon not running"))
            val sandboxMap = inspectSandboxAgents()

            val mergedAgents = CcAgentType.entries.map { type ->
                val fromApi = agentsRes.getOrNull()?.firstOrNull { it.type == type || it.type.id.equals(type.id, ignoreCase = true) }
                val sandboxVer = sandboxMap[type.defaultExecutable.lowercase()]
                val isInstalled = sandboxVer != null || (fromApi?.installed == true)
                val currentVer = sandboxVer ?: fromApi?.currentVersion
                val latestVer = fromApi?.latestVersion?.takeIf { it.isNotBlank() } ?: type.defaultLatestVersion
                val providerName = fromApi?.activeProviderName
                val providerId = fromApi?.activeProviderId

                CcAgentState(
                    type = type,
                    installed = isInstalled,
                    currentVersion = currentVer,
                    latestVersion = latestVer,
                    activeProviderId = providerId,
                    activeProviderName = providerName,
                    isChecking = false,
                    errorNotice = if (!isInstalled) "not installed or not executable" else null,
                    description = fromApi?.description ?: type.displayName,
                )
            }

            val creds = readWebCredentials()
            withContext(Dispatchers.Main.immediate) {
                if (isRunning) {
                    statusRes.onSuccess { _daemonStatus.value = it }
                    providersRes.onSuccess { _providers.value = it }
                } else {
                    _daemonStatus.value = null
                }
                _agents.value = mergedAgents
                _webUsername.value = creds.first
                _webPassword.value = creds.second
            }
        } finally {
            withContext(Dispatchers.Main.immediate) {
                _isCheckingEnvironment.value = false
                _agents.value = _agents.value.map { it.copy(isChecking = false) }
            }
        }
    }

    suspend fun readWebCredentials(): Pair<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val cmd = """
                    USER_FILE="${'$'}{HOME:-/root}/.cc-switch/web_username"
                    PASS_FILE="${'$'}{HOME:-/root}/.cc-switch/web_password"
                    if [ ! -s "${'$'}USER_FILE" ]; then
                        mkdir -p "${'$'}{HOME:-/root}/.cc-switch" 2>/dev/null || true
                        printf 'admin' > "${'$'}USER_FILE" 2>/dev/null || true
                    fi
                    if [ ! -s "${'$'}PASS_FILE" ]; then
                        mkdir -p "${'$'}{HOME:-/root}/.cc-switch" 2>/dev/null || true
                        printf 'admin123' > "${'$'}PASS_FILE" 2>/dev/null || true
                    fi
                    chmod 600 "${'$'}USER_FILE" "${'$'}PASS_FILE" 2>/dev/null || true
                    cat "${'$'}USER_FILE" 2>/dev/null || echo "admin"
                    echo "---TAIXU_SPLIT---"
                    cat "${'$'}PASS_FILE" 2>/dev/null || echo "admin123"
                """.trimIndent()
                val res = linuxRuntime.execute(ShellCommand(commandLine = cmd, timeoutMs = 5000L))
                if (res.isSuccess) {
                    val parts = res.stdout.split("---TAIXU_SPLIT---")
                    val username = parts.getOrNull(0)?.trim().orEmpty().ifBlank { "admin" }
                    val password = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "admin123" }
                    Pair(username, password)
                } else {
                    Pair("admin", "admin123")
                }
            } catch (_: Exception) {
                Pair("admin", "admin123")
            }
        }
    }

    fun resetWebPassword(newPassword: String = "admin123") {
        viewModelScope.launch(Dispatchers.IO) {
            val cmd = """
                PASS_FILE="${'$'}{HOME:-/root}/.cc-switch/web_password"
                mkdir -p "${'$'}{HOME:-/root}/.cc-switch" 2>/dev/null || true
                printf '%s' "$newPassword" > "${'$'}PASS_FILE" 2>/dev/null || true
                chmod 600 "${'$'}PASS_FILE" 2>/dev/null || true
            """.trimIndent()
            linuxRuntime.execute(ShellCommand(commandLine = cmd, timeoutMs = 5000L))
            _webPassword.value = newPassword
            withContext(Dispatchers.Main.immediate) {
                _successMessage.value = "Web password reset to $newPassword (takes effect after restarting the hub)"
            }
        }
    }

    private suspend fun inspectSandboxAgents(): Map<String, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val checkScript = """
                    export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"

                    find_and_shim_node_agent() {
                        cmd="${'$'}1"
                        pkg_name="${'$'}2"

                        pkg_dir=""
                        NPM_PREFIX="${'$'}(npm config get prefix 2>/dev/null || true)"
                        for nmdir in \
                            /opt/taixu/runtimes/node/*/lib/node_modules \
                            "${'$'}NPM_PREFIX/lib/node_modules" \
                            /usr/local/lib/node_modules \
                            /usr/lib/node_modules \
                            /root/.local/lib/node_modules; do
                            if [ -d "${'$'}nmdir/${'$'}pkg_name" ]; then
                                pkg_dir="${'$'}nmdir/${'$'}pkg_name"
                                break
                            fi
                        done

                        ver=""
                        entry_file=""
                        if [ -n "${'$'}pkg_dir" ] && [ -f "${'$'}pkg_dir/package.json" ]; then
                            ver="${'$'}(grep -m 1 '"version"' "${'$'}pkg_dir/package.json" 2>/dev/null | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"

                            for candidate in \
                                "${'$'}pkg_dir/cli.js" \
                                "${'$'}pkg_dir/bin/${'$'}cmd" \
                                "${'$'}pkg_dir/bin/${'$'}cmd.js" \
                                "${'$'}pkg_dir/dist/cli.js" \
                                "${'$'}pkg_dir/index.js"; do
                                if [ -f "${'$'}candidate" ]; then
                                    entry_file="${'$'}candidate"
                                    break
                                fi
                            done

                            if [ -z "${'$'}entry_file" ]; then
                                bin_rel="${'$'}(grep -A 5 '"bin"' "${'$'}pkg_dir/package.json" 2>/dev/null | grep -oE '"[^"]+\.js"' | tr -d '"' | head -n 1)"
                                if [ -n "${'$'}bin_rel" ] && [ -f "${'$'}pkg_dir/${'$'}bin_rel" ]; then
                                    entry_file="${'$'}pkg_dir/${'$'}bin_rel"
                                fi
                            fi
                        fi

                        if [ -n "${'$'}entry_file" ]; then
                            mkdir -p /opt/taixu/bin /usr/local/bin 2>/dev/null || true
                            rm -f "/opt/taixu/bin/${'$'}cmd" "/usr/local/bin/${'$'}cmd" 2>/dev/null || true
                            cat << 'EOF' > "/opt/taixu/bin/${'$'}cmd"
#!/bin/sh
export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/opt/taixu/bin/${'$'}cmd"
                            chmod 755 "/opt/taixu/bin/${'$'}cmd" 2>/dev/null || true

                            cat << 'EOF' > "/usr/local/bin/${'$'}cmd"
#!/bin/sh
export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/usr/local/bin/${'$'}cmd"
                            chmod 755 "/usr/local/bin/${'$'}cmd" 2>/dev/null || true
                        fi

                        if [ -z "${'$'}ver" ]; then
                            for candidate in \
                                /opt/taixu/runtimes/node/*/bin/"${'$'}cmd" \
                                /opt/taixu/tools/*/bin/"${'$'}cmd" \
                                /root/.local/bin/"${'$'}cmd" \
                                /usr/bin/"${'$'}cmd"; do
                                if [ -f "${'$'}candidate" ] && [ "${'$'}candidate" != "/opt/taixu/bin/${'$'}cmd" ] && [ "${'$'}candidate" != "/usr/local/bin/${'$'}cmd" ]; then
                                    if ! grep -q "exec .*/${'$'}cmd" "${'$'}candidate" 2>/dev/null; then
                                        ver="${'$'}("${'$'}candidate" --version 2>/dev/null | head -n 1 | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"
                                        if [ -n "${'$'}ver" ]; then
                                            mkdir -p /opt/taixu/bin 2>/dev/null || true
                                            rm -f "/opt/taixu/bin/${'$'}cmd" 2>/dev/null || true
                                            cat << 'EOF' > "/opt/taixu/bin/${'$'}cmd"
#!/bin/sh
export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                                            printf 'exec "%s" "$@"\n' "${'$'}candidate" >> "/opt/taixu/bin/${'$'}cmd"
                                            chmod 755 "/opt/taixu/bin/${'$'}cmd" 2>/dev/null || true
                                            break
                                        fi
                                    fi
                                fi
                            done
                        fi

                        if [ -n "${'$'}ver" ]; then
                            echo "${'$'}cmd:${'$'}ver"
                        elif [ -n "${'$'}entry_file" ]; then
                            echo "${'$'}cmd:installed"
                        else
                            echo "${'$'}cmd:none"
                        fi
                    }

                    find_and_shim_python_agent() {
                        cmd="${'$'}1"
                        pkg_name="${'$'}2"

                        real_bin=""
                        for candidate in \
                            /opt/taixu/runtimes/python/*/bin/"${'$'}cmd" \
                            /root/.local/bin/"${'$'}cmd" \
                            /usr/local/bin/"${'$'}cmd" \
                            /usr/bin/"${'$'}cmd"; do
                            if [ -f "${'$'}candidate" ] && [ "${'$'}candidate" != "/opt/taixu/bin/${'$'}cmd" ]; then
                                if ! grep -q "exec .*/${'$'}cmd" "${'$'}candidate" 2>/dev/null; then
                                    real_bin="${'$'}candidate"
                                    break
                                fi
                            fi
                        done

                        ver=""
                        if [ -n "${'$'}real_bin" ]; then
                            mkdir -p /opt/taixu/bin 2>/dev/null || true
                            rm -f "/opt/taixu/bin/${'$'}cmd" 2>/dev/null || true
                            cat << 'EOF' > "/opt/taixu/bin/${'$'}cmd"
#!/bin/sh
export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec "%s" "$@"\n' "${'$'}real_bin" >> "/opt/taixu/bin/${'$'}cmd"
                            chmod 755 "/opt/taixu/bin/${'$'}cmd" 2>/dev/null || true

                            raw="${'$'}("${'$'}real_bin" --version 2>/dev/null || "${'$'}real_bin" -v 2>/dev/null || true)"
                            ver="${'$'}(echo "${'$'}raw" | head -n 1 | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"
                        fi

                        if [ -n "${'$'}ver" ]; then
                            echo "${'$'}cmd:${'$'}ver"
                        elif [ -n "${'$'}real_bin" ]; then
                            echo "${'$'}cmd:installed"
                        else
                            echo "${'$'}cmd:none"
                        fi
                    }

                    find_and_shim_node_agent "claude" "@anthropic-ai/claude-code"
                    find_and_shim_node_agent "codex" "@openai/codex"
                    find_and_shim_node_agent "gemini" "@google/gemini-cli"
                    find_and_shim_node_agent "grok" "grok-build"
                    find_and_shim_node_agent "opencode" "opencode-ai"
                    find_and_shim_node_agent "openclaw" "openclaw"
                    find_and_shim_python_agent "hermes" "hermes-agent"
                    find_and_shim_node_agent "pi" "pi-agent"
                """.trimIndent()
                val res = linuxRuntime.execute(ShellCommand(commandLine = checkScript, timeoutMs = 15000L))
                if (!res.isSuccess) return@withContext emptyMap()
                val map = mutableMapOf<String, String?>()
                res.stdout.lines().forEach { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().lowercase()
                        val value = parts[1].trim()
                        map[key] = if (value == "none" || value.isBlank()) null else value
                    }
                }
                map
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    fun startDaemon() {
        _isOperating.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.startGateway(toolId)
                delay(1000)
                val running = toolManager.isGatewayRunning(toolId)
                withContext(Dispatchers.Main.immediate) {
                    _isDaemonRunning.value = running
                    if (running) {
                        _successMessage.value = "CC-Switch daemon started on port 19870"
                    } else {
                        val lastLog = toolManager.getServiceLogs(toolId).takeLast(10).joinToString("\n").trim()
                        _errorMessage.value = if (lastLog.isNotBlank()) "The hub did not stay running after startup. Latest log:\n$lastLog" else "Hub startup failed; the port is not listening"
                    }
                }
                if (running) {
                    fetchDaemonData()
                }
            } catch (e: Exception) {
                logger.w("Failed to start cc-switch daemon: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "Failed to start the hub daemon: ${e.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun stopDaemon() {
        _isOperating.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.stopGateway(toolId)
                delay(500)
                val running = toolManager.isGatewayRunning(toolId)
                withContext(Dispatchers.Main.immediate) {
                    _isDaemonRunning.value = running
                    if (!running) {
                        _daemonStatus.value = null
                        _successMessage.value = "CC-Switch daemon stopped"
                    } else {
                        _errorMessage.value = "The daemon could not be stopped; the port may still be in use"
                    }
                }
            } catch (e: Exception) {
                logger.w("Failed to stop cc-switch daemon: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "Failed to stop the hub daemon: ${e.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun restartDaemon() {
        _isOperating.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.restartGateway(toolId)
                delay(1000)
                val running = toolManager.isGatewayRunning(toolId)
                withContext(Dispatchers.Main.immediate) {
                    _isDaemonRunning.value = running
                    if (running) {
                        _successMessage.value = "CC-Switch daemon restarted"
                    } else {
                        val lastLog = toolManager.getServiceLogs(toolId).takeLast(10).joinToString("\n").trim()
                        _errorMessage.value = if (lastLog.isNotBlank()) "The hub did not stay running after restart. Latest log:\n$lastLog" else "Hub restart failed; the port is not listening"
                    }
                }
                if (running) {
                    fetchDaemonData()
                }
            } catch (e: Exception) {
                logger.w("Failed to restart cc-switch daemon: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "Restart failed: ${e.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun openSwitchProviderDialog(agent: CcAgentState) {
        _switchingAgent.value = agent
    }

    fun openUpgradeDialog(agent: CcAgentState) {
        _upgradingAgent.value = agent
    }

    fun dismissDialogs() {
        _switchingAgent.value = null
        _upgradingAgent.value = null
    }

    fun dismissMessage() {
        _errorMessage.value = null
        _successMessage.value = null
    }

    fun switchAgentProvider(agent: CcAgentState, provider: CcProviderProfile) {
        viewModelScope.launch {
            _switchingAgent.value = null
            _isOperating.value = true
            try {
                val res = ccSwitchClient.switchAgentProvider(agent.type.id, provider.id)
                if (res.isSuccess) {
                    // Update local state optimistic
                    _agents.value = _agents.value.map {
                        if (it.type == agent.type) {
                            it.copy(
                                activeProviderId = provider.id,
                                activeProviderName = provider.name,
                                activeModelName = provider.selectedModel,
                            )
                        } else it
                    }
                    _successMessage.value = "Switched ${agent.type.displayName} to ${provider.name}"
                } else {
                    _errorMessage.value = "Failed to switch provider: ${res.exceptionOrNull()?.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun importTaiXuModel(model: AiModelEntity) {
        viewModelScope.launch {
            _isOperating.value = true
            try {
                val apiKey = if (model.secretRef.isNotBlank()) {
                    providerRepository.readModelApiKey(model.secretRef).orEmpty()
                } else ""
                val profile = CcProviderProfile(
                    id = model.id,
                    name = model.name,
                    protocol = if (model.provider.contains("anthropic", ignoreCase = true)) "ANTHROPIC" else "OPENAI",
                    baseUrl = model.baseUrl,
                    apiKey = apiKey,
                    maskedApiKey = if (apiKey.length > 8) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "***",
                    selectedModel = model.model,
                    availableModels = listOf(model.model),
                    isCustom = true,
                )
                val saveRes = ccSwitchClient.saveProvider(profile)
                if (saveRes.isSuccess) {
                    _providers.value = _providers.value.filterNot { it.id == profile.id } + profile
                    _successMessage.value = "Synced from the TaiXu model catalog: ${model.name}"
                } else {
                    _errorMessage.value = "Failed to sync to CC-Switch: ${saveRes.exceptionOrNull()?.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun showInstallLogs() {
        _showLogDialog.value = true
    }

    fun dismissInstallLogs() {
        _showLogDialog.value = false
    }

    fun clearInstallLogs() {
        _installLogs.value = emptyList()
    }

    fun installOrUpgradeAgent(agent: CcAgentState, targetVersion: String? = null) {
        _upgradingAgent.value = null
        _isOperating.value = true
        _installingAgentType.value = agent.type
        _errorMessage.value = null
        _successMessage.value = null
        _logAgentTitle.value = "Install and deploy ${agent.type.displayName}"
        _showLogDialog.value = true

        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        fun log(msg: String) {
            val now = timeFormatter.format(Date())
            msg.lines().filter { it.isNotBlank() }.forEach { line ->
                _installLogs.update { (it + "[$now] $line").takeLast(1000) }
            }
        }

        _installLogs.value = listOf("[${timeFormatter.format(Date())}] [*] Preparing to install/update ${agent.type.displayName} (target version: ${targetVersion ?: "latest"})...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 确保沙箱运行时依赖 (Node.js / Python)
                val isNodeTool = agent.type in listOf(
                    CcAgentType.CLAUDE_CODE,
                    CcAgentType.CODEX,
                    CcAgentType.GEMINI_CLI,
                    CcAgentType.OPENCODE,
                    CcAgentType.OPENCLAW,
                    CcAgentType.GROK_BUILD,
                    CcAgentType.PI,
                )

                if (isNodeTool) {
                    log("[*] [Step 1/3] Checking the Node.js / npm runtime in the sandbox...")
                    val hasNode = linuxRuntime.execute(
                        ShellCommand("command -v npm >/dev/null 2>&1 || [ -x /opt/taixu/bin/npm ] || [ -x /usr/local/bin/npm ]")
                    ).isSuccess
                    if (!hasNode) {
                        log("[!] No usable npm found in the sandbox. Resolving it with the TaiXu dependency manager...")
                        val depRes = dependencyManager.acquire(
                            RuntimeRequirement(RuntimeName.NODE),
                            "cc-switch",
                        )
                        if (depRes is AppResult.Failure) {
                            log("[!] No bundled package matched; falling back to apt-get to install nodejs and npm (please wait)...")
                            val aptRes = linuxRuntime.execute(
                                ShellCommand(
                                    commandLine = "apt-get update -y && apt-get install -y nodejs npm curl",
                                    environment = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
                                    timeoutMs = 120_000L,
                                    onOutput = { log(it) },
                                ),
                            )
                            if (!aptRes.isSuccess) {
                                log("[-] apt-get failed to install nodejs (exit code: ${aptRes.exitCode})")
                            }
                        } else {
                            log("[+] Node.js runtime dependencies loaded")
                        }
                    } else {
                        log("[+] Node.js / npm runtime is ready")
                    }
                } else if (agent.type == CcAgentType.HERMES) {
                    log("[*] [Step 1/3] Checking the Python 3 runtime in the sandbox...")
                    val hasPython = linuxRuntime.execute(ShellCommand("command -v python3 >/dev/null 2>&1")).isSuccess
                    if (!hasPython) {
                        log("[!] Loading Python runtime dependencies...")
                        dependencyManager.acquire(
                            RuntimeRequirement(RuntimeName.PYTHON),
                            "cc-switch",
                        )
                    } else {
                        log("[+] Python 3 runtime is ready")
                    }
                }

                // 2. 构建精准安装指令
                val npmPackage = when (agent.type) {
                    CcAgentType.CLAUDE_CODE -> "@anthropic-ai/claude-code"
                    CcAgentType.CODEX -> "@openai/codex"
                    CcAgentType.GEMINI_CLI -> "@google/gemini-cli"
                    CcAgentType.OPENCODE -> "opencode-ai"
                    CcAgentType.OPENCLAW -> "openclaw"
                    CcAgentType.HERMES -> "hermes-agent"
                    CcAgentType.GROK_BUILD -> "grok-build"
                    CcAgentType.PI -> "pi-agent"
                }

                val installScript = if (agent.type == CcAgentType.HERMES) {
                    val pkg = if (targetVersion.isNullOrBlank()) "hermes-agent" else "hermes-agent==$targetVersion"
                    log("[*] [Step 2/3] Installing with pip: $pkg...")
                    """
                    export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
                    pip install --break-system-packages $pkg -i https://pypi.tuna.tsinghua.edu.cn/simple || pip install --break-system-packages $pkg
                    real_bin=""
                    for candidate in \
                        /opt/taixu/runtimes/python/*/bin/hermes \
                        /root/.local/bin/hermes \
                        /usr/local/bin/hermes \
                        /usr/bin/hermes; do
                        if [ -f "${'$'}candidate" ] && [ "${'$'}candidate" != "/opt/taixu/bin/hermes" ]; then
                            if ! grep -q "exec .*/hermes" "${'$'}candidate" 2>/dev/null; then
                                real_bin="${'$'}candidate"
                                break
                            fi
                        fi
                    done
                    if [ -n "${'$'}real_bin" ]; then
                        mkdir -p /opt/taixu/bin 2>/dev/null || true
                        rm -f "/opt/taixu/bin/hermes" 2>/dev/null || true
                        cat << 'EOF' > "/opt/taixu/bin/hermes"
#!/bin/sh
export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                        printf 'exec "%s" "${'$'}@"\n' "${'$'}real_bin" >> "/opt/taixu/bin/hermes"
                        chmod 755 "/opt/taixu/bin/hermes" 2>/dev/null || true
                        echo "[+] Created executable wrapper: /opt/taixu/bin/hermes -> ${'$'}real_bin"
                    fi
                    """.trimIndent()
                } else {
                    val verSuffix = if (targetVersion.isNullOrBlank()) "" else "@$targetVersion"
                    log("[*] [Step 2/3] Installing npm package globally: $npmPackage$verSuffix...")
                    log("[*] Downloading and building from the configured mirror (https://registry.npmmirror.com)...")
                    """
                    export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
                    NPM_PREFIX="${'$'}(npm config get prefix 2>/dev/null || true)"
                    if [ -n "${'$'}NPM_PREFIX" ] && [ -d "${'$'}NPM_PREFIX/bin" ]; then
                        export PATH="${'$'}NPM_PREFIX/bin:${'$'}PATH"
                    fi
                    for np in /opt/taixu/runtimes/node/*/bin; do
                        if [ -d "${'$'}np" ]; then
                            export PATH="${'$'}np:${'$'}PATH"
                        fi
                    done

                    # 执行 npm 全局安装 (允许执行 lifecycle 脚本下载原生组件)
                    npm install -g --foreground-scripts $npmPackage$verSuffix --registry=https://registry.npmmirror.com || npm install -g --foreground-scripts $npmPackage$verSuffix

                    pkg_dir=""
                    for nmdir in \
                        /opt/taixu/runtimes/node/*/lib/node_modules \
                        "${'$'}NPM_PREFIX/lib/node_modules" \
                        /usr/local/lib/node_modules \
                        /usr/lib/node_modules \
                        /root/.local/lib/node_modules; do
                        if [ -d "${'$'}nmdir/$npmPackage" ]; then
                            pkg_dir="${'$'}nmdir/$npmPackage"
                            break
                        fi
                    done

                    entry_file=""
                    if [ -n "${'$'}pkg_dir" ]; then
                        for candidate in \
                            "${'$'}pkg_dir/cli.js" \
                            "${'$'}pkg_dir/bin/${agent.type.defaultExecutable}" \
                            "${'$'}pkg_dir/bin/${agent.type.defaultExecutable}.js" \
                            "${'$'}pkg_dir/dist/cli.js" \
                            "${'$'}pkg_dir/index.js"; do
                            if [ -f "${'$'}candidate" ]; then
                                entry_file="${'$'}candidate"
                                break
                            fi
                        done
                        if [ -z "${'$'}entry_file" ] && [ -f "${'$'}pkg_dir/package.json" ]; then
                            bin_rel="${'$'}(grep -A 5 '"bin"' "${'$'}pkg_dir/package.json" 2>/dev/null | grep -oE '"[^"]+\.js"' | tr -d '"' | head -n 1)"
                            if [ -n "${'$'}bin_rel" ] && [ -f "${'$'}pkg_dir/${'$'}bin_rel" ]; then
                                entry_file="${'$'}pkg_dir/${'$'}bin_rel"
                            fi
                        fi
                    fi

                    if [ -n "${'$'}entry_file" ]; then
                        mkdir -p /opt/taixu/bin 2>/dev/null || true
                        rm -f "/opt/taixu/bin/${agent.type.defaultExecutable}" "/usr/local/bin/${agent.type.defaultExecutable}" 2>/dev/null || true
                        cat << 'EOF' > "/opt/taixu/bin/${agent.type.defaultExecutable}"
#!/bin/sh
export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                        printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/opt/taixu/bin/${agent.type.defaultExecutable}"
                        chmod 755 "/opt/taixu/bin/${agent.type.defaultExecutable}" 2>/dev/null || true

                        cat << 'EOF' > "/usr/local/bin/${agent.type.defaultExecutable}"
#!/bin/sh
export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                        printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/usr/local/bin/${agent.type.defaultExecutable}"
                        chmod 755 "/usr/local/bin/${agent.type.defaultExecutable}" 2>/dev/null || true
                        echo "[+] Verified main executable wrapper: /opt/taixu/bin/${agent.type.defaultExecutable} -> ${'$'}entry_file"
                    fi
                    """.trimIndent()
                }

                val cmdRes = linuxRuntime.execute(
                    ShellCommand(
                        commandLine = installScript,
                        environment = mapOf(
                            "DEBIAN_FRONTEND" to "noninteractive",
                            "npm_config_registry" to "https://registry.npmmirror.com",
                        ),
                        timeoutMs = 180_000L,
                        onOutput = { log(it) },
                    ),
                )

                // 3. 严格在沙箱内校验可执行文件是否生成
                log("[*] [Step 3/3] Verifying the executable path and version in the sandbox...")
                val verifyScript = """
                    export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
                    NPM_PREFIX="${'$'}(npm config get prefix 2>/dev/null || true)"
                    if [ -n "${'$'}NPM_PREFIX" ] && [ -d "${'$'}NPM_PREFIX/bin" ]; then
                        export PATH="${'$'}NPM_PREFIX/bin:${'$'}PATH"
                    fi
                    for np in /opt/taixu/runtimes/node/*/bin; do
                        if [ -d "${'$'}np" ]; then
                            export PATH="${'$'}np:${'$'}PATH"
                        fi
                    done

                    # 如果包装器不存在或存在循环 exec 坏引用，自动自愈重建
                    if [ ! -s "/opt/taixu/bin/${agent.type.defaultExecutable}" ] || grep -q "exec .*/${agent.type.defaultExecutable}" "/opt/taixu/bin/${agent.type.defaultExecutable}" 2>/dev/null; then
                        pkg_dir=""
                        for nmdir in \
                            /opt/taixu/runtimes/node/*/lib/node_modules \
                            "${'$'}NPM_PREFIX/lib/node_modules" \
                            /usr/local/lib/node_modules \
                            /usr/lib/node_modules \
                            /root/.local/lib/node_modules; do
                            if [ -d "${'$'}nmdir/$npmPackage" ]; then
                                pkg_dir="${'$'}nmdir/$npmPackage"
                                break
                            fi
                        done
                        entry_file=""
                        if [ -n "${'$'}pkg_dir" ]; then
                            for candidate in \
                                "${'$'}pkg_dir/cli.js" \
                                "${'$'}pkg_dir/bin/${agent.type.defaultExecutable}" \
                                "${'$'}pkg_dir/bin/${agent.type.defaultExecutable}.js" \
                                "${'$'}pkg_dir/dist/cli.js" \
                                "${'$'}pkg_dir/index.js"; do
                                if [ -f "${'$'}candidate" ]; then
                                    entry_file="${'$'}candidate"
                                    break
                                fi
                            done
                            if [ -z "${'$'}entry_file" ] && [ -f "${'$'}pkg_dir/package.json" ]; then
                                bin_rel="${'$'}(grep -A 5 '"bin"' "${'$'}pkg_dir/package.json" 2>/dev/null | grep -oE '"[^"]+\.js"' | tr -d '"' | head -n 1)"
                                if [ -n "${'$'}bin_rel" ] && [ -f "${'$'}pkg_dir/${'$'}bin_rel" ]; then
                                    entry_file="${'$'}pkg_dir/${'$'}bin_rel"
                                fi
                            fi
                        fi
                        if [ -n "${'$'}entry_file" ]; then
                            mkdir -p /opt/taixu/bin 2>/dev/null || true
                            rm -f "/opt/taixu/bin/${agent.type.defaultExecutable}" "/usr/local/bin/${agent.type.defaultExecutable}" 2>/dev/null || true
                            cat << 'EOF' > "/opt/taixu/bin/${agent.type.defaultExecutable}"
#!/bin/sh
export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/opt/taixu/bin/${agent.type.defaultExecutable}"
                            chmod 755 "/opt/taixu/bin/${agent.type.defaultExecutable}" 2>/dev/null || true

                            cat << 'EOF' > "/usr/local/bin/${agent.type.defaultExecutable}"
#!/bin/sh
export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
EOF
                            printf 'exec node "%s" "$@"\n' "${'$'}entry_file" >> "/usr/local/bin/${agent.type.defaultExecutable}"
                            chmod 755 "/usr/local/bin/${agent.type.defaultExecutable}" 2>/dev/null || true
                        fi
                    fi

                    if [ -f "/opt/taixu/bin/${agent.type.defaultExecutable}" ]; then
                        echo "/opt/taixu/bin/${agent.type.defaultExecutable}"
                        exit 0
                    else
                        exit 1
                    fi
                """.trimIndent()

                val verifyRes = linuxRuntime.execute(
                    ShellCommand(
                        commandLine = verifyScript,
                        timeoutMs = 10_000L,
                    ),
                )

                if (verifyRes.isSuccess && verifyRes.stdout.isNotBlank()) {
                    val binPath = verifyRes.stdout.trim().lines().firstOrNull { it.isNotBlank() } ?: verifyRes.stdout.trim()
                    log("[+] Found executable command at: $binPath")
                    val verRes = linuxRuntime.execute(
                        ShellCommand(
                            commandLine = """
                                export PATH="/opt/taixu/bin:/usr/local/bin:/root/.local/bin:${'$'}PATH"
                                ver=""
                                for nmdir in /opt/taixu/runtimes/node/*/lib/node_modules "${'$'}(npm config get prefix 2>/dev/null)/lib/node_modules" /usr/local/lib/node_modules /usr/lib/node_modules; do
                                    pj="${'$'}nmdir/$npmPackage/package.json"
                                    if [ -f "${'$'}pj" ]; then
                                        ver="${'$'}(grep -m 1 '"version"' "${'$'}pj" 2>/dev/null | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"
                                        [ -n "${'$'}ver" ] && break
                                    fi
                                done
                                if [ -z "${'$'}ver" ]; then
                                    raw="${'$'}("${agent.type.defaultExecutable}" --version 2>/dev/null || "${agent.type.defaultExecutable}" -v 2>/dev/null || true)"
                                    ver="${'$'}(echo "${'$'}raw" | head -n 1 | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1)"
                                fi
                                echo "${'$'}{ver:-installed}"
                            """.trimIndent(),
                            timeoutMs = 10_000L,
                        ),
                    )
                    val rawVer = verRes.stdout.trim().lines().firstOrNull { it.isNotBlank() }
                    val cleanVer = rawVer?.let { Regex("""[0-9]+(\.[0-9]+)+""").find(it)?.value } ?: rawVer ?: targetVersion ?: "Installed"
                    log("[+] Version detected: $cleanVer")
                    log("[+] [SUCCESS] ${agent.type.displayName} installed successfully. Run '${agent.type.defaultExecutable}' in the terminal at any time.")
                    withContext(Dispatchers.Main.immediate) {
                        _successMessage.value = "${agent.type.displayName} installed successfully. Current version: $cleanVer"
                    }
                } else {
                    log("[-] Verification failed: executable '${agent.type.defaultExecutable}' was not found in the sandbox")
                    if (cmdRes.stderr.isNotBlank()) {
                        log("[-] Process stderr:\n${cmdRes.stderr.trim()}")
                    }
                    val lastErr = cmdRes.stderr.ifBlank { cmdRes.stdout }.lines()
                        .filter { it.isNotBlank() }
                        .takeLast(3)
                        .joinToString("; ")
                    withContext(Dispatchers.Main.immediate) {
                        _errorMessage.value = "Installation incomplete: ${lastErr.ifBlank { "The ${agent.type.defaultExecutable} command was not created in the sandbox. View the log below for troubleshooting." }}"
                    }
                }

                delay(500)
                fetchDaemonData()
            } catch (e: Exception) {
                logger.w("Install agent failed: ${e.message}", e)
                log("[-] Installation error: ${e.message}")
                log(e.stackTraceToString())
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "Operation failed: ${e.message}"
                }
            } finally {
                _installingAgentType.value = null
                _isOperating.value = false
            }
        }
    }

    private fun detectLanIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces.asSequence()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
