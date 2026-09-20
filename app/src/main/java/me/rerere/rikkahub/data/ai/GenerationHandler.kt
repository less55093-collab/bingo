package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelRequestException
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_PLAN_TOOL_NAME
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.localizedChatMessage
import me.rerere.rikkahub.service.ImageOperationContext
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
private val IMAGE_TOOL_NAMES = setOf(IMAGE_GENERATION_TOOL_NAME, IMAGE_GENERATION_PLAN_TOOL_NAME)

internal fun validateExecutableToolCalls(tools: List<UIMessagePart.Tool>) {
    require(tools.map { it.toolCallId }.distinct().size == tools.size) { "模型返回了重复的工具标识，尚未执行" }
    require(tools.count { it.toolName in IMAGE_TOOL_NAMES && it.approvalState !is ToolApprovalState.Denied } <= 1) {
        "模型在同一回复中重复提交了生图工具。请使用一个生图方案或直接生图，尚未执行扣费。"
    }
    require(tools.all { it.toolCallId.isNotBlank() }) {
        "Model returned an incomplete tool call without an id"
    }
    require(tools.all { it.toolName.isNotBlank() }) {
        "Model returned an incomplete tool call without a name"
    }
    tools.filterNot { it.approvalState is ToolApprovalState.Denied }.forEach { tool ->
        require(Json.parseToJsonElement(tool.input.ifBlank { "{}" }) is JsonObject) {
            "模型返回的 ${tool.toolName} 工具参数不完整，尚未执行工具"
        }
    }
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        conversationId: String? = null,
    ): Flow<GenerationChunk> = flow {
        // Explicit image actions only need the image tool. Resolving a text provider here would
        // make a disabled chat model prevent an otherwise valid image request.
        val provider by lazy { model.findProvider(settings.providers) ?: error("Provider not found") }
        val providerImpl by lazy { providerManager.getProviderByType(provider) }

        var messages: List<UIMessage> = messages
        val directImageGeneration = messages.lastOrNull { it.role == MessageRole.USER }
            ?.annotations?.contains(UIMessageAnnotation.DirectImageRequest) == true
        prepareDirectImageToolMessage(messages, model)?.let { toolMessage ->
            messages = messages + toolMessage
            processingStatus.value = "正在提交生图任务，请先保持 App 在前台"
            emit(GenerationChunk.Messages(messages))
        }
        if (directImageGeneration && messages.lastOrNull()?.getTools()?.any { it.isExecuted } == true) {
            emit(GenerationChunk.Messages(messages))
            return@flow
        }

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools for assistant=${assistant.id}")
                if (assistant?.enableMemory == true) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { content ->
                            memoryRepo.addMemory(memoryAssistantId, content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                check(!directImageGeneration) { "图片任务状态不完整，尚未提交，请重新发送生图请求" }
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                val completedMessage = messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                val tools = completedMessage.getTools().filter { !it.isExecuted }
                validateModelToolCalls(tools, toolsInternal, model)
                val finalizedMessage = if (tools.isEmpty()) completedMessage.finishReasoning() else completedMessage
                messages = messages.slice(0 until messages.lastIndex) + finalizedMessage
                emit(GenerationChunk.Messages(messages))

                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            validateModelToolCalls(toolsToProcess, toolsInternal, model)
            val startedTools = toolsToProcess.map { tool ->
                if (tool.approvalState is ToolApprovalState.Pending ||
                    tool.approvalState is ToolApprovalState.Denied) tool
                else tool.copy(executionStarted = true)
            }
            val startingMessage = messages.last()
            messages = messages.dropLast(1) + startingMessage.copy(parts = startingMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) startedTools.find { it.toolCallId == part.toolCallId } ?: part
                else part
            })
            emit(GenerationChunk.Messages(messages))
            if (directImageGeneration) processingStatus.value = null

            // 同一轮里的多个工具调用并发执行，否则生成多张图片时会串行排队，等待时间成倍增加
            val toolMessages = if (directImageGeneration) {
                // Only explicitly attached reference images belong to this direct action. A fresh
                // image request must not silently reuse a previous generated image as its source.
                messages.drop(messages.indexOfLast { it.role == MessageRole.USER }.coerceAtLeast(0))
            } else messages
            val executedTools = coroutineScope {
                startedTools.map { tool ->
                    async {
                        if (conversationId != null && tool.toolName in IMAGE_TOOL_NAMES) {
                            withContext(ImageOperationContext(conversationId, messages.last().id.toString(), tool.toolCallId)) {
                                executeTool(tool, toolsInternal, toolMessages)
                            }
                        } else executeTool(tool, toolsInternal, toolMessages)
                    }
                }.awaitAll().filterNotNull()
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            // 工具执行完毕，本轮思考到此结束：补上结束时间，否则思考时长会一直显示 0 秒
            messages = messages.dropLast(1) +
                lastMessage.copy(parts = updatedParts).finishReasoning()
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
            // Image delivery ends this operation. A model continuation must not create another
            // billable image, including after a successful tool result.
            if (executedTools.any { it.toolName in IMAGE_TOOL_NAMES }) break
        }

    }.flowOn(Dispatchers.IO)

    private fun validateModelToolCalls(calls: List<UIMessagePart.Tool>, definitions: List<Tool>, model: Model) {
        try {
            validateExecutableToolCalls(calls)
            calls.filterNot { it.approvalState is ToolApprovalState.Denied }.forEach { call ->
                val definition = definitions.find { it.name == call.toolName }
                    ?: error("模型请求了不存在的工具 ${call.toolName}，尚未执行")
                val args = call.inputAsJson() as JsonObject
                val schema = definition.parameters() as? me.rerere.ai.core.InputSchema.Obj
                require(schema?.required.orEmpty().all { key -> args[key] != null && args[key] != JsonNull }) {
                    "模型返回的 ${call.toolName} 工具参数缺少必填内容，尚未执行"
                }
                schema?.properties?.forEach { (key, child) ->
                    if (child is JsonObject) args[key]?.let { validateToolArgumentValue(it, child, key) }
                }
                if (call.toolName == IMAGE_GENERATION_TOOL_NAME) {
                    require((args["prompt"] as? JsonPrimitive)?.content?.isNotBlank() == true) { "生图提示词不能为空" }
                } else if (call.toolName == IMAGE_GENERATION_PLAN_TOOL_NAME) {
                    me.rerere.rikkahub.data.ai.tools.local.parseImageGenerationVariants(args)
                }
            }
        } catch (error: Exception) {
            throw ModelRequestException(model.modelId,
                IllegalStateException("模型返回了无效的工具调用，尚未执行生图。${error.message.orEmpty()}", error))
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ) {
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            // An explicit user choice must not be discarded just because a newly discovered
            // upstream model is absent from the local capability registry. AUTO remains passive.
            model = if (assistant.reasoningLevel != ReasoningLevel.AUTO) model.copy(
                abilities = (model.abilities + me.rerere.ai.provider.ModelAbility.REASONING).distinct(),
            ) else model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        var receivedOutput = false
        fun observeOutput(parts: List<UIMessagePart>) {
            if (parts.any { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.text.isNotBlank()
                        is UIMessagePart.Tool -> part.toolName.isNotBlank() || part.input.isNotBlank()
                        is UIMessagePart.Image -> part.url.isNotBlank()
                        is UIMessagePart.Audio -> true
                        else -> false
                    }
                }) receivedOutput = true
        }
        processingStatus.value = "正在等待 ${model.modelId} 回复，可切到后台，完成后会通知你"
        try {
        if (stream) {
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            ).collect { chunk ->
                chunk.choices.forEach { choice ->
                    observeOutput(choice.delta?.parts.orEmpty() + choice.message?.parts.orEmpty())
                }
                if (receivedOutput) processingStatus.value = null
                messages = messages.handleMessageChunk(chunk = chunk, model = model)
                chunk.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        } else {
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            chunk.choices.forEach { choice ->
                observeOutput(choice.delta?.parts.orEmpty() + choice.message?.parts.orEmpty())
            }
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
        check(receivedOutput) { "模型返回空结果，未收到正文或有效工具调用，尚未启动生图。请切换模型或稍后重试。" }
        } catch (error: Exception) {
            if (error is CancellationException || error is ModelRequestException) throw error
            throw ModelRequestException(model.modelId, error)
        } finally {
            processingStatus.value = null
        }
    }

    /**
     * 执行单个工具调用，返回带 output 的副本；Pending 状态返回 null 表示本轮不产出结果。
     * 供并发执行使用，因此不得修改任何共享状态。
     */
    private suspend fun executeTool(
        tool: UIMessagePart.Tool,
        toolsInternal: List<Tool>,
        messages: List<UIMessage>,
    ): UIMessagePart.Tool? {
        return when (val approvalState = tool.approvalState) {
            is ToolApprovalState.Denied -> tool.copy(
                output = listOf(
                    UIMessagePart.Text(
                        json.encodeToString(
                            buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive("Tool execution denied by user. Reason: ${approvalState.reason.ifBlank { "No reason provided" }}")
                                )
                            }
                        )
                    )
                )
            )

            is ToolApprovalState.Answered -> tool.copy(
                output = listOf(UIMessagePart.Text(approvalState.answer))
            )

            // Should not reach here, but just in case
            is ToolApprovalState.Pending -> null

            else -> runCatching {
                val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                    ?: error("Tool ${tool.toolName} not found")
                val args = runCatching {
                    json.parseToJsonElement(tool.input.ifBlank { "{}" })
                }.getOrElse {
                    error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                }
                Log.i(
                    TAG,
                    "generateText: executing tool=${toolDef.name} inputBytes=${tool.input.toByteArray().size}"
                )
                val result = toolDef.executeWithContext(args, messages)
                val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                tool.copy(output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess))
            }.getOrElse {
                // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                if (it is CancellationException) throw it
                Log.w(
                    TAG,
                    "generateText: tool=${tool.toolName} failed error=${it.javaClass.simpleName}"
                )
                tool.copy(
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(
                                buildJsonObject {
                                    put(
                                        "error",
                                        JsonPrimitive(it.localizedChatMessage(context))
                                    )
                                }
                            )
                        )
                    )
                )
            }
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
