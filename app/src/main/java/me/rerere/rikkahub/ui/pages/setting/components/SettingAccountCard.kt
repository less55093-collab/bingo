package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.rikkahub.ui.pages.account.AccountVM
import me.rerere.rikkahub.ui.pages.account.formatBalance
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAccountCard(
    onClickAccount: () -> Unit,
    onClickRedeem: () -> Unit,
    modifier: Modifier = Modifier,
    vm: AccountVM = koinViewModel(),
) {
    val profile by vm.profile.collectAsStateWithLifecycle()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColors,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClickAccount)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    HugeIcons.UserCircle,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = profile?.email ?: "未登录",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "剩余额度 ${formatBalance(profile?.balance ?: 0.0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilledTonalButton(onClick = onClickRedeem) {
                Text("充值")
            }
        }
    }
}
