package top.wkbin.taixu.ui.settings.stats

import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.core.model.StatsDateRangePreset
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeFilterChip
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeLinearProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.settings.stats.widgets.StatsHeatmap
import top.wkbin.taixu.ui.settings.stats.widgets.StatsMetricGrid
import top.wkbin.taixu.ui.settings.stats.widgets.StatsRankSection
import top.wkbin.taixu.ui.settings.stats.widgets.StatsSectionCard
import top.wkbin.taixu.ui.settings.stats.widgets.StatsUsageChart

@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "Usage statistics and analytics",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 顶部时间范围筛选胶囊
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val presets = listOf(
                    StatsDateRangePreset.ALL_TIME to "All time",
                    StatsDateRangePreset.LAST_30_DAYS to "Last 30 days",
                    StatsDateRangePreset.PREVIOUS_MONTH to "Previous month",
                    StatsDateRangePreset.PREVIOUS_QUARTER to "Previous quarter",
                )

                for ((preset, label) in presets) {
                    val selected = uiState.range.preset == preset
                    RuntimeFilterChip(
                        selected = selected,
                        onClick = { viewModel.setDateRangePreset(preset) },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                ),
                            )
                        },
                    )
                }
            }

            if (uiState.isLoading) {
                RuntimeLinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 加载失败：错误态 + Retry入口（复用模块内 RuntimeCard + 按钮模式）
            uiState.error?.let { errorMessage ->
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            RuntimeButton(
                                onClick = { viewModel.refresh() },
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            // 加载完成但没有任何数据：给出空态而非整页空白
            if (uiState.error == null && !uiState.isLoading && uiState.snapshot?.heatmap.isNullOrEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No statistics yet. Activity and token usage will appear here after you use Agent conversations and tools.",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val snapshot = uiState.snapshot
            if (snapshot != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 1. Activity heatmap
                    item {
                        StatsSectionCard(title = "Activity heatmap") {
                            StatsHeatmap(days = snapshot.heatmap)
                        }
                    }

                    // 2. Key metrics overview 6 宫格
                    item {
                        StatsSectionCard(title = "Key metrics overview") {
                            StatsMetricGrid(summary = snapshot.summary)
                        }
                    }

                    // 3. Token 用量趋势图表
                    item {
                        StatsSectionCard(title = "Token usage and consumption trends") {
                            StatsUsageChart(days = snapshot.trend)
                        }
                    }

                    // 4. Model usage ranking
                    item {
                        StatsRankSection(
                            title = "Model usage ranking",
                            leftHeader = "Model",
                            rightHeader = "Calls",
                            items = snapshot.modelRank,
                            icon = RuntimeIconName.Brain,
                        )
                    }

                    // 5. 助手 / 工程工作区排行
                    item {
                        StatsRankSection(
                            title = "Assistant and project ranking",
                            leftHeader = "Workspace / Assistant",
                            rightHeader = "Usage frequency",
                            items = snapshot.assistantRank,
                            icon = RuntimeIconName.Bot,
                        )
                    }

                    // 6. 热门话题排行
                    item {
                        StatsRankSection(
                            title = "Popular conversation topics",
                            leftHeader = "Conversation",
                            rightHeader = "Messages",
                            items = snapshot.topicRank,
                            icon = RuntimeIconName.Chat,
                        )
                    }
                }
            }
        }
    }
}
