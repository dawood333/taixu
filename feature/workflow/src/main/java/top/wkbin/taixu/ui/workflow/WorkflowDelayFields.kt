package top.wkbin.taixu.ui.workflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.wkbin.taixu.ui.components.RuntimeTextButton

@Composable
internal fun WorkflowDelayFields(config: MutableMap<String, String>) {
    val milliseconds = "milliseconds" in config
    val key = if (milliseconds) "milliseconds" else "seconds"
    Column {
        OutlinedTextField(
            value = config[key] ?: config["delaySeconds"] ?: "1",
            onValueChange = { config[key] = it },
            label = { Text(if (milliseconds) "Wait duration (ms)" else "Wait duration (seconds)") },
            supportingText = { Text(if (milliseconds) "Integer from 0 to 600000; supports \${VAR}" else "0–600 seconds; decimals and \${VAR} are supported") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        RuntimeTextButton(onClick = {
            val raw = config[key] ?: config["delaySeconds"] ?: "1"
            val converted = if (milliseconds) raw.toLongOrNull()?.let { (it / 1000.0).toString() }
                else raw.toDoubleOrNull()?.let { (it * 1000).toLong().toString() }
            config.remove(key)
            config.remove("delaySeconds")
            config[if (milliseconds) "seconds" else "milliseconds"] = converted ?: raw
        }, enabled = (config[key] ?: config["delaySeconds"] ?: "1").toDoubleOrNull()?.isFinite() == true) {
            Text(if (milliseconds) "Switch to seconds (value converted automatically)" else "Switch to milliseconds (value converted automatically)")
        }
    }
}
