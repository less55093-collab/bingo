package me.rerere.rikkahub.ui.pages.account.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.gateway.UserProfile
import me.rerere.rikkahub.ui.pages.account.formatBalance
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun BalanceCard(
    profile: UserProfile?,
    refreshing: Boolean,
    onTopUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.MoneyBag02,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.balance_remaining),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }

            Text(
                text = profile?.let { formatBalance(it.balance) } ?: "--",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )

            profile?.email?.takeIf { it.isNotBlank() }?.let { email ->
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onTopUp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.balance_top_up))
            }
        }
    }
}

/**
 * Shown in the chat input area when the balance can no longer cover a normal exchange. Insufficient
 * balance is the most common failure users will hit, and the raw upstream error is unreadable, so it
 * is worth warning before the request rather than explaining after.
 */
@Composable
fun LowBalanceBanner(
    onTopUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.balance_insufficient_banner),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onTopUp) {
                Text(
                    stringResource(R.string.balance_insufficient_action),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
