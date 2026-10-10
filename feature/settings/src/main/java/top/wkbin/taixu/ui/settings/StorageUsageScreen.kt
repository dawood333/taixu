package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.runtime.StorageCategory
import top.wkbin.taixu.runtime.StorageEntry
import top.wkbin.taixu.runtime.StorageRiskLevel
import top.wkbin.taixu.runtime.StorageUsage
import top.wkbin.taixu.ui.components.IconTile
import top.wkbin.taixu.ui.components.NoticeBanner
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeFilledTonalButton
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton
import top.wkbin.taixu.ui.components.RuntimeTextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar
import java.util.Locale

/** Keep diagnostic paths out of the main storage overview. */
@Composable
private fun StorageScanNotice(warnings: List<String>) {
    var showDetails by remember(warnings) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuntimeIcon(
            name = RuntimeIconName.Info,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Some directories were not included",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        RuntimeTextButton(onClick = { showDetails = true }) {
            Text("Details", style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
    if (showDetails) {
        RuntimeAlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Scan details") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Some directories could not be read completely, so the displayed usage may be lower than the actual amount. Details recorded during the scan (up to 20 entries):")
                    warnings.forEach { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                RuntimeTextButton(onClick = { showDetails = false }) { Text("Close") }
            },
        )
    }
}

@Composable
fun StorageUsageScreen(
    onBack: () -> Unit,
    viewModel: StorageUsageViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val usage = uiState.usage
    val refreshing = uiState.refreshing
    val cleaningAction = uiState.cleaningAction
    val message = uiState.message
    val messageIsError = uiState.messageIsError
    val activeFilter = uiState.activeFilter
    val dialogTarget = uiState.dialogTarget
    val filteredCategories = uiState.filteredCategories

    dialogTarget?.let { target ->
        when (target) {
            is CleanupDialogTarget.QuickSafe -> {
                RuntimeAlertDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    title = { Text("Clean up archived logs?") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Clean archived logs older than 7 days that have not changed since the scan:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("• Historical logs in .log.gz and .log.N formats")
                            Text("• Current logs, installation assets, and rollback backups will be kept")
                            Spacer(Modifier.height(4.dp))
                            Text("Historical diagnostic information will be deleted. Close terminal sessions and stop running tasks first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    confirmButton = {
                        RuntimeButton(
                            onClick = viewModel::quickSafeClean,
                        ) {
                            Text("Clean now")
                        }
                    },
                    dismissButton = {
                        RuntimeTextButton(onClick = viewModel::dismissDialog) {
                            Text("Cancel")
                        }
                    },
                )
            }

            is CleanupDialogTarget.AllProjectBuilds -> {
                RuntimeAlertDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    title = { Text("Clean recognized project caches?") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Only cache files older than 7 days and unchanged in recognized projects will be processed:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("• .gradle in Gradle projects and .dart_tool in Dart/Flutter projects")
                            Spacer(Modifier.height(4.dp))
                            Text("• build, dist, out, target, and unrecognized directories remain managed by the workspace.", color = MaterialTheme.colorScheme.primary)
                            Text("• The next build must regenerate caches and may need to run pub get. Stop running tasks first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = {
                        RuntimeButton(
                            onClick = { viewModel.cleanProjectBuilds() },
                        ) {
                            Text("Confirm cleanup")
                        }
                    },
                    dismissButton = {
                        RuntimeTextButton(onClick = viewModel::dismissDialog) {
                            Text("Cancel")
                        }
                    },
                )
            }

            is CleanupDialogTarget.CategoryTarget -> {
                val category = target.category
                RuntimeAlertDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    title = { Text("Clean category “${category.name}”?") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Estimated deletion: ${category.reclaimableBytes.readableSize()} (logical file size)")
                            Text(category.cleanupHint ?: "Clean the eligible data in this category?")
                            category.entries.filter { it.cleanable }.forEach { item ->
                                Text("• ${item.name}：${item.reclaimableBytes.readableSize()}")
                            }
                            if (category.riskLevel == StorageRiskLevel.CAUTION) {
                                Text("Note: caches must be downloaded or generated again, which may increase the next build time and affect offline use.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD97706))
                            } else if (category.riskLevel == StorageRiskLevel.DANGEROUS) {
                                Text("Warning: This action cannot be undone. Make sure your data is backed up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    confirmButton = {
                        RuntimeButton(
                            onClick = { viewModel.clearCategory(category.id) },
                        ) {
                            Text("Confirm cleanup")
                        }
                    },
                    dismissButton = {
                        RuntimeTextButton(onClick = viewModel::dismissDialog) {
                            Text("Cancel")
                        }
                    },
                )
            }

            is CleanupDialogTarget.EntryTarget -> {
                val entry = target.entry
                RuntimeAlertDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    title = { Text("Clean “${entry.name}”?") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Estimated deletion: ${entry.reclaimableBytes.readableSize()} (logical file size)")
                            Text(entry.cleanupHint ?: "Clean this item?")
                            entry.path?.let {
                                Text("Target path: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (entry.riskLevel == StorageRiskLevel.DANGEROUS) {
                                Text("Warning: This will permanently delete the selected data and cannot be undone!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    confirmButton = {
                        RuntimeButton(
                            onClick = { viewModel.clearEntry(target.categoryId, entry.id, entry.name) },
                        ) {
                            Text("Confirm cleanup")
                        }
                    },
                    dismissButton = {
                        RuntimeTextButton(onClick = viewModel::dismissDialog) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "Storage Management",
                onBack = onBack,
                statusText = usage?.totalManagedBytes?.readableSize() ?: "Calculating…",
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. 顶部存储看板与多段占比仪表条
            item(key = "header_dashboard") {
                usage?.let {
                    StorageDashboardCard(
                        usage = it,
                        refreshing = refreshing,
                        onRefresh = viewModel::refresh,
                    )
                } ?: RuntimeCard(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RuntimeCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            "Analyzing Linux sandbox, dependency caches, and workspace storage…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            usage?.scanWarnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
                item(key = "scan_warnings") {
                    StorageScanNotice(warnings)
                }
            }
            usage?.excludedMountPointCount?.takeIf { it > 0 }?.let { count ->
                item(key = "excluded_mount_points") {
                    Text(
                        "Skipped $count restricted mount points; workspace files and attachments are counted from their actual directories.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 2. 智能Clean建议卡片（安全释放 + 谨慎释放）
            usage?.let { u ->
                if (u.safeCleanableBytes > 0 || u.projectCleanableBytes > 0) {
                    item(key = "smart_cleanup_suggestions") {
                        SmartCleanupCards(
                            safeCleanableBytes = u.safeCleanableBytes,
                            cautionCleanableBytes = u.projectCleanableBytes,
                            cleaningAction = cleaningAction,
                            onQuickSafeClean = { viewModel.openDialog(CleanupDialogTarget.QuickSafe) },
                            onCleanProjectBuilds = { viewModel.openDialog(CleanupDialogTarget.AllProjectBuilds) },
                        )
                    }
                }
            }

            // 3. 全局反馈提示横幅
            message?.let {
                item(key = "global_notice_banner") {
                    NoticeBanner(it, isError = messageIsError, onDismiss = viewModel::dismissMessage)
                }
            }

            // 4. 分类视图筛选 Chip 栏
            item(key = "filter_chips") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Category details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(StorageFilter.entries) { filter ->
                        FilterChip(
                            selected = activeFilter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }

            // 5. 过滤后的分类卡片列表与细化折叠树
            val categories = usage?.categories.orEmpty()
            if (filteredCategories.isEmpty() && usage != null) {
                item(key = "empty_filter") {
                    RuntimeCard(Modifier.fillMaxWidth()) {
                        Text(
                            "No data in the selected category",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(filteredCategories, key = { it.id }) { category ->
                    val catIndex = categories.indexOfFirst { it.id == category.id }.coerceAtLeast(0)
                    StorageCategoryDetailCard(
                        category = category,
                        color = categoryColor(catIndex),
                        cleaningAction = cleaningAction,
                        onCleanCategory = { viewModel.openDialog(CleanupDialogTarget.CategoryTarget(category)) },
                        onCleanEntry = { entry -> viewModel.openDialog(CleanupDialogTarget.EntryTarget(category.id, entry)) },
                    )
                }
            }

            item(key = "bottom_spacer") {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * 顶部存储看板与多段渐变占比仪表条（竖向深度占比分析）
 */
@Composable
private fun StorageDashboardCard(
    usage: StorageUsage,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) = RuntimeCard(Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                "Storage managed by TaiXu",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                usage.totalManagedBytes.readableSize(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        RuntimeOutlinedButton(
            onClick = onRefresh,
            enabled = !refreshing,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (refreshing) {
                RuntimeCircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Scanning", style = MaterialTheme.typography.labelSmall)
            } else {
                RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Rescan", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Calculated from file sizes and excludes the APK; it may differ from system storage usage. Potentially cleanable data includes only recognized files older than 7 days.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(14.dp))

    // 多段存储色彩条
    val visibleCategories = usage.categories.filter { it.bytes > 0L }
    val totalBytes = visibleCategories.sumOf { it.bytes }.coerceAtLeast(1L)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics {
                contentDescription = visibleCategories.joinToString("；") { cat ->
                    val pct = (cat.bytes * 100 / totalBytes)
                    "${cat.name}: $pct%"
                }
            },
    ) {
        if (visibleCategories.isEmpty()) {
            Spacer(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        } else {
            visibleCategories.forEachIndexed { index, category ->
                val weight = (category.bytes.toFloat() / totalBytes).coerceAtLeast(0.008f)
                Box(
                    modifier = Modifier
                        .weight(weight)
                        .fillMaxSize()
                        .padding(horizontal = 0.5.dp)
                        .background(categoryColor(index)),
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    // 竖向分类占比深度分析列表（每个分类独占一行，绝不折叠与截断）
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        visibleCategories.forEachIndexed { index, item ->
            val pct = (item.bytes.toDouble() / totalBytes * 100.0)
            val pctStr = if (pct >= 1.0) String.format(Locale.US, "%.1f%%", pct) else "< 1%"

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(categoryColor(index)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = item.bytes.readableSize(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Surface(
                            shape = CircleShape,
                            color = categoryColor(index).copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = pctStr,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = categoryColor(index),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 单项轻量视觉进度细条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (pct / 100.0).toFloat().coerceIn(0.01f, 1f))
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(categoryColor(index)),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Available device storage: ${usage.availableBytes.readableSize()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${usage.categories.size} managed categories",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 智能Clean建议卡片：安全Clean与谨慎Clean快捷入口（宽敞防挤压设计）
 */
@Composable
private fun SmartCleanupCards(
    safeCleanableBytes: Long,
    cautionCleanableBytes: Long,
    cleaningAction: String?,
    onQuickSafeClean: () -> Unit,
    onCleanProjectBuilds: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Eligible archived logs卡片
        if (safeCleanableBytes > 0) {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 上半部分：图标 + 标题/描述 + 醒目大字容量
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        IconTile(
                            icon = RuntimeIconName.Shield,
                            color = Color(0xFF10B981),
                            size = 40.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "Eligible archived logs",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Archived logs older than 7 days; current logs are retained",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            safeCleanableBytes.readableSize(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981),
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // 下半部分：保障标签 + 独立操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFE6F8F0),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Check,
                                    modifier = Modifier.size(12.dp),
                                    tint = Color(0xFF0E9F6E),
                                )
                                Text(
                                    "Low risk · does not affect source code or configuration",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF0E9F6E),
                                )
                            }
                        }

                        RuntimeButton(
                            onClick = onQuickSafeClean,
                            enabled = cleaningAction == null,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            if (cleaningAction == "quick_safe") {
                                RuntimeCircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Cleaning…", style = MaterialTheme.typography.labelMedium)
                            } else {
                                Text("Clean expired logs", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 2. Workspace build artifacts卡片
        if (cautionCleanableBytes > 0) {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 上半部分：图标 + 标题/描述 + 醒目大字容量
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        IconTile(
                            icon = RuntimeIconName.Code,
                            color = Color(0xFFF59E0B),
                            size = 40.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "Workspace build artifacts",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Old .gradle / .dart_tool caches in recognized projects",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            cautionCleanableBytes.readableSize(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B),
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // 下半部分：保障标签 + 独立操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFFFF4E5),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Info,
                                    modifier = Modifier.size(12.dp),
                                    tint = Color(0xFFB76E00),
                                )
                                Text(
                                    "Use caution · regenerated during builds",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFB76E00),
                                )
                            }
                        }

                        RuntimeFilledTonalButton(
                            onClick = onCleanProjectBuilds,
                            enabled = cleaningAction == null,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            if (cleaningAction == "all_project_builds") {
                                RuntimeCircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Cleaning…", style = MaterialTheme.typography.labelMedium)
                            } else {
                                Text("Clean project caches", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分类Details卡片：宽松大气的两段式布局（上层标题与占用量，下层风险徽标与操作按钮）
 */
@Composable
private fun StorageCategoryDetailCard(
    category: StorageCategory,
    color: Color,
    cleaningAction: String?,
    onCleanCategory: () -> Unit,
    onCleanEntry: (StorageEntry) -> Unit,
) {
    var expanded by remember(category.id) { mutableStateOf(false) }
    val isCategoryCleaning = cleaningAction == "category_${category.id}"

    RuntimeCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (category.entries.isNotEmpty()) expanded = !expanded },
    ) {
        // 顶部行：图标 + 标题/描述 (拥有 100% 完整水平空间) + 大小 + 展开箭头
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = categoryIcon(category.id),
                color = color,
                size = 42.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Potentially cleanable ${category.reclaimableBytes.readableSize()} · ${category.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = category.bytes.readableSize(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (category.entries.isNotEmpty()) {
                    RuntimeIcon(
                        name = if (expanded) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 底部状态与操作行：风险等级标签（完整不换行） + Clean按钮
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RiskBadge(category.riskLevel)

            if (category.cleanable && category.bytes > 0) {
                RuntimeFilledTonalButton(
                    onClick = onCleanCategory,
                    enabled = cleaningAction == null,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    if (isCategoryCleaning) {
                        RuntimeCircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = if (category.id == "package_cache") "Clean now" else "Clean category",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // 折叠展开的明细项列表
        AnimatedVisibility(
            visible = expanded && category.entries.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                category.entries.forEachIndexed { index, entry ->
                    StorageEntryRow(
                        entry = entry,
                        cleaningAction = cleaningAction,
                        onClean = { onCleanEntry(entry) },
                    )
                    if (index < category.entries.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 细化项目单行展现 (包括代码工程与构建产物、SDK 子项等)
 */
@Composable
private fun StorageEntryRow(
    entry: StorageEntry,
    cleaningAction: String?,
    onClean: () -> Unit,
) {
    val isEntryCleaning = cleaningAction == "entry_${entry.id}" || cleaningAction == "project_build_${entry.name}"

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        // 主条目行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Potentially cleanable ${entry.reclaimableBytes.readableSize()} · ${entry.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    entry.bytes.readableSize(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (entry.cleanable && entry.bytes > 0) {
                    RuntimeFilledTonalButton(
                        onClick = onClean,
                        enabled = cleaningAction == null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        if (isEntryCleaning) {
                            RuntimeCircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                "Clean",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (entry.riskLevel) {
                                    StorageRiskLevel.SAFE -> Color(0xFF10B981)
                                    StorageRiskLevel.CAUTION -> Color(0xFFD97706)
                                    StorageRiskLevel.DANGEROUS -> MaterialTheme.colorScheme.error
                                    StorageRiskLevel.READONLY -> MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    }
                }
            }
        }

        // 次级细项卡片（例如工程下的源码资产 vs 编译构建产物，或者 Android SDK 下的 components）
        if (entry.subItems.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entry.subItems.forEach { sub ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (sub.cleanable) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    sub.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                sub.bytes.readableSize(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (sub.cleanable) Color(0xFFD97706) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 风险等级标签：绝不垂直折行，色彩清晰优雅
 */
@Composable
private fun RiskBadge(riskLevel: StorageRiskLevel, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (riskLevel) {
        StorageRiskLevel.SAFE -> Triple(
            "Archived logs · eligible for cleanup",
            Color(0xFFE6F8F0),
            Color(0xFF0E9F6E),
        )
        StorageRiskLevel.CAUTION -> Triple(
            "Clean as needed · caches may need to be restored",
            Color(0xFFFFF4E5),
            Color(0xFFB76E00),
        )
        StorageRiskLevel.DANGEROUS -> Triple(
            "High risk · persistent data",
            Color(0xFFFFECEC),
            Color(0xFFDC2626),
        )
        StorageRiskLevel.READONLY -> Triple(
            "Keep · managed by the owning feature",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Surface(
        shape = CircleShape,
        color = bg,
        modifier = modifier,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun categoryIcon(categoryId: String): RuntimeIconName = when (categoryId) {
    "environment" -> RuntimeIconName.Wrench
    "package_cache" -> RuntimeIconName.Package
    "projects" -> RuntimeIconName.Code
    "linux_system" -> RuntimeIconName.Linux
    "plugins" -> RuntimeIconName.Extension
    "runtimes" -> RuntimeIconName.Cpu
    "user_home" -> RuntimeIconName.Home
    "logs" -> RuntimeIconName.Logs
    "cache" -> RuntimeIconName.Download
    "conversations" -> RuntimeIconName.Attach
    "database" -> RuntimeIconName.Storage
    "app_data" -> RuntimeIconName.Tune
    else -> RuntimeIconName.Storage
}

private fun categoryColor(index: Int): Color = listOf(
    Color(0xFF3B82F6), // 亮蓝 (SDK 工具链)
    Color(0xFF10B981), // 翠绿 (包管理与依赖构建缓存)
    Color(0xFFF59E0B), // 琥珀黄 (工作区项目工程)
    Color(0xFF8B5CF6), // 紫罗兰 (Linux 基础系统)
    Color(0xFF06B6D4), // 青蓝 (插件与扩展生态)
    Color(0xFFEC4899), // 桃红 (共享运行时)
    Color(0xFF64748B), // 蓝灰 (用户家目录)
    Color(0xFF14B8A6), // 蓝绿 (日志)
    Color(0xFFF97316), // 橙色 (下载缓存)
    Color(0xFF6366F1), // 靛蓝 (附件与Skills)
    Color(0xFF84CC16), // 黄绿 (数据库)
    Color(0xFFD97706), // 棕黄 (应用配置)
)[index % 12]

private fun Long.readableSize(): String = when {
    this < 1024L -> "$this B"
    this < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", this / 1024.0)
    this < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", this / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", this / (1024.0 * 1024 * 1024))
}



