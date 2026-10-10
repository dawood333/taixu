package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.core.database.AndroidAppEntity
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.settings.LocalizedText as Text

@Composable
fun AppManagementScreen(
    onBack: () -> Unit,
    viewModel: AppManagementViewModel = koinViewModel(),
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    var showSystemApps by remember { mutableStateOf(false) }

    // Every entry reconciles the live PackageManager inventory with Room: missing packages are
    // removed and newly installed ones are inserted, without rebuilding the UI's data source.
    LaunchedEffect(Unit) { viewModel.synchronize() }
    val userApps = apps.filterNot { it.isSystemApp }
    val systemApps = apps.filter { it.isSystemApp }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("App Management", onBack, statusText = if (syncing) "Syncing" else "Standard access · enhanced with privileges") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RuntimeCard(contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Managed app index", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "All modes can read apps and store them in Room; subsequent visits compare package names incrementally. Shizuku/Root also reads frozen and background-network restriction states. Operations such as freezing or granting permissions require privileges and will report insufficient permissions in PRoot mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RuntimeButton(onClick = viewModel::synchronize, enabled = !syncing) {
                                if (syncing) RuntimeCircularProgressIndicator(Modifier.size(18.dp)) else RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp))
                                Text(if (syncing) "Syncing" else "Sync now", modifier = Modifier.padding(start = 8.dp))
                            }
                            Text("${apps.size} total", style = MaterialTheme.typography.labelLarge)
                        }
                        message?.let {
                            Text(it, color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 纯计数标签：不可交互，避免伪装成可点击的 FilterChip
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
                        Text(
                            "User apps (${userApps.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    FilterChip(
                        selected = showSystemApps,
                        onClick = { showSystemApps = !showSystemApps },
                        label = { Text("Show system apps (${systemApps.size})") },
                    )
                }
            }
            if (userApps.isEmpty() && !syncing) {
                item { Text("No app data yet. Run a sync first.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(userApps, key = { it.packageName }) { app -> AndroidAppRow(app) }
            if (showSystemApps && systemApps.isNotEmpty()) {
                item {
                    Text(
                        "System apps",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(systemApps, key = { it.packageName }) { app -> AndroidAppRow(app) }
            }
        }
    }
}

@Composable
private fun AndroidAppRow(app: AndroidAppEntity) {
    RuntimeCard(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AndroidAppIcon(app.packageName)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (app.isEnabled) "Enabled" else "Frozen",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (app.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (app.isNetworkRestricted) {
                    // 「Frozen」已由上方状态文案表达，此处只补充Network restricted信息，避免重复
                    Text(
                        "Network restricted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AndroidAppIcon(packageName: String) {
    val context = LocalContext.current
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (drawable == null) {
            RuntimeIcon(RuntimeIconName.Package, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AndroidView(
                factory = {
                    ImageView(it).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = packageName
                        setImageDrawable(drawable)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { image -> image.setImageDrawable(drawable) },
            )
        }
    }
}
