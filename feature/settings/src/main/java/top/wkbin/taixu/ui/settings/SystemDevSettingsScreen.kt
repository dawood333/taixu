package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.ui.settings.LocalizedText as Text
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.runtime.privilege.PhantomProcessLimitState
import top.wkbin.taixu.runtime.privilege.PhantomProcessLimitStatus
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeIconButton as IconButton
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar

/**
 * 二级子页 3：系统保活与开发者诊断
 */
@Composable
fun SystemDevSettingsScreen(
    onBack: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenAdbLogcat: () -> Unit = {},
    onOpenCustomIteration: () -> Unit = {},
    onOpenPermissionGuide: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val developer by viewModel.developerMode.collectAsStateWithLifecycle()
    val qemuCompatibilityEnabled by viewModel.qemuCompatibilityEnabled.collectAsStateWithLifecycle()
    val qemuCompatibilityReady by viewModel.qemuCompatibilityReady.collectAsStateWithLifecycle()
    val qemuCompatibilityMessage by viewModel.qemuCompatibilityMessage.collectAsStateWithLifecycle()
    val phantomStatus by viewModel.phantomProcessStatus.collectAsStateWithLifecycle()
    val phantomBusy by viewModel.phantomProcessBusy.collectAsStateWithLifecycle()
    val phantomMessage by viewModel.phantomProcessMessage.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showBatteryDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showPhantomProcessDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var batteryExempted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val isRestrictiveRom = remember { RomAutostartHelper.isKnownRestrictiveRom() }
    val romLabel = remember { RomAutostartHelper.romLabel() }

    LaunchedEffect(Unit) { viewModel.refreshPhantomProcessLimit() }

    if (showBatteryDialog) {
        BatteryOptimizationDialog(
            exempted = batteryExempted,
            onRefresh = { batteryExempted = isIgnoringBatteryOptimizations(context) },
            onDismiss = { showBatteryDialog = false },
        )
    }
    if (showPhantomProcessDialog) {
        PhantomProcessLimitDialog(
            status = phantomStatus,
            busy = phantomBusy,
            message = phantomMessage,
            adbCommand = viewModel.phantomProcessAdbCommand,
            onRefresh = viewModel::refreshPhantomProcessLimit,
            onRemove = viewModel::removePhantomProcessLimit,
            onDismiss = {
                viewModel.clearPhantomProcessMessage()
                showPhantomProcessDialog = false
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("Background Persistence & Diagnostics", onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "Keep processes alive & wake",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Shield,
                        title = "OEM background restrictions & permissions guide",
                        subtitle = "OEM-specific setup for autostart, unrestricted battery use, and locking apps in recents",
                        value = "Open guide",
                        onClick = onOpenPermissionGuide,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Battery,
                        title = "Battery optimization & background persistence",
                        subtitle = "Exempt from battery restrictions to prevent the Agent from being frozen when the screen is off",
                        value = if (batteryExempted) "Exempted" else "Not exempted",
                        onClick = { showBatteryDialog = true },
                    )
                    if (isRestrictiveRom) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingsRow(
                            icon = RuntimeIconName.Cpu,
                            title = "$romLabel autostart shortcut",
                            subtitle = "Open the OEM autostart settings directly",
                            value = "Enable",
                            onClick = {
                                runCatching { RomAutostartHelper.openAutostartSettings(context) }.onFailure {
                                    Toast.makeText(context, "Could not open OEM settings. Configure this manually in system Settings → Apps.", Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Speed,
                        title = "Android 12 child-process limits",
                        subtitle = "Remove the background limit of 32 phantom processes",
                        value = when {
                            phantomBusy && phantomStatus == null -> "Checking"
                            phantomStatus?.state == PhantomProcessLimitState.REMOVED -> "Removed"
                            phantomStatus?.state == PhantomProcessLimitState.ACTIVE -> "Not removed"
                            phantomStatus?.state == PhantomProcessLimitState.UNSUPPORTED -> "Not required"
                            else -> "Pending check"
                        },
                        onClick = {
                            showPhantomProcessDialog = true
                            viewModel.refreshPhantomProcessLimit()
                        },
                    )
                }
            }

            item {
                Text(
                    text = "Android Debugging & Logs",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Terminal,
                        title = "Wireless ADB & Log Capture",
                        subtitle = "mDNS pairing discovery, automatic reconnect, and live Logcat workspace",
                        value = "Open workspace",
                        onClick = onOpenAdbLogcat,
                    )
                }
            }

            item {
                Text(
                    text = "TaiXu Custom Development",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Code,
                        title = "Custom Iteration (TaiXuDev)",
                        subtitle = "Use AI in the phone sandbox to develop TaiXu itself and build APKs in the cloud",
                        onClick = onOpenCustomIteration,
                    )
                }
            }

            item {
                Text(
                    text = "Developer Debugging & Console",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    ToggleRow(
                        icon = RuntimeIconName.Bug,
                        title = "Developer Diagnostics Mode",
                        subtitle = "Enable low-level health monitoring and the debug console",
                        checked = developer,
                        change = viewModel::setDeveloperMode,
                    )
                    if (developer) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingsRow(
                            icon = RuntimeIconName.Terminal,
                            title = "Developer Console",
                            subtitle = "View PRoot processes and command traces in real time",
                            onClick = onOpenDeveloper,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ToggleRow(
                        icon = RuntimeIconName.Cpu,
                        title = "QEMU x86_64 Compatibility Mode",
                        subtitle = if (qemuCompatibilityReady) {
                            "Allow sessions that explicitly request it to use QEMU x86_64 user mode; ARM64 sessions are unaffected"
                        } else {
                            "QEMU x86_64 compatibility environment not detected. Install the qemu-x86-64-compat plugin from the Tool Center first."
                        },
                        checked = qemuCompatibilityEnabled && qemuCompatibilityReady,
                        enabled = qemuCompatibilityReady,
                        change = viewModel::setQemuCompatibilityEnabled,
                    )
                    Text(
                        text = qemuCompatibilityMessage ?: if (qemuCompatibilityEnabled) {
                            "Enabled. The compatibility plugin provides ARM64 QEMU user mode and a minimal x86_64 RootFS only."
                        } else {
                            "Disabled by default; no x86_64 tools are downloaded or used. When enabled, it applies only to third-party projects that explicitly select compatibility mode and does not change the APK's default arm64-v8a ABI."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (qemuCompatibilityReady) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationDialog(
    exempted: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 从系统授权页返回时刷新豁免状态
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeIcon(RuntimeIconName.Shield, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Battery Optimization & Background Execution", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (exempted) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                    ) {
                        Text(
                            if (exempted) "Battery optimization exempted" else "Not exempted · background processes may be frozen",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (exempted) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Text(
                    "TaiXu starts a foreground service and holds a CPU wake lock while the Agent runs, but system battery optimization may still" +
                        "freeze processes after the screen turns off, causing Agent inference or commands to stall. We recommend enabling the following two options:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.onFailure {
                            Toast.makeText(context, "Could not open system settings. Manually grant the battery optimization exemption in Settings.", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Request battery optimization exemption")
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.onFailure {
                            Toast.makeText(context, "Could not open app details. Open them manually in system Settings.", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Open app details (autostart/background execution)")
                }
                Text(
                    "Note: On Xiaomi, Huawei, OPPO, and other vendor ROMs, you may also need to allow Autostart and Background execution manually in app details," +
                        "otherwise vendor power-saving policies may still terminate processes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun PhantomProcessLimitDialog(
    status: PhantomProcessLimitStatus?,
    busy: Boolean,
    message: String?,
    adbCommand: String,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }

    val state = status?.state
    val statusText = when (state) {
        PhantomProcessLimitState.REMOVED -> "Limit removed"
        PhantomProcessLimitState.ACTIVE -> "Limit still active"
        PhantomProcessLimitState.UNSUPPORTED -> "Not required on this system"
        PhantomProcessLimitState.UNAVAILABLE -> "Temporarily unavailable"
        null -> if (busy) "Checking" else "Not checked yet"
    }
    val healthy = state == PhantomProcessLimitState.REMOVED || state == PhantomProcessLimitState.UNSUPPORTED
    val statusContainer = if (healthy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val statusContent = if (healthy) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeIcon(RuntimeIconName.Speed, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Android 12 child-process limits", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Surface(shape = RoundedCornerShape(6.dp), color = statusContainer) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = statusContent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Text(
                    status?.details ?: "Read the actual system configuration to check whether the phantom-process limit is still active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state == PhantomProcessLimitState.REMOVED || state == PhantomProcessLimitState.ACTIVE) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Maximum phantom processes: ${status.maxPhantomProcesses ?: "system default (usually 32)"}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "Phantom-process monitoring: ${when (status.monitoringEnabled) { true -> "Enabled"; false -> "Disabled"; null -> "system default (enabled)" }}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                Text(
                    "Android 12+ monitors child processes spawned by apps. Exceeding the system limit may terminate PRoot, compilers, or Agent tasks. This changes the child-process limit, not the number of Java/Kotlin threads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Button(
                    onClick = onRemove,
                    enabled = !busy && state != PhantomProcessLimitState.REMOVED && state != PhantomProcessLimitState.UNSUPPORTED,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Working")
                    } else {
                        Text("Remove limit with Shizuku / Root")
                    }
                }

                Text(
                    "You can also run this command in a computer terminal connected to the phone:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            adbCommand,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Android 12 child-process limit command", adbCommand))
                                Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Copy, Modifier.size(15.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Copy command")
                        }
                    }
                }

                if (!message.isNullOrBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state == PhantomProcessLimitState.REMOVED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
