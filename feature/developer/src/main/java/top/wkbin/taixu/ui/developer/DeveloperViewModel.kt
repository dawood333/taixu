package top.wkbin.taixu.ui.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.OnboardingPreferences
import top.wkbin.taixu.core.datastore.RegistryPreferences
import top.wkbin.taixu.core.datastore.RuntimePreferences
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.core.model.InstalledRuntime
import top.wkbin.taixu.core.tools.RuntimeManager
import top.wkbin.taixu.core.tools.SignedRegistryRequest
import top.wkbin.taixu.core.tools.ToolManager
import top.wkbin.taixu.core.tools.ToolRegistry
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.RuntimeHealth
import top.wkbin.taixu.runtime.RootfsUpdateInfo
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DeveloperViewModel(
    private val linuxRuntime: LinuxRuntime,
    private val runtimeManager: RuntimeManager,
    private val runtimePreferences: RuntimePreferences,
    private val agentPreferences: AgentPreferences,
    private val registryPreferences: RegistryPreferences,
    private val onboardingPreferences: OnboardingPreferences,
    private val toolRegistry: ToolRegistry,
    private val toolManager: ToolManager,
    private val logger: AppLogger,
    private val embeddedAdbManager: EmbeddedAdbManager,
) : ViewModel() {

    init {
        embeddedAdbManager.startDiscovery(EmbeddedAdbManager.TAG_DEVELOPER_UI)
    }

    override fun onCleared() {
        super.onCleared()
        embeddedAdbManager.stopDiscovery(EmbeddedAdbManager.TAG_DEVELOPER_UI)
    }

    val runtimeState: StateFlow<RuntimeState> = linuxRuntime.state

    private val _commandInput = MutableStateFlow("cat /etc/os-release")
    val commandInput: StateFlow<String> = _commandInput.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _health = MutableStateFlow<RuntimeHealth?>(null)
    val health: StateFlow<RuntimeHealth?> = _health.asStateFlow()

    private val _commandResult = MutableStateFlow<CommandResult?>(null)
    val commandResult: StateFlow<CommandResult?> = _commandResult.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 当前 message 是否为失败结果（类型化标记，避免 UI 用字符串匹配判断样式）。 */
    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    private fun setMessage(text: String, isError: Boolean = false) {
        _message.value = text
        _messageIsError.value = isError
    }
    private val _unusedRuntimes = MutableStateFlow<List<InstalledRuntime>>(emptyList())
    val unusedRuntimes: StateFlow<List<InstalledRuntime>> = _unusedRuntimes.asStateFlow()
    private val _processes = MutableStateFlow<List<ManagedProcess>>(emptyList())
    val processes: StateFlow<List<ManagedProcess>> = _processes.asStateFlow()
    private val _rootfsVersion = MutableStateFlow<String?>(null)
    val rootfsVersion: StateFlow<String?> = _rootfsVersion.asStateFlow()
    private val _rootfsUpdate = MutableStateFlow<RootfsUpdateInfo?>(null)
    val rootfsUpdate: StateFlow<RootfsUpdateInfo?> = _rootfsUpdate.asStateFlow()
    private var initializationJob: Job? = null

    val adbState: StateFlow<EmbeddedAdbManager.ConnectionState> = embeddedAdbManager.state
    val adbDiscovery: StateFlow<EmbeddedAdbManager.DiscoveryState> = embeddedAdbManager.discovery
    private val _adbBusy = MutableStateFlow(false)
    val adbBusy: StateFlow<Boolean> = _adbBusy.asStateFlow()
    private val _adbMessage = MutableStateFlow<String?>(null)
    val adbMessage: StateFlow<String?> = _adbMessage.asStateFlow()
    private val _logcatOutput = MutableStateFlow("")
    val logcatOutput: StateFlow<String> = _logcatOutput.asStateFlow()

    val adbNotificationEnabled: StateFlow<Boolean> = runtimePreferences.adbNotificationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAdbNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runtimePreferences.setAdbNotificationEnabled(enabled)
        }
    }

    fun pairWirelessAdb(code: String, pairingPort: Int? = null) {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = if (pairingPort != null) "Pairing securely through the specified pairing port $pairingPort…" else "Pairing securely through the automatically discovered pairing port…"
            val result = embeddedAdbManager.pair(pairingPort, code.trim())
            _adbMessage.value = result.fold(
                onSuccess = { "Pairing and connection succeeded. The key is stored securely; the port will be discovered and reconnected automatically next time." },
                onFailure = { it.message ?: "Wireless ADB pairing failed" },
            )
            _adbBusy.value = false
        }
    }

    fun connectWirelessAdb(explicitPort: Int? = null) {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = if (explicitPort != null) "Connecting to the specified port $explicitPort…" else "Connecting to the automatically discovered wireless debugging port…"
            val result = embeddedAdbManager.connect(explicitPort)
            _adbMessage.value = result.fold(
                onSuccess = { "Wireless ADB connected." },
                onFailure = { it.message ?: "Wireless ADB connection failed" },
            )
            _adbBusy.value = false
        }
    }

    fun restartAdbDiscovery() {
        embeddedAdbManager.restartDiscovery()
        _adbMessage.value = "mDNS port discovery restarted."
    }

    fun captureLogcat(packageName: String, tag: String, priority: Char, keyword: String, lines: Int) {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = "Capturing logs…"
            runCatching {
                embeddedAdbManager.captureLogcat(
                    EmbeddedAdbManager.LogcatRequest(packageName.trim(), tag.trim(), priority, keyword, lines),
                )
            }.onSuccess { result ->
                _logcatOutput.value = result.output
                _adbMessage.value = if (result.success) "Log capture completed." else result.output
            }.onFailure { error ->
                _adbMessage.value = error.message ?: "Log capture failed"
            }
            _adbBusy.value = false
        }
    }

    fun clearDeviceLogcat() {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            val result = embeddedAdbManager.clearLogcat()
            if (result.success) _logcatOutput.value = ""
            _adbMessage.value = if (result.success) "Device Logcat buffer cleared." else result.output
            _adbBusy.value = false
        }
    }

    val registryManifestUrl: StateFlow<String> = registryPreferences.manifestUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val registrySignatureUrl: StateFlow<String> = registryPreferences.signatureUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val registryPublicKey: StateFlow<String> = registryPreferences.publicKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val _registryStatus = MutableStateFlow<String?>(null)
    val registryStatus: StateFlow<String?> = _registryStatus.asStateFlow()

    /** 工具清单更新是否失败（类型化标记）。 */
    private val _registryStatusIsError = MutableStateFlow(false)
    val registryStatusIsError: StateFlow<Boolean> = _registryStatusIsError.asStateFlow()

    private fun setRegistryStatus(text: String, isError: Boolean = false) {
        _registryStatus.value = text
        _registryStatusIsError.value = isError
    }

    val agentLoggingEnabled: StateFlow<Boolean> = agentPreferences.agentLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _agentLogSize = MutableStateFlow(0L)
    val agentLogSize: StateFlow<Long> = _agentLogSize.asStateFlow()
    private val _agentLogLocation = MutableStateFlow("")
    val agentLogLocation: StateFlow<String> = _agentLogLocation.asStateFlow()

    fun setAgentLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            agentPreferences.setAgentLoggingEnabled(enabled)
            refreshAgentLogSize()
        }
    }

    fun refreshAgentLogSize() {
        _agentLogSize.value = logger.getAgentLogSizeBytes()
        _agentLogLocation.value = logger.getAgentLogLocation()
    }

    fun readAgentLogs(): String = logger.readAgentLogs()

    fun clearAgentLogs() {
        logger.clearAgentLogs()
        refreshAgentLogSize()
        setMessage("Agent logs cleared.")
    }

    fun saveRegistryConfig(manifestUrl: String, signatureUrl: String, publicKey: String) {
        viewModelScope.launch {
            registryPreferences.setRegistryConfig(manifestUrl, signatureUrl, publicKey)
            setRegistryStatus("Tool registry configuration saved.")
        }
    }

    fun updateRegistry() {
        viewModelScope.launch {
            setRegistryStatus("Downloading and verifying tool registry…")
            val request = SignedRegistryRequest(
                manifestUrl = registryManifestUrl.value,
                signatureUrl = registrySignatureUrl.value,
                publicKeyBase64 = registryPublicKey.value,
            )
            val result = toolRegistry.updateSigned(request)
            if (result.isSuccess) {
                toolManager.syncRegistry()
                setRegistryStatus("Tool registry updated: ${result.getOrNull()} tools.")
            } else {
                logger.e("Registry update failed: ${result.errorOrNull()?.message}")
                setRegistryStatus("Failed to update tool registry. Check the manifest URL, signature, and public key configuration.", isError = true)
            }
        }
    }

    init {
        refreshUnusedRuntimes()
        refreshProcesses()
        refreshRootfsVersion()
        refreshAgentLogSize()
    }

    fun onCommandInputChanged(value: String) {
        _commandInput.value = value
    }

    fun initialize() {
        if (_busy.value) return
        _message.value = null
        initializationJob = viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = linuxRuntime.initialize()) {
                    is AppResult.Success -> setMessage("Initialization complete.")
                    is AppResult.Failure -> {
                        logger.e("Runtime initialize failed: ${result.error.message}")
                        setMessage("Initialization failed. Check network access and available storage, then try again.", isError = true)
                    }
                }
            } finally {
                _busy.value = false
                initializationJob = null
            }
        }
    }

    fun cancelInitialization() {
        initializationJob?.cancel()
    }

    fun updateRootfs() {
        if (_busy.value || linuxRuntime.state.value !is RuntimeState.Ready) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            _messageIsError.value = false
            runCatching { linuxRuntime.updateRootfs() }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        setMessage("RootFS update completed; user data was preserved.")
                    } else {
                        logger.e("RootFS update failed: ${result.errorOrNull()?.message}")
                        setMessage("RootFS update failed; the previous version was restored automatically. Try again later.", isError = true)
                    }
                    refreshRootfsVersion()
                }
                .onFailure { throwable ->
                    logger.e("RootFS update failed", throwable)
                    setMessage("RootFS update failed. Check the network and try again.", isError = true)
                }
            _busy.value = false
        }
    }

    fun checkRootfsUpdate() {
        if (_busy.value || linuxRuntime.state.value !is RuntimeState.Ready) return
        viewModelScope.launch {
            _busy.value = true
            setMessage("Checking the RootFS OCI manifest…")
            runCatching { linuxRuntime.checkRootfsUpdate() }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        val info = result.getOrNull()!!
                        _rootfsUpdate.value = info
                        if (info.hasUpdate) {
                            setMessage("A new RootFS version is available.")
                        } else {
                            setMessage("RootFS is up to date.")
                        }
                    } else {
                        logger.e("RootFS update check failed: ${result.errorOrNull()?.message}")
                        setMessage("Failed to check for RootFS updates. Check the network and try again.", isError = true)
                    }
                }
                .onFailure { throwable ->
                    logger.e("RootFS update check failed", throwable)
                    setMessage("Failed to check for RootFS updates. Check the network and try again.", isError = true)
                }
            _busy.value = false
        }
    }

    fun runHealthCheck() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { linuxRuntime.healthCheck() }
                .onSuccess { _health.value = it }
                .onFailure {
                    logger.e("Health check failed", it)
                    setMessage("Health check failed. Make sure the runtime is ready, then try again.", isError = true)
                }
            _busy.value = false
        }
    }

    fun refreshUnusedRuntimes() {
        viewModelScope.launch {
            runCatching { runtimeManager.unusedRuntimes() }
                .onSuccess { _unusedRuntimes.value = it }
                .onFailure { logger.e("Failed to read unused runtimes", it) }
        }
    }

    fun cleanupRuntime(runtimeId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { runtimeManager.cleanup(runtimeId) }
                .onSuccess { result ->
                    if (result.isSuccess) setMessage("Shared runtime cleaned up.") else setMessage("Cleanup failed. Try again later.", isError = true)
                    refreshUnusedRuntimes()
                }
                .onFailure { logger.e("Runtime cleanup failed", it); setMessage("Cleanup failed. Try again later.", isError = true) }
            _busy.value = false
        }
    }

    fun refreshProcesses() {
        viewModelScope.launch {
            runCatching {
                linuxRuntime.cleanupDeadBackground()
                linuxRuntime.listBackground()
            }.onSuccess { _processes.value = it }
                .onFailure { logger.e("Failed to read background processes", it) }
        }
    }

    fun stopProcess(processId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            runCatching { linuxRuntime.stopBackground(processId) }
                .onSuccess { refreshProcesses() }
                .onFailure { logger.e("Stop process failed", it); setMessage("Failed to stop the process. Try again later.", isError = true) }
        }
    }

    fun refreshRootfsVersion() {
        _rootfsVersion.value = linuxRuntime.rootfsVersion()
    }

    fun resetLinuxEnvironment() {
        if (_busy.value || linuxRuntime.state.value is RuntimeState.Initializing) return
        viewModelScope.launch {
            _busy.value = true
            val distroId = linuxRuntime.activeDistroId.value
            runCatching {
                val result = linuxRuntime.resetSandbox(distroId)
                check(result.isSuccess) { result.errorOrNull()?.message ?: "Failed to reset the Linux environment" }
                toolManager.resetDistroState(distroId)
                // A factory-style runtime reset intentionally restarts the
                // complete first-run flow: environment download, then model
                // selection/configuration.
                onboardingPreferences.setOnboardingCompleted(false)
                result
            }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        setMessage("Linux environment restored to its initial state. Workspace projects were not deleted.")
                    } else {
                        logger.e("Linux reset failed: ${result.errorOrNull()?.message}")
                        setMessage("Reset failed. Try again later.", isError = true)
                    }
                }
                .onFailure { logger.e("Linux reset failed", it); setMessage("Reset failed. Try again later.", isError = true) }
            _busy.value = false
        }
    }

    fun runCommand() {
        if (_busy.value) return
        val command = _commandInput.value.trim()
        if (command.isEmpty()) {
            setMessage("Command cannot be empty.", isError = true)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching {
                linuxRuntime.execute(ShellCommand(commandLine = command))
            }.onSuccess {
                _commandResult.value = it
            }.onFailure {
                logger.e("Command execution failed", it)
                _commandResult.value = null
                setMessage("Command execution failed. Check the command syntax and runtime status.", isError = true)
            }
            _busy.value = false
        }
    }
}
