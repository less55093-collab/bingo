package me.rerere.rikkahub.ui.pages.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.account.components.RedeemHistoryList
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.getText
import me.rerere.rikkahub.utils.openUrl
import org.koin.androidx.compose.koinViewModel

@Composable
fun RedeemPage(vm: RedeemVM = koinViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipboardEmptyMessage = stringResource(R.string.redeem_clipboard_empty)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.redeem_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.redeem_code_hint),
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = state.code,
                onValueChange = vm::setCode,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
                label = { Text(stringResource(R.string.redeem_code_label)) },
                singleLine = true,
                isError = state.codeError != null,
                supportingText = if (state.codeError != null) {
                    { Text(state.codeError!!) }
                } else {
                    null
                },
                trailingIcon = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val text = clipboard.getClipEntry()
                                    ?.clipData
                                    ?.getText()
                                if (text.isNullOrBlank()) {
                                    toaster.show(clipboardEmptyMessage, type = ToastType.Info)
                                } else {
                                    vm.setCode(text)
                                }
                            }
                        },
                        enabled = !state.submitting,
                    ) {
                        Text(stringResource(R.string.redeem_paste))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit(vm, toaster, context) }),
            )

            Button(
                onClick = { submit(vm, toaster, context) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSubmit,
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.redeem_submit))
                }
            }

            OutlinedButton(
                onClick = { context.openUrl(BingoGatewayAPI.SHOP_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.redeem_buy_prompt))
            }

            TextButton(
                onClick = { navController.navigate(Screen.Tutorial) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.redeem_view_tutorial))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.redeem_history_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.historyLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }

            RedeemHistoryList(items = state.history)

            Text(
                text = stringResource(R.string.redeem_hint_one_time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun submit(
    vm: RedeemVM,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context,
) {
    vm.redeem { result ->
        val unit = redeemTypeLabel(result.type)?.let { context.getString(it) } ?: result.type
        toaster.show(
            message = context.getString(
                R.string.redeem_success_toast,
                formatRedeemValue(result.type, result.value),
                unit,
            ),
            type = ToastType.Success,
        )
    }
}
