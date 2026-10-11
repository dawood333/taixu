package top.wkbin.taixu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.PhoneAgentEndpoint
import top.wkbin.taixu.core.tools.AgentModelConnectionTester
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton
import top.wkbin.taixu.ui.components.RuntimeTextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.settings.LocalizedText as Text

class PhoneAgentSettingsViewModel(
    private val preferences: AgentPreferences,
    private val connectionTester: AgentModelConnectionTester,
) : ViewModel() {
    val config: Flow<PhoneAgentEndpoint> = preferences.phoneAgentConfig
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    fun save(value: PhoneAgentEndpoint) {
        viewModelScope.launch { preferences.setPhoneAgentConfig(value) }
    }

    fun test(baseUrl: String, model: String, apiKey: String, onResult: (String) -> Unit) {
        val error = phoneAgentConfigError(baseUrl, model, apiKey)
        if (error != null) {
            onResult(error)
            return
        }
        viewModelScope.launch {
            _testing.value = true
            val result = runCatching {
                connectionTester.test(baseUrl.trim(), model.trim(), apiKey.trim())
            }.fold(
                onSuccess = { "Connection successful" },
                onFailure = { it.message?.take(180) ?: "Connection failed. Check the endpoint, API key, and network." },
            )
            _testing.value = false
            onResult(result)
        }
    }
}

@Composable
internal fun PhoneAgentSettingsEntry(
    onOpen: () -> Unit,
    viewModel: PhoneAgentSettingsViewModel = koinViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle(PhoneAgentEndpoint())
    SettingsRow(
        icon = RuntimeIconName.Android,
        title = "Phone Control Model",
        subtitle = "Uses a separate endpoint, model name, and API key; it is not part of the chat model configuration",
        value = config.model.ifBlank { "Not configured" },
        onClick = onOpen,
    )
}

@Composable
fun PhoneAgentSettingsScreen(
    onBack: () -> Unit,
    viewModel: PhoneAgentSettingsViewModel = koinViewModel(),
) {
    val saved by viewModel.config.collectAsStateWithLifecycle(PhoneAgentEndpoint())
    var baseUrl by remember(saved) { mutableStateOf(saved.baseUrl) }
    var model by remember(saved) { mutableStateOf(saved.model) }
    var apiKey by remember(saved) { mutableStateOf(saved.apiKey) }
    var message by remember { mutableStateOf<String?>(null) }
    val testing by viewModel.testing.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("Phone Control Model", onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Used only for the virtual screen to inspect the display, tap, swipe, and type. Configure the main chat model under Model Profiles; the two configurations are independent.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsGroup {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Endpoint URL") },
                        placeholder = { Text("https://open.bigmodel.cn/api/paas/v4") },
                        supportingText = { Text("Use the endpoint shown above for the Zhipu Open Platform") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model name") },
                        placeholder = { Text("autoglm-phone") },
                        supportingText = { Text("Zhipu's Phone Control model name is autoglm-phone") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                }
            }
            RuntimeOutlinedButton(
                onClick = { viewModel.test(baseUrl, model, apiKey) { message = it } },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuntimeCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Testing…")
                    }
                } else {
                    Text("Test connection")
                }
            }
            RuntimeButton(
                onClick = {
                    val error = phoneAgentConfigError(baseUrl, model, apiKey)
                    if (error != null) {
                        message = error
                    } else {
                        viewModel.save(PhoneAgentEndpoint(baseUrl.trim(), model.trim(), apiKey.trim()))
                        message = "Saved. The main chat model does not use this configuration."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) { Text("Save") }
            RuntimeTextButton(
                onClick = {
                    baseUrl = ""
                    model = ""
                    apiKey = ""
                    viewModel.save(PhoneAgentEndpoint())
                    message = "Cleared"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear") }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun phoneAgentConfigError(baseUrl: String, model: String, apiKey: String): String? {
    val url = baseUrl.trim()
    if (url.isBlank() || model.isBlank() || apiKey.isBlank()) return "Endpoint URL, model name, and API key are all required"
    val local = url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
    if (!url.startsWith("https://") && !local) return "External endpoints must use https://"
    return null
}
