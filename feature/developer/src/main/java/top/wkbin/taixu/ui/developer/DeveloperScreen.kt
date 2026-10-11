package top.wkbin.taixu.ui.developer

import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.ui.components.RuntimeAlertDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import androidx.compose.material3.HorizontalDivider
import top.wkbin.taixu.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.wkbin.taixu.ui.components.RuntimeSwitch as Switch
import top.wkbin.taixu.ui.developer.LocalizedText as Text
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.core.model.InstalledRuntime
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.ui.components.EmptyPanel
import top.wkbin.taixu.ui.components.IconTile
import top.wkbin.taixu.ui.components.InfoRow
import top.wkbin.taixu.ui.components.NoticeBanner
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.SectionHeader
import top.wkbin.taixu.ui.components.StatusBadge
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import top.wkbin.taixu.runtime.RootfsUpdateInfo

@Composable
fun DeveloperScreen(
    onBack: () -> Unit,
    onOpenCatalog: () -> Unit = {},
    viewModel: DeveloperViewModel = koinViewModel(),
) {
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val commandInput by viewModel.commandInput.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val commandResult by viewModel.commandResult.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    val registryStatusIsError by viewModel.registryStatusIsError.collectAsStateWithLifecycle()
    val unusedRuntimes by viewModel.unusedRuntimes.collectAsStateWithLifecycle()
    val processes by viewModel.processes.collectAsStateWithLifecycle()
    val rootfsVersion by viewModel.rootfsVersion.collectAsStateWithLifecycle()
    val rootfsUpdate by viewModel.rootfsUpdate.collectAsStateWithLifecycle()
    val savedManifestUrl by viewModel.registryManifestUrl.collectAsStateWithLifecycle()
    val savedSignatureUrl by viewModel.registrySignatureUrl.collectAsStateWithLifecycle()
    val savedPublicKey by viewModel.registryPublicKey.collectAsStateWithLifecycle()
    val registryStatus by viewModel.registryStatus.collectAsStateWithLifecycle()
    val agentLoggingEnabled by viewModel.agentLoggingEnabled.collectAsStateWithLifecycle()
    val agentLogSize by viewModel.agentLogSize.collectAsStateWithLifecycle()
    val agentLogLocation by viewModel.agentLogLocation.collectAsStateWithLifecycle()
    val adbState by viewModel.adbState.collectAsStateWithLifecycle()
    val adbDiscovery by viewModel.adbDiscovery.collectAsStateWithLifecycle()
    val adbBusy by viewModel.adbBusy.collectAsStateWithLifecycle()
    val adbMessage by viewModel.adbMessage.collectAsStateWithLifecycle()
    val logcatOutput by viewModel.logcatOutput.collectAsStateWithLifecycle()
    var manifestUrl by remember(savedManifestUrl) { mutableStateOf(savedManifestUrl) }
    var signatureUrl by remember(savedSignatureUrl) { mutableStateOf(savedSignatureUrl) }
    var publicKey by remember(savedPublicKey) { mutableStateOf(savedPublicKey) }
    var cleanupTarget by remember { mutableStateOf<InstalledRuntime?>(null) }
    var showRootfsUpdateConfirmation by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showAgentLogDialog by remember { mutableStateOf(false) }
    var agentLogText by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var logcatPackage by remember { mutableStateOf("") }
    var logcatTag by remember { mutableStateOf("") }
    var logcatKeyword by remember { mutableStateOf("") }
    var logcatLines by remember { mutableStateOf("200") }
    var logcatPriority by remember { mutableStateOf('V') }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(adbState) {
        if (adbState is EmbeddedAdbManager.ConnectionState.Connected) pairingCode = ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("Developer Console", onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconTile(RuntimeIconName.Sparkles)
                        Column {
                            Text("Liquid Glass Component Gallery", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Apple HIG / Kyant Backdrop design comparison and complete component gallery",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(onClick = onOpenCatalog) {
                        Text("Open gallery")
                    }
                }
            }

            RuntimeControlCard(
                state = runtimeState,
                rootfsVersion = rootfsVersion,
                rootfsUpdate = rootfsUpdate,
                busy = busy,
                onInitialize = viewModel::initialize,
                onCancel = viewModel::cancelInitialization,
                onUpdate = { showRootfsUpdateConfirmation = true },
                onCheckUpdate = viewModel::checkRootfsUpdate,
                onHealthCheck = viewModel::runHealthCheck,
                onReset = { showResetConfirmation = true },
            )

            message?.let { NoticeBanner(it, isError = messageIsError) }

            health?.let { result ->
                RuntimeCard(
                    Modifier.fillMaxWidth(),
                    containerColor = if (result.isHealthy) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(
                            if (result.isHealthy) RuntimeIconName.Shield else RuntimeIconName.Alert,
                            color = if (result.isHealthy) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (result.isHealthy) "Health check passed" else "Health check failed", style = MaterialTheme.typography.titleLarge)
                            Text(result.detail ?: "Runtime core capabilities have been checked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                    InfoRow("System", result.osRelease ?: "-")
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Architecture", result.architecture ?: "-")
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Workspace writable", if (result.workspaceWritable) "Yes" else "No")
                }
            }

            SectionHeader("Wireless ADB · Logcat", "mDNS discovers dynamic ports; reconnects automatically after the first successful pairing")
            RuntimeCard(Modifier.fillMaxWidth()) {
                val connected = adbState as? EmbeddedAdbManager.ConnectionState.Connected
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconTile(RuntimeIconName.Terminal)
                    Column(Modifier.weight(1f)) {
                        Text("Built-in wireless ADB", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (val current = adbState) {
                                EmbeddedAdbManager.ConnectionState.Disconnected -> "Disconnected"
                                EmbeddedAdbManager.ConnectionState.Discovering -> "Discovering port via mDNS"
                                EmbeddedAdbManager.ConnectionState.Pairing -> "Pairing securely"
                                EmbeddedAdbManager.ConnectionState.Connecting -> "Connecting"
                                is EmbeddedAdbManager.ConnectionState.Connected -> "${current.host}:${current.port}"
                                is EmbeddedAdbManager.ConnectionState.Failed -> current.message
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    StatusBadge(if (connected != null) "Connected" else "Not ready", if (connected != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Discovered: ${adbDiscovery.pairingEndpoints.size} pairing ports · ${adbDiscovery.connectEndpoints.size} connection ports",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { value -> pairingCode = value.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("One-time 6-digit pairing code") },
                    supportingText = { Text("First, open “Wireless debugging” in system Settings and select “Pair device with pairing code”; the port is discovered automatically.") },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.pairWirelessAdb(pairingCode) },
                        enabled = !adbBusy && pairingCode.length == 6 && adbDiscovery.pairingEndpoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Pair and connect") }
                    OutlinedButton(
                        onClick = viewModel::connectWirelessAdb,
                        enabled = !adbBusy && adbDiscovery.connectEndpoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Reconnect") }
                }
                adbMessage?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(18.dp))
                Text("App log capture", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = logcatPackage,
                    onValueChange = { logcatPackage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Package name (leave blank to capture all)") },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = logcatTag,
                        onValueChange = { logcatTag = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Tag (optional)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = logcatLines,
                        onValueChange = { logcatLines = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(0.55f),
                        label = { Text("Line count") },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = logcatKeyword,
                    onValueChange = { logcatKeyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Keyword (optional, case-insensitive)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf('V', 'D', 'I', 'W', 'E', 'F').forEach { priority ->
                        if (priority == logcatPriority) {
                            Button(onClick = { logcatPriority = priority }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) { Text(priority.toString()) }
                        } else {
                            OutlinedButton(onClick = { logcatPriority = priority }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) { Text(priority.toString()) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.captureLogcat(logcatPackage, logcatTag, logcatPriority, logcatKeyword, logcatLines.toIntOrNull() ?: 200) },
                        enabled = connected != null && !adbBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Capture logs") }
                    OutlinedButton(onClick = viewModel::clearDeviceLogcat, enabled = connected != null && !adbBusy) { Text("Clear") }
                }
                if (logcatOutput.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            logcatOutput,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Logcat", logcatOutput))
                            Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Copy logs") }
                }
            }

            SectionHeader("Tool sources", "Remote manifest updates: HTTPS + Ed25519 signature verification (a custom public key detects transfer corruption but is not a tamper-proof trust anchor)")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 保存前校验：两个地址必须Yes HTTPS，Public key cannot be empty
                    val manifestUrlValid = manifestUrl.trim().startsWith("https://")
                    val signatureUrlValid = signatureUrl.trim().startsWith("https://")
                    val publicKeyValid = publicKey.isNotBlank()
                    OutlinedTextField(
                        value = manifestUrl,
                        onValueChange = { manifestUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Manifest HTTPS URL") },
                        isError = manifestUrl.isNotBlank() && !manifestUrlValid,
                        supportingText = {
                            if (manifestUrl.isNotBlank() && !manifestUrlValid) Text("URL must begin with https://")
                        },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = signatureUrl,
                        onValueChange = { signatureUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Signature HTTPS URL") },
                        isError = signatureUrl.isNotBlank() && !signatureUrlValid,
                        supportingText = {
                            if (signatureUrl.isNotBlank() && !signatureUrlValid) Text("URL must begin with https://")
                        },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = { publicKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Ed25519 public key (Base64)") },
                        isError = !publicKeyValid,
                        supportingText = {
                            if (!publicKeyValid) Text("Public key cannot be empty")
                        },
                        minLines = 2,
                    )
                    Text(
                        "Note: whoever controls the manifest URL can also supply the public key. A user-provided key detects transfer corruption and checks content consistency, but does not provide end-to-end tamper protection. For a true trust anchor, embed a fixed public key in the APK.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.saveRegistryConfig(manifestUrl.trim(), signatureUrl.trim(), publicKey.trim()) },
                            enabled = manifestUrlValid && signatureUrlValid && publicKeyValid,
                        ) { Text("Save configuration") }
                        OutlinedButton(
                            onClick = viewModel::updateRegistry,
                            enabled = manifestUrlValid && signatureUrlValid && publicKeyValid,
                        ) { Text("Check for updates") }
                    }
                    registryStatus?.let { NoticeBanner(it, isError = registryStatusIsError) }
                }
            }

            SectionHeader("Resource management", "Background Linux processes and shared dependencies")

            RuntimeCard(Modifier.fillMaxWidth()) {
                ResourceHeader(RuntimeIconName.Cpu, "Background processes", "${processes.size} active processes", viewModel::refreshProcesses)
                Spacer(Modifier.height(14.dp))
                if (processes.isEmpty()) {
                    Text("No background processes are currently registered.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    processes.forEachIndexed { index, process ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(process.toolId ?: process.id, style = MaterialTheme.typography.titleMedium)
                                Text("${process.type} · PID ${process.pid ?: "Unknown"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { viewModel.stopProcess(process.id) }, enabled = !busy) { Text("Stop") }
                        }
                    }
                }
            }

            RuntimeCard(Modifier.fillMaxWidth()) {
                ResourceHeader(RuntimeIconName.Package, "Unused runtimes", "Reference count is 0", viewModel::refreshUnusedRuntimes)
                Spacer(Modifier.height(8.dp))
                Text("Only shared dependencies that are not referenced by any tools are listed here. Cleanup runs package-manager cleanup inside the Linux sandbox.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (unusedRuntimes.isEmpty()) {
                    Text("There are no shared runtimes that can be safely cleaned up.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    unusedRuntimes.forEachIndexed { index, runtime ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(runtime.name.name, style = MaterialTheme.typography.titleMedium)
                                Text(runtime.version ?: "Unknown version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { cleanupTarget = runtime }, enabled = !busy) { Text("Clean up") }
                        }
                    }
                }
            }

            SectionHeader("Agent diagnostics", "Persist Agent execution traces, tool calls, and error details locally")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(RuntimeIconName.Shield)
                    Column(Modifier.weight(1f)) {
                        Text("Local Agent debug logs", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (agentLoggingEnabled) "Enabled (file size: ${agentLogSize.toReadableSize()})" else "Disabled (no local log file is written)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (agentLogLocation.isNotBlank()) {
                            Text(
                                "Log file: $agentLogLocation",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = agentLoggingEnabled,
                        onCheckedChange = viewModel::setAgentLoggingEnabled,
                    )
                }
                if (agentLoggingEnabled || agentLogSize > 0L) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                agentLogText = viewModel.readAgentLogs()
                                showAgentLogDialog = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("View logs")
                        }
                        OutlinedButton(
                            onClick = {
                                val logs = viewModel.readAgentLogs()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("TaiXu Agent Logs", logs))
                                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Copy all")
                        }
                        TextButton(
                            onClick = viewModel::clearAgentLogs,
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            SectionHeader("Command diagnostics", "Run a one-off shell command in an isolated Linux environment")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(RuntimeIconName.Terminal)
                    Column(Modifier.weight(1f)) {
                        Text("Shell Runner", style = MaterialTheme.typography.titleMedium)
                        Text("For quick diagnostics; does not create a persistent terminal session", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = viewModel::onCommandInputChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Shell command") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = viewModel::runCommand, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    RuntimeIcon(RuntimeIconName.Play, Modifier.size(18.dp)); Spacer(Modifier.size(7.dp)); Text("Run command")
                }
                commandResult?.let { result ->
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Color(0xFF080D16)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("exit ${result.exitCode}  •  ${result.durationMs} ms", style = MaterialTheme.typography.labelMedium, color = if (result.exitCode == 0) Color(0xFF83EDC9) else Color(0xFFFFA2AE))
                        Text(result.stdout.ifBlank { "(no stdout)" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color(0xFFD9E2F1))
                        if (result.stderr.isNotBlank()) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                            Text(result.stderr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color(0xFFFFA2AE))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    cleanupTarget?.let { runtime ->
        RuntimeAlertDialog(
            onDismissRequest = { cleanupTarget = null },
            title = { Text("Clean up ${runtime.name.name}？") },
            text = { Text("This removes a shared runtime that is not referenced by any tools. This action cannot be automatically reversed.") },
            confirmButton = { TextButton(onClick = { cleanupTarget = null; viewModel.cleanupRuntime(runtime.id) }) { Text("Confirm cleanup", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { cleanupTarget = null }) { Text("Cancel") } },
        )
    }
    if (showResetConfirmation) {
        var countdown by remember { mutableStateOf(3) }
        LaunchedEffect(Unit) {
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
        }
        RuntimeAlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset the current sandbox?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• Manually installed packages and system modifications will be removed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• Project code under /workspace will not be deleted", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showResetConfirmation = false; viewModel.resetLinuxEnvironment() },
                    enabled = countdown == 0,
                ) {
                    Text(
                        if (countdown > 0) "Confirm reset (${countdown}s)" else "Confirm reset",
                        color = if (countdown == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { showResetConfirmation = false }) { Text("Cancel") } },
        )
    }
    if (showRootfsUpdateConfirmation) {
        RuntimeAlertDialog(
            onDismissRequest = { showRootfsUpdateConfirmation = false },
            title = { Text("Update Linux RootFS?") },
            text = { Text("A new version will be downloaded and verified online; /root and /opt/taixu will be preserved. Background Linux processes will stop during the update, and the previous version will be restored automatically if it fails.") },
            confirmButton = { TextButton(onClick = { showRootfsUpdateConfirmation = false; viewModel.updateRootfs() }) { Text("Confirm update") } },
            dismissButton = { TextButton(onClick = { showRootfsUpdateConfirmation = false }) { Text("Cancel") } },
        )
    }
    if (showAgentLogDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showAgentLogDialog = false },
            title = { Text("Local Agent debug logs") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        agentLogText.ifBlank { "No logs yet" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAgentLogDialog = false }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("TaiXu Agent Logs", agentLogText))
                    Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
        )
    }
}

@Composable
private fun RuntimeControlCard(
    state: RuntimeState,
    rootfsVersion: String?,
    rootfsUpdate: RootfsUpdateInfo?,
    busy: Boolean,
    onInitialize: () -> Unit,
    onCancel: () -> Unit,
    onUpdate: () -> Unit,
    onCheckUpdate: () -> Unit,
    onHealthCheck: () -> Unit,
    onReset: () -> Unit,
) {
    val color = when (state) {
        RuntimeState.Ready -> MaterialTheme.colorScheme.secondary
        is RuntimeState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    RuntimeCard(Modifier.fillMaxWidth(), containerColor = color.copy(alpha = 0.08f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(RuntimeIconName.Terminal, color = color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Linux Runtime", style = MaterialTheme.typography.titleLarge)
                Text("RootFS ${rootfsVersion ?: "Not installed"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rootfsUpdate?.let { info ->
                    Text(
                        if (info.hasUpdate) "Update available" else "Up to date",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (info.hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusBadge(describeState(state), color)
        }
        val initializing = state as? RuntimeState.Initializing
        if (initializing != null) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { initializing.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(displayStep(initializing.step), style = MaterialTheme.typography.bodySmall)
                Text("${(initializing.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            initializing.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel download") }
        } else {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onInitialize, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state is RuntimeState.Error) "Retry initialization" else if (state is RuntimeState.Ready) "Recheck initialization" else "Download and initialize")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onHealthCheck,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text("Health check", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onCheckUpdate,
                    enabled = !busy && state is RuntimeState.Ready,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text("Check for updates", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onUpdate,
                    enabled = !busy && state is RuntimeState.Ready,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text("Update RootFS", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            Spacer(Modifier.height(9.dp))
            OutlinedButton(
                onClick = onReset,
                enabled = !busy && state !is RuntimeState.Initializing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore initial Linux state", color = MaterialTheme.colorScheme.error) }
        }
        (state as? RuntimeState.Error)?.let {
            Spacer(Modifier.height(12.dp))
            // 面向用户的提示与原始Error解耦：原始Error进日志，避免技术文本直接展示
            androidx.compose.runtime.LaunchedEffect(it.throwable) {
                Log.w("DeveloperScreen", "Runtime error: ${it.throwable.message}")
            }
            NoticeBanner("Runtime initialization failed. Check network access and storage space, then tap “Retry initialization”", isError = true)
        }
    }
}

@Composable
private fun ResourceHeader(icon: RuntimeIconName, title: String, detail: String, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }
}

private fun Long.toReadableSize(): String {
    if (this < 1024L) return "$this B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index += 1 }
    return "%.1f %s".format(value, units[index])
}

private fun describeState(state: RuntimeState): String = when (state) {
    RuntimeState.NotInitialized -> "Not initialized"
    is RuntimeState.Initializing -> "Initializing"
    RuntimeState.Ready -> "Ready"
    is RuntimeState.Error -> "Error"
}

private fun displayStep(step: String): String = when (step) {
    "detectArchitecture" -> "Detect device architecture"
    "checkStorage" -> "Check available storage"
    "createDirectories" -> "Create runtime directories"
    "Prepare PRoot" -> "Prepare PRoot launch components"
    "configureRootfs" -> "Configure Linux system"
    "configureDns" -> "Configure DNS"
    "configureEnvironment" -> "Configure environment variables"
    "createWorkspace" -> "Create workspace"
    "runHealthCheck" -> "Run health check"
    else -> step
}
