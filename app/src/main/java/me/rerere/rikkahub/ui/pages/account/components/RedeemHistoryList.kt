package me.rerere.rikkahub.ui.pages.account.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.gateway.RedeemHistoryItem
import me.rerere.rikkahub.ui.pages.account.formatRedeemValue
import me.rerere.rikkahub.ui.pages.account.redeemTypeLabel

@Composable
fun RedeemHistoryList(
    items: List<RedeemHistoryItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.redeem_history_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 8.dp),
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider()
            RedeemHistoryRow(item)
        }
    }
}

@Composable
private fun RedeemHistoryRow(item: RedeemHistoryItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = redeemTypeLabel(item.type)?.let { stringResource(it) } ?: item.type,
                style = MaterialTheme.typography.bodyMedium,
            )
            item.usedAt?.takeIf { it.isNotBlank() }?.let { usedAt ->
                Text(
                    text = formatUsedAt(usedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "+${formatRedeemValue(item.type, item.value)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Timestamps arrive as RFC3339. Rather than pull in a parser for a display-only string, trim to
 * minute precision and fall back to the raw value if the shape is not what we expect.
 */
private fun formatUsedAt(raw: String): String {
    val normalized = raw.replace('T', ' ')
    val cut = normalized.indexOf('.').takeIf { it > 0 } ?: normalized.length
    return normalized.take(cut).take(16).ifBlank { raw }
}
