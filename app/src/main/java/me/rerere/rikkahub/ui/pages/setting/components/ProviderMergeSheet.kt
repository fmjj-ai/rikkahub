package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderProtocol
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ProviderMergeOption
import me.rerere.rikkahub.data.datastore.buildProviderMergePlan
import me.rerere.rikkahub.data.datastore.mergeBaseUrl

@Composable
fun ProviderMergeSheet(
    groups: List<List<ProviderSetting>>,
    onDismiss: () -> Unit,
    onMerge: (ProviderSetting, List<ProviderSetting>, ProviderSetting.MultiProtocol) -> Unit,
) {
    var selectedGroup by remember { mutableStateOf<List<ProviderSetting>?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_merge_title),
                style = MaterialTheme.typography.titleLarge,
            )

            val group = selectedGroup
            if (group == null) {
                Text(
                    text = stringResource(R.string.setting_provider_page_merge_select_group),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                groups.forEach { providers ->
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedGroup = providers },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = providers.first().mergeBaseUrl(),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            Text(
                                text = providers.joinToString(" / ") { it.name.ifBlank { "?" } },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.setting_provider_page_merge_group_summary,
                                    providers.size,
                                    providers.sumOf { it.models.size },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                ProviderMergeConfirm(
                    group = group,
                    onMerge = onMerge,
                )
            }
        }
    }
}

@Composable
private fun ProviderMergeConfirm(
    group: List<ProviderSetting>,
    onMerge: (ProviderSetting, List<ProviderSetting>, ProviderSetting.MultiProtocol) -> Unit,
) {
    var primary by remember(group) { mutableStateOf(group.first()) }
    val others = remember(primary, group) { group.filter { it.id != primary.id } }
    val plan = remember(primary, group) { buildProviderMergePlan(primary, others) }
    var selections by remember(primary) {
        mutableStateOf<Map<ProviderProtocol, ProviderMergeOption>>(emptyMap())
    }

    Text(
        text = stringResource(R.string.setting_provider_page_merge_primary),
        style = MaterialTheme.typography.titleSmall,
    )
    group.forEach { provider ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { primary = provider }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(
                selected = primary.id == provider.id,
                onClick = { primary = provider },
            )
            Column(modifier = Modifier.weight(1f)) {
                val displayName = provider.name.ifBlank {
                    stringResource(R.string.setting_provider_page_merge_unnamed)
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = provider.mergeProtocols().joinToString(" · ") { it.displayName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    plan.conflicts.forEach { conflict ->
        Text(
            text = stringResource(
                R.string.setting_provider_page_merge_conflict,
                conflict.protocol.displayName,
            ),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        val selectedOption = selections[conflict.protocol] ?: conflict.options.first()
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            conflict.options.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, conflict.options.size),
                    selected = selectedOption.source.id == option.source.id,
                    onClick = { selections = selections + (conflict.protocol to option) },
                    label = {
                        val label = option.source.name.ifBlank {
                            stringResource(R.string.setting_provider_page_merge_unnamed)
                        }
                        Text(label)
                    },
                )
            }
        }
    }

    val mergeResult = runCatching { plan.merged(selections) }
    val merged = mergeResult.getOrNull()
    mergeResult.exceptionOrNull()?.let {
        Text(it.message ?: "模型配置冲突，不能合并", color = MaterialTheme.colorScheme.error)
    }
    Text(
        text = stringResource(
            R.string.setting_provider_page_merge_summary,
            group.size,
            merged?.models?.size ?: 0,
            group.size - 1,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            enabled = merged != null,
            onClick = {
                merged?.let { onMerge(primary, others, it) }
            },
        ) {
            Text(stringResource(R.string.setting_provider_page_merge_confirm))
        }
    }
}

private fun ProviderSetting.mergeProtocols(): List<ProviderProtocol> = when (this) {
    is ProviderSetting.Account -> emptyList()
    is ProviderSetting.MultiProtocol -> configuredProtocols()
    is ProviderSetting.OpenAI -> listOf(ProviderProtocol.OPENAI)
    is ProviderSetting.Google -> listOf(ProviderProtocol.GOOGLE)
    is ProviderSetting.Claude -> listOf(ProviderProtocol.CLAUDE)
}
