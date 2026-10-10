package top.wkbin.taixu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.database.AndroidAppEntity
import top.wkbin.taixu.core.database.AndroidAppRepository
import top.wkbin.taixu.runtime.apps.AndroidAppManager

class AppManagementViewModel(
    repository: AndroidAppRepository,
    private val appManager: AndroidAppManager,
) : ViewModel() {
    val apps: StateFlow<List<AndroidAppEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Whether the current message represents a failure (typed flag, so the UI does not infer styling from message text). */
    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    fun synchronize() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            val result = appManager.synchronize()
            _syncing.value = false
            _message.value = result.fold(
                onSuccess = { "Synced ${it.total} apps: ${it.userApps} user apps and ${it.systemApps} system apps." },
                onFailure = { it.message ?: "App synchronization failed" },
            )
            _messageIsError.value = result.isFailure
        }
    }

    fun consumeMessage() { _message.value = null }
}
