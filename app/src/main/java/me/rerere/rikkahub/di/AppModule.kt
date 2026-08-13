package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.BackgroundGenerationInterruptionNotice
import me.rerere.rikkahub.service.ConversationStreamCheckpoint
import me.rerere.rikkahub.service.GenerationRecoveryGate
import me.rerere.rikkahub.service.GenerationProtectionManager
import me.rerere.rikkahub.service.ImageGenerationManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get(), get())
    }

    single {
        UpdateChecker(get(), get(), get(), get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    // One owner for all long-running model work. Chat and image leases must share the same
    // foreground-service lifetime so a nested image tool cannot stop protection for its chat turn.
    single {
        GenerationProtectionManager(get())
    }

    single {
        BackgroundGenerationInterruptionNotice(get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
            generationProtectionManager = get(),
        )
    }

    // 生图放在 AppScope：ImgGenVM 用 viewModelScope，用户切走页面就会取消生成
    single {
        ImageGenerationManager(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            providerManager = get(),
            filesManager = get(),
            genMediaRepository = get(),
            appEventBus = get(),
            authTokenStore = get(),
            generationProtectionManager = get(),
        )
    }

    single {
        ConversationStreamCheckpoint(get())
    }

    single {
        GenerationRecoveryGate()
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            streamCheckpoint = get(),
            generationRecoveryGate = get(),
            generationProtectionManager = get(),
            backgroundInterruptionNotice = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            folderRepository = get()
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
