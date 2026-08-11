package me.rerere.rikkahub.ui.pages.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.account.components.BalanceCard
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun AccountPage(vm: AccountVM = koinViewModel()) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    var showLogoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.account_page_title)) },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BalanceCard(
                profile = profile,
                refreshing = refreshing,
                onTopUp = { navController.navigate(Screen.Redeem) },
            )

            Card(colors = CustomColors.cardColors) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.account_history_row)) },
                    supportingContent = { Text(stringResource(R.string.account_history_row_desc)) },
                    colors = CustomColors.listItemColors,
                    modifier = Modifier.clickable { navController.navigate(Screen.Redeem) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.tutorial_page_title)) },
                    supportingContent = { Text(stringResource(R.string.account_tutorial_row_desc)) },
                    colors = CustomColors.listItemColors,
                    modifier = Modifier.clickable { navController.navigate(Screen.Tutorial) },
                )
            }

            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.account_logout),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.account_logout)) },
            text = { Text(stringResource(R.string.account_logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    vm.logout { }
                }) {
                    Text(
                        stringResource(R.string.account_logout_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.account_logout_cancel))
                }
            },
        )
    }
}
