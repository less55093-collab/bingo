package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json

internal class ResponseProtocolException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Item IDs address streamed output; call IDs address tool results sent back to the model.
 * Keep those identities separate and publish tools only after the whole response completes.
 * Final snapshots replace argument deltas, including lost, duplicated or reordered deltas.
 */
internal class ResponseToolCallAccumulator {
    private data class Call(
        val itemIds: MutableSet<String> = mutableSetOf(),
        var callId: String? = null,
        var outputIndex: Int? = null,
        var name: String? = null,
        var arguments: String? = null,
        var snapshotRank: Int = -1,
        val deltas: MutableList<Pair<Long?, String>> = mutableListOf(),
    )

    private val calls = mutableListOf<Call>()
    private val sequences = mutableMapOf<Long, JsonObject>()
    private var finalized = false

    /** Sequence numbers are event identities, not positions in an individual argument string. */
    fun isNewEvent(event: JsonObject): Boolean {
        val sequence = (event["sequence_number"] as? JsonPrimitive)?.longOrNull ?: return true
        val previous = sequences.putIfAbsent(sequence, event) ?: return true
        check(previous == event) { "模型返回了冲突的 Responses 事件序号：$sequence" }
        return false
    }

    /** Returns true when this event belongs to the function-call assembler. */
    fun accept(event: JsonObject): Boolean {
        check(!finalized) { "Responses 工具调用已经完成" }
        return when (event.string("type")) {
            "response.output_item.added", "response.output_item.done" -> {
                val item = event["item"] as? JsonObject ?: return false
                if (item.string("type") != "function_call") return false
                acceptItem(item, event.index(), if (event.string("type")!!.endsWith(".done")) 2 else 0)
                true
            }

            "response.function_call_arguments.delta" -> {
                val call = resolve(event.string("item_id"), event.string("call_id"), event.index())
                val delta = event.string("delta") ?: error("模型返回的工具参数分片缺少 delta")
                call.deltas += (event["sequence_number"] as? JsonPrimitive)?.longOrNull to delta
                true
            }

            "response.function_call_arguments.done" -> {
                val call = resolve(event.string("item_id"), event.string("call_id"), event.index())
                mergeName(call, event.string("name"))
                snapshot(call, event.string("arguments") ?: error("模型返回的工具参数缺少 arguments"), 1)
                true
            }

            else -> false
        }
    }

    fun finish(response: JsonObject? = null): List<UIMessagePart.Tool> {
        if (finalized) return emptyList()
        val outputValue = response?.get("output")
        if (outputValue != null) {
            val outputs = outputValue as? JsonArray ?: error("模型返回的 Responses output 不是数组")
            val finalCalls = outputs.mapIndexedNotNull { index, value ->
                val item = value as? JsonObject ?: error("模型返回的 Responses output 条目无效")
                if (item.string("type") == "function_call") acceptItem(item, index, 3) else null
            }.toSet()
            check(calls.all { it in finalCalls }) { "模型的最终响应遗漏了已开始的工具调用" }
        }

        val tools = calls.sortedWith(compareBy(nullsLast()) { it.outputIndex }).map { call ->
            val callId = call.callId?.takeIf(String::isNotBlank)
                ?: error("模型返回的工具调用缺少 call_id，尚未执行工具")
            val name = call.name?.takeIf(String::isNotBlank)
                ?: error("模型返回的工具调用缺少名称，尚未执行工具")
            val arguments = when {
                call.snapshotRank >= 1 -> call.arguments.orEmpty()
                call.deltas.isNotEmpty() -> {
                    val deltas = if (call.deltas.all { it.first != null }) {
                        call.deltas.sortedBy { it.first }
                    } else call.deltas
                    call.arguments.orEmpty() + deltas.joinToString("") { it.second }
                }
                else -> call.arguments.orEmpty()
            }
            check(runCatching { json.parseToJsonElement(arguments) is JsonObject }.getOrDefault(false)) {
                "模型返回的 $name 工具参数不是完整的 JSON 对象，尚未执行工具"
            }
            UIMessagePart.Tool(toolCallId = callId, toolName = name, input = arguments)
        }
        check(tools.map { it.toolCallId }.distinct().size == tools.size) { "模型返回了冲突的工具调用 ID" }
        finalized = true
        return tools
    }

    private fun acceptItem(item: JsonObject, index: Int?, rank: Int): Call {
        val call = resolve(item.string("id"), item.string("call_id"), index)
        mergeName(call, item.string("name"))
        val status = item.string("status")
        check(status !in setOf("failed", "cancelled", "incomplete")) { "模型的工具调用未完成：$status" }
        if (rank >= 2) {
            check(status == null || status == "completed") { "模型的最终工具调用仍未完成：$status" }
        }
        item.string("arguments")?.let { snapshot(call, it, rank) }
        if (rank >= 2) check(item.string("arguments") != null) { "模型的最终工具调用缺少 arguments" }
        return call
    }

    private fun resolve(itemId: String?, callId: String?, index: Int?): Call {
        check(!itemId.isNullOrBlank() || !callId.isNullOrBlank() || index != null) {
            "模型返回的工具调用缺少可关联的标识"
        }
        val matches = calls.filter { call ->
            (itemId != null && itemId in call.itemIds) ||
                (callId != null && callId == call.callId) ||
                (index != null && index == call.outputIndex)
        }
        check(matches.size <= 1) { "模型返回的工具调用标识关联冲突" }
        val call = matches.singleOrNull() ?: Call().also(calls::add)
        if (callId != null) {
            check(call.callId == null || call.callId == callId) { "模型在同一工具调用中更换了 call_id" }
            call.callId = callId
        }
        if (index != null) {
            check(call.outputIndex == null || call.outputIndex == index) { "模型在同一工具调用中更换了 output_index" }
            call.outputIndex = index
        }
        itemId?.let(call.itemIds::add)
        return call
    }

    private fun mergeName(call: Call, name: String?) {
        if (name.isNullOrBlank()) return
        check(call.name == null || call.name == name) { "模型在同一工具调用中更换了工具名称" }
        call.name = name
    }

    private fun snapshot(call: Call, arguments: String, rank: Int) {
        if (rank < call.snapshotRank) return
        check(rank != call.snapshotRank || call.arguments == arguments) { "模型返回了冲突的工具参数快照" }
        call.arguments = arguments
        call.snapshotRank = rank
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.index(): Int? = (this["output_index"] as? JsonPrimitive)?.intOrNull

    private inline fun check(value: Boolean, message: () -> String) {
        if (!value) throw ResponseProtocolException(message())
    }

    private fun error(message: String): Nothing = throw ResponseProtocolException(message)
}
