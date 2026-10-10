package top.wkbin.taixu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.core.model.StorageMountBinding
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import top.wkbin.taixu.ui.settings.LocalizedText as Text

@Composable
internal fun AddMountDialog(
    existingGuestPaths: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, hostPath: String, guestPath: String) -> Unit,
) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var hostPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("/storage/emulated/0/") }
    var guestPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("/mnt/") }

    val nameValid = name.isNotBlank()
    val trimmedHost = hostPath.trim()
    val hostCharInvalid = ':' in trimmedHost
    val hostRootAllowed = StorageMountBinding.isHostPathAllowed(trimmedHost)
    val hostPathValid = hostPath.isNotBlank() && !hostCharInvalid && hostRootAllowed

    // Keep normalization consistent with SettingsViewModel.addCustomMountBinding: add a leading slash if missing
    val trimmedGuest = guestPath.trim().let { if (it.startsWith("/")) it else "/$it" }
    val guestDuplicate = trimmedGuest in existingGuestPaths
    val guestNormalized = StorageMountBinding.normalizeGuestPath(trimmedGuest)
    val guestCharInvalid = ':' in trimmedGuest
    val guestRootAllowed = guestNormalized != null && StorageMountBinding.isGuestPathAllowed(trimmedGuest)
    val guestPathValid = guestPath.isNotBlank() && guestNormalized != null &&
        guestRootAllowed && !guestCharInvalid && !guestDuplicate

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add storage mount", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Mount name") },
                    placeholder = { Text("e.g. Photos, project source code") },
                    isError = !nameValid,
                    supportingText = { if (!nameValid) Text("Enter a mount name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hostPath,
                    onValueChange = { hostPath = it },
                    label = { Text("Host path (Android)") },
                    placeholder = { Text("/storage/emulated/0/...") },
                    isError = hostPath.isNotBlank() && !hostPathValid,
                    supportingText = {
                        when {
                            hostPath.isNotBlank() && hostCharInvalid -> Text("Host path cannot contain colons or other invalid characters")
                            hostPath.isNotBlank() && !hostRootAllowed ->
                                Text("Host path must be inside /storage/emulated/0")
                            else -> {}
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = guestPath,
                    onValueChange = { guestPath = it },
                    label = { Text("Container mount path (Linux)") },
                    placeholder = { Text("/mnt/my_folder") },
                    isError = guestPath.isNotBlank() && !guestPathValid,
                    supportingText = {
                        when {
                            guestPath.isNotBlank() && !guestPath.trim().startsWith("/") -> Text("Path must start with /")
                            guestPath.isNotBlank() && guestNormalized == null -> Text("Container path must not contain ..")
                            guestPath.isNotBlank() && guestCharInvalid -> Text("Container path cannot contain colons or other invalid characters")
                            guestPath.isNotBlank() && !guestRootAllowed ->
                                Text("Container path must be inside /mnt or /sdcard")
                            guestDuplicate -> Text("This container path is already used by another mount")
                            else -> {}
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, hostPath, guestPath) },
                enabled = nameValid && hostPathValid && guestPathValid,
            ) {
                Text("Add mount")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
