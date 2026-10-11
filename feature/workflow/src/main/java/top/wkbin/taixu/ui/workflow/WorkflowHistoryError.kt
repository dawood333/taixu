package top.wkbin.taixu.ui.workflow

/** Keep unsaved history visible until persistence succeeds, without replacing operation errors. */
internal fun workflowHistoryError(operationError: String?, historyErrors: Map<String, String>): String? =
    operationError ?: historyErrors.values.firstOrNull()?.let { "Failed to save workflow history: $it" }
