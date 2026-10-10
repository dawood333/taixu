package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.runtime.FtpServiceState
import top.wkbin.taixu.ui.components.NoticeBanner
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton
import top.wkbin.taixu.ui.components.RuntimeSwitch
import top.wkbin.taixu.ui.components.RuntimeTextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.settings.LocalizedText as Text

@Composable
fun FtpSettingsScreen(
    onBack: () -> Unit,
    viewModel: FtpSettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val operating by viewModel.operating.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val vpnActive by viewModel.vpnActive.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var portText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(settings.port.toString()) }
    var usernameText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(settings.username) }
    var showPasswordDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showUsernameDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings.distroId, settings.port) { portText = settings.port.toString() }
    LaunchedEffect(settings.distroId, settings.username) { usernameText = settings.username }

    message?.let { current ->
        RuntimeAlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            title = { Text(if (messageIsError) "FTP operation failed" else "FTP file server") },
            text = { Text(current) },
            confirmButton = { RuntimeTextButton(onClick = viewModel::consumeMessage) { Text("Got it") } },
        )
    }

    if (showPasswordDialog) {
        FtpPasswordDialog(
            passwordConfigured = settings.passwordConfigured,
            onDismiss = { showPasswordDialog = false },
            onSave = { password ->
                showPasswordDialog = false
                viewModel.savePassword(password)
            },
            onClear = {
                showPasswordDialog = false
                viewModel.clearPassword()
            },
        )
    }

    if (showUsernameDialog) {
        FtpUsernameDialog(
            currentUsername = settings.username,
            onDismiss = { showUsernameDialog = false },
            onSave = { user ->
                showUsernameDialog = false
                viewModel.saveUsername(user)
            },
        )
    }

    val statusText = when (serviceState) {
        is FtpServiceState.Stopped -> "Stopped"
        is FtpServiceState.Starting -> "Starting"
        is FtpServiceState.Running -> "Running"
        is FtpServiceState.Failed -> "Failed to start"
    }
    val busy = operating || serviceState is FtpServiceState.Starting

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("FTP remote file access", onBack, statusText = statusText) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                NoticeBanner(
                    text = "FTP provides direct access to the Linux container's root filesystem (/). On the same Wi-Fi or network, use FileZilla, Cyberduck, or Windows File Explorer to upload/download large files and manage directories.",
                )
            }

            item {
                SectionLabel("Service status")
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Server,
                        title = "Enable FTP service",
                        subtitle = "Keep enabled and start automatically with the Linux runtime",
                        value = if (busy) {
                            "Working..."
                        } else {
                            when (serviceState) {
                                is FtpServiceState.Running -> "Running"
                                is FtpServiceState.Starting -> "Starting"
                                is FtpServiceState.Failed -> "Failed"
                                is FtpServiceState.Stopped -> "Stopped"
                            }
                        },
                        trailing = {
                            if (busy) {
                                RuntimeCircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp,
                                )
                            } else {
                                RuntimeSwitch(
                                    checked = settings.enabled,
                                    onCheckedChange = viewModel::toggleEnabled,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        },
                    )
                }
                (serviceState as? FtpServiceState.Failed)?.let { failed ->
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                    ) {
                        Text(failed.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                SectionLabel("Connection address and client settings")
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "External app connection settings (enter each field separately):",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        ParamRow(
                            label = "Server port",
                            value = settings.port.toString(),
                            highlight = true,
                            onCopy = { copyText(context, settings.port.toString(), "FTP port ${settings.port} copied") },
                        )
                        ParamRow(
                            label = "本机主机 IP (Host - 手机其他 App 连接)",
                            value = "127.0.0.1",
                            highlight = true,
                            onCopy = { copyText(context, "127.0.0.1", "本机 IP (127.0.0.1) 已Copy") },
                        )
                        ParamRow(
                            label = "局域网主机 IP (Host - 电脑/外部设备连接)",
                            value = settings.connectionHost,
                            onCopy = { copyText(context, settings.connectionHost, "局域网 IP (${settings.connectionHost}) 已Copy") },
                        )
                        ParamRow(
                            label = "登录Username (Username)",
                            value = settings.username,
                            onCopy = { copyText(context, settings.username, "Username (${settings.username}) 已Copy") },
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        Text(
                            text = "快速连接 URL：",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        ParamRow(
                            label = "局域网 / 电脑访问 URL",
                            value = settings.connectionUrl,
                            onCopy = { copyText(context, settings.connectionUrl, "局域网 FTP 地址已Copy") },
                        )
                        ParamRow(
                            label = "手机本机其他 App 访问 URL",
                            value = settings.localUrl,
                            onCopy = { copyText(context, settings.localUrl, "本机 FTP 地址已Copy") },
                        )

                        Text(
                            text = "使用提示：在手机第三方文件管理器 (如 MT管理器/Solid Explorer/CX文件管理器) 中添加 FTP 时，主机填写 127.0.0.1，端口填 ${settings.port}；同一 Wi-Fi 下电脑连接主机填 ${settings.connectionHost}。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (vpnActive) {
                item {
                    NoticeBanner(
                        text = "检测到手机正处于 VPN 连接中，VPN 可能接管局域网流量导致外部设备无法连接。若连接超时请先暂停 VPN 或将本应用加入直连白名单。",
                        isError = true,
                    )
                }
            }

            item {
                SectionLabel("登录认证与权限")
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Admin,
                        title = "登录Username",
                        subtitle = "FTP 客户端连接时使用的Username",
                        value = settings.username,
                        onClick = { showUsernameDialog = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    val passwordSubtitle = if (settings.passwordConfigured) {
                        "Password已单独配置并加密保存，点击修改或清除"
                    } else if (settings.sshPasswordConfigured) {
                        "未单独设置，已自动使用 SSH 登录Password"
                    } else {
                        "未设置Password（免密登录，客户端Password留空或填任意内容即可）"
                    }
                    val passwordValue = if (settings.passwordConfigured) {
                        "已设置"
                    } else if (settings.sshPasswordConfigured) {
                        "同 SSH Password"
                    } else {
                        "免密"
                    }
                    SettingsRow(
                        icon = RuntimeIconName.Key,
                        title = "登录Password",
                        subtitle = passwordSubtitle,
                        value = passwordValue,
                        onClick = { showPasswordDialog = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Shield,
                        title = "允许匿名访问",
                        subtitle = if (settings.anonymousEnabled) "外部客户端无需Username/Password即可登录" else "要求输入Username与Password认证",
                        trailing = {
                            RuntimeSwitch(
                                checked = settings.anonymousEnabled,
                                onCheckedChange = viewModel::toggleAnonymous,
                                enabled = !busy,
                            )
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Storage,
                        title = "只读保护模式",
                        subtitle = if (settings.readOnly) "只读模式生效：禁止外部写入、修改、重命名或删除文件" else "读写模式：允许外部自由上传、重命名与删除文件",
                        trailing = {
                            RuntimeSwitch(
                                checked = settings.readOnly,
                                onCheckedChange = viewModel::toggleReadOnly,
                                enabled = !busy,
                            )
                        },
                    )
                }
            }

            item {
                SectionLabel("网络配置")
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Network, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                        val portValue = portText.toIntOrNull()
                        val portValid = portValue != null && portValue in 1024..65535
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text("FTP 端口") },
                            isError = portText.isNotBlank() && !portValid,
                            supportingText = {
                                Text(
                                    if (portText.isNotBlank() && !portValid) "端口需在 1024–65535 范围内"
                                    else "默认端口：2121（范围 1024–65535）",
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        RuntimeOutlinedButton(
                            onClick = { copyText(context, settings.port.toString(), "FTP port ${settings.port} copied") },
                        ) {
                            RuntimeIcon(RuntimeIconName.Copy, Modifier.size(14.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Copy")
                        }
                        RuntimeOutlinedButton(
                            onClick = { viewModel.savePort(portText) },
                            enabled = !busy && portValid && portText != settings.port.toString(),
                        ) {
                            Text("保存")
                        }
                    }
                }
            }

            if (logs.isNotEmpty()) {
                item {
                    SectionLabel("最近访问日志")
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ) {
                        Text(
                            logs.takeLast(12).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

private fun copyText(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("FTP URL", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

@Composable
private fun FtpPasswordDialog(
    passwordConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var password by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var confirmation by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val matches = password == confirmation
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (passwordConfigured) "修改 FTP 登录Password" else "设置 FTP 登录Password") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Password将通过 Android Keystore 加密保存，外部客户端连接时需输入此Password。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(128) },
                    label = { Text("新Password") },
                    supportingText = { Text("至少 4 个字符") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.take(128) },
                    label = { Text("再次输入Password") },
                    isError = confirmation.isNotEmpty() && !matches,
                    supportingText = {
                        if (confirmation.isNotEmpty() && !matches) Text("两次输入的Password不一致")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onSave(password) },
                enabled = password.length >= 4 && matches,
            ) { Text("保存") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (passwordConfigured) {
                    RuntimeTextButton(onClick = onClear) { Text("清除Password") }
                }
                RuntimeTextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun FtpUsernameDialog(
    currentUsername: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var username by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(currentUsername) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 FTP 登录Username") },
        text = {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(32) },
                label = { Text("Username") },
                supportingText = { Text("默认为 root") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onSave(username.trim().ifBlank { "root" }) },
                enabled = username.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ParamRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        RuntimeOutlinedButton(
            onClick = onCopy,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Copy, Modifier.size(13.dp))
            Spacer(Modifier.size(4.dp))
            Text("Copy", style = MaterialTheme.typography.labelSmall)
        }
    }
}
