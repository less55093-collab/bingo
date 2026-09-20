package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.account.formatBalance

@Composable
fun InsufficientBalanceOverlay(
    onBuy: () -> Unit,
    onRedeem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.52f))
            .pointerInput(Unit) {}
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "额度已用完",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "没额度时无法同步模型，也没法继续聊天或生图。充值后即可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("怎么充值", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("1. 点「去购买」打开商店，下单兑换码。", style = MaterialTheme.typography.bodyMedium)
                    Text("2. 复制兑换码，回到 App 点「去充值」。", style = MaterialTheme.typography.bodyMedium)
                    Text("3. 粘贴兑换，到账后就能继续用。", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onBuy, modifier = Modifier.fillMaxWidth()) {
                    Text("去购买")
                }
                OutlinedButton(onClick = onRedeem, modifier = Modifier.fillMaxWidth()) {
                    Text("去充值")
                }
            }
        }
    }
}

@Composable
fun WelcomeCreditDialog(
    balance: Double,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("欢迎使用 Bingo") },
        text = {
            Text("当前剩余额度 ${formatBalance(balance)}。用完后按充值教程买兑换码，即可继续聊天和生图。")
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("开始使用") }
        },
    )
}

@Composable
fun ChatCoachDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("怎么换模型和分组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. 点输入框左边的模型图标，列表里分成「聊天模型」和「生图模型」，都能切换。选生图模型会在聊天里直接生图。")
                Text("2. 要换分组：打开设置 → 分组与模型，聊天和生图可以分开选。")
                Text("右边「专业生图」会进入更完整的生图页，可调尺寸、参考图和作品。")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = {
            TextButton(onClick = onOpenSettings) { Text("去设置") }
        },
    )
}

internal const val PREF_WELCOME_CREDIT_SHOWN = "welcome_credit_shown"
internal const val PREF_CHAT_COACH_SHOWN = "chat_coach_shown"
