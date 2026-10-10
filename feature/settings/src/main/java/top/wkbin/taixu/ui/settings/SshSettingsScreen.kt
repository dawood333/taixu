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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
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
import top.wkbin.taixu.runtime.SshServiceState
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
fun SshSettingsScreen(
    onBack: () -> Unit,
    viewModel: SshSettingsViewModel = koinViewModel(),
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
    var authorizedKeysText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(settings.authorizedKeys) }
    var showPasswordDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.distroId, settings.port) { portText = settings.port.toString() }
    LaunchedEffect(settings.distroId, settings.authorizedKeys) { authorizedKeysText = settings.authorizedKeys }

    message?.let { current ->
        RuntimeAlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            title = { Text(if (messageIsError) "SSH operation failed" else "SSH Remote Access") },
            text = { Text(current) },
            confirmButton = { RuntimeTextButton(onClick = viewModel::consumeMessage) { Text("Got it") } },
        )
    }

    if (showPasswordDialog) {
        PasswordSettingsDialog(
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

    val statusText = when (serviceState) {
        is SshServiceState.Stopped -> "Stopped"
        is SshServiceState.Installing -> "Installing OpenSSH"
        is SshServiceState.Starting -> "Starting"
        is SshServiceState.Running -> "Running"
        is SshServiceState.Failed -> "Failed to start"
    }
    val busy = operating || serviceState is SshServiceState.Installing || serviceState is SshServiceState.Starting

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("SSH Remote Access", onBack, statusText = statusText) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                NoticeBanner(
                    text = "SSH is configured separately for the active Linux distribution. OpenSSH is installed on first use as needed. Traditional root/password login and public-key authentication are supported.",
                )
            }

            item {
                SectionLabel("Service status")
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Server,
                        title = "Enable SSH service",
                        subtitle = "Keep enabled and start automatically with the Linux runtime",
                        value = if (busy) {
                            when (serviceState) {
                                is SshServiceState.Installing -> "Installing OpenSSH"
                                is SshServiceState.Starting -> "Waiting for SSH service to become ready"
                                else -> "Working"
                            }
                        } else {
                            when (serviceState) {
                                is SshServiceState.Running -> "Running"
                                is SshServiceState.Installing -> "Installing"
                                is SshServiceState.Starting -> "Starting"
                                is SshServiceState.Failed -> "Failed"
                                is SshServiceState.Stopped -> if ((serviceState as SshServiceState.Stopped).installed) "Installed" else "Not installed"
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
                (serviceState as? SshServiceState.Failed)?.let { failed ->
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
                SectionLabel("Authentication")
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Admin,
                        title = "Login username",
                        subtitle = "Administrator account in the PRoot Linux sandbox",
                        value = "root",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Key,
                        title = "Login password",
                        subtitle = if (settings.passwordConfigured) "Password is encrypted and saved; tap to change or clear it" else "Not set. Configure a password before using password authentication",
                        value = if (settings.passwordConfigured) "Configured" else "Not set",
                        onClick = { showPasswordDialog = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Shield,
                        title = "Allow password login",
                        subtitle = if (settings.passwordAuthEnabled) "Enter the root password when connecting" else "Password authentication is disabled",
                        trailing = {
                            RuntimeSwitch(
                                checked = settings.passwordAuthEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && !settings.passwordConfigured) {
                                        showPasswordDialog = true
                                    } else {
                                        viewModel.setPasswordAuthEnabled(enabled)
                                    }
                                },
                                enabled = !busy,
                            )
                        },
                    )
                }
            }

            item {
                SectionLabel("Connection")
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = settings.connectionCommand,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "On first connection, type yes to accept the server fingerprint, then enter the root password",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RuntimeOutlinedButton(
                            onClick = { copyText(context, settings.connectionCommand, "SSH command copied") },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            RuntimeIcon(RuntimeIconName.Copy, Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Copy command")
                        }
                    }
                }
            }

            if (vpnActive) {
                item {
                    NoticeBanner(
                        text = "A VPN is active and routing this app's traffic, so connections from computers on the same LAN may time out. Disable the VPN or add this app to the VPN bypass/exclusion list.",
                        isError = true,
                    )
                }
            }

            item {
                SectionLabel("Network settings")
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Network, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                        val portValue = portText.toIntOrNull()
                        val portValid = portValue != null && portValue in 1024..65535
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text("SSH Port") },
                            isError = portText.isNotBlank() && !portValid,
                            supportingText = {
                                Text(
                                    if (portText.isNotBlank() && !portValid) "Port must be between 1024 and 65535"
                                    else "Allowed range: 1024–65535",
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        RuntimeOutlinedButton(onClick = { viewModel.savePort(portText) }, enabled = !busy && portValid) { Text("Save") }
                    }
                }
            }

            item {
                SectionLabel("Public-key authentication (optional)")
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Password authentication works without a public key. For passwordless connections, add one OpenSSH public key per line.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = authorizedKeysText,
                            onValueChange = { authorizedKeysText = it },
                            label = { Text("authorized_keys") },
                            placeholder = { Text("ssh-ed25519 AAAAC3... user@device") },
                            minLines = 4,
                            maxLines = 8,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RuntimeButton(
                            onClick = { viewModel.saveAuthorizedKeys(authorizedKeysText) },
                            enabled = !busy,
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Save, Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Save public keys")
                        }
                    }
                }
            }

            if (logs.isNotEmpty()) {
                item {
                    SectionLabel("Recent logs")
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
    clipboard?.setPrimaryClip(ClipData.newPlainText("SSH command", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

@Composable
private fun PasswordSettingsDialog(
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
        title = { Text(if (passwordConfigured) "Change SSH password" else "Set SSH password") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "The password will be encrypted using Android Keystore and applied to the root account of the current Linux distribution.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(128) },
                    label = { Text("New password") },
                    supportingText = { Text("At least 8 characters; colons are not allowed") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.take(128) },
                    label = { Text("Confirm password") },
                    isError = confirmation.isNotEmpty() && !matches,
                    supportingText = {
                        if (confirmation.isNotEmpty() && !matches) Text("The passwords do not match")
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
                enabled = password.length >= 8 && matches && ':' !in password,
            ) { Text("Save and enable") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (passwordConfigured) {
                    RuntimeTextButton(onClick = onClear) { Text("Clear password") }
                }
                RuntimeTextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
