package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.model.gateway.GatewayGroup
import me.rerere.rikkahub.data.model.gateway.GatewayPurpose
import me.rerere.rikkahub.data.model.gateway.enforceGroupRestrictions
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import java.text.DateFormat
import java.util.Date

@Composable
fun GatewaySettings(vm: SettingVM) {
    val sync by vm.gatewaySync.collectAsStateWithLifecycle()
    val savedRouting by vm.gatewayRouting.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val routing = savedRouting.enforceGroupRestrictions()
    val chatGroups = GatewayPurpose.CHAT.availableGroups(sync.groups)
    val imageGroups = GatewayPurpose.IMAGE.availableGroups(sync.groups)
    LaunchedEffect(Unit) { vm.refreshGateway() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("分组与模型", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tooltip(tooltip = { Text("刷新分组和模型") }) {
                IconButton(onClick = vm::refreshGateway, enabled = !sync.loading) {
                    Icon(HugeIcons.Refresh01, contentDescription = "刷新分组和模型")
                }
            }
        }
        if (sync.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        GatewayGroupSelect(
            label = "聊天分组", groups = chatGroups, selected = routing.chat?.group,
            enabled = !sync.loading,
            onSelect = { vm.selectGatewayGroup(GatewayPurpose.CHAT, it.id) },
        )
        if (routing.chat?.models?.isNotEmpty() == true) {
            Text("聊天模型 · ${routing.chat!!.models.size} 个", style = MaterialTheme.typography.labelLarge)
            ModelSelector(
                modelId = settings.chatModelId, providers = settings.providers, type = ModelType.CHAT,
                modifier = Modifier.fillMaxWidth(),
                onSelect = { vm.selectGatewayModel(GatewayPurpose.CHAT, it.id) },
            )
        }
        GatewayGroupSelect(
            label = "生图分组", groups = imageGroups, selected = routing.image?.group,
            enabled = !sync.loading,
            onSelect = { vm.selectGatewayGroup(GatewayPurpose.IMAGE, it.id) },
        )
        if (routing.image?.models?.isNotEmpty() == true) {
            Text("生图模型 · ${routing.image!!.models.size} 个", style = MaterialTheme.typography.labelLarge)
            ModelSelector(
                modelId = settings.imageGenerationModelId, providers = settings.providers, type = ModelType.IMAGE,
                modifier = Modifier.fillMaxWidth(),
                onSelect = { vm.selectGatewayModel(GatewayPurpose.IMAGE, it.id) },
            )
            Text("上游目录未标注模型用途，已列出该分组全部模型。请选择支持生图的模型。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (!sync.loading) {
            if (sync.groups.isNotEmpty() || sync.error == null) {
                if (chatGroups.isEmpty()) {
                    Text("当前账号没有可用的聊天分组，请开通权限后刷新。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (imageGroups.isEmpty()) {
                    Text("当前账号没有可用的生图分组，请开通权限后刷新。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            listOf("聊天" to routing.chat, "生图" to routing.image).forEach { (name, binding) ->
                if (binding != null && binding.syncedAt > 0) {
                    val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(binding.syncedAt))
                    Text(if (binding.models.isEmpty()) "$name 分组未返回模型，可切换分组或稍后刷新。"
                        else "$name 最近同步：$at", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        sync.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun GatewayGroupSelect(
    label: String,
    groups: List<GatewayGroup>,
    selected: GatewayGroup?,
    enabled: Boolean,
    onSelect: (GatewayGroup) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled && groups.isNotEmpty()) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let { group ->
                group.displayName +
                    if (groups.none { it.id == group.id } && enabled) "（不可用，请重选）" else ""
            } ?: "请选择分组",
            onValueChange = {}, readOnly = true, enabled = enabled && groups.isNotEmpty(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.displayName) },
                    onClick = { expanded = false; onSelect(group) },
                )
            }
        }
    }
}
