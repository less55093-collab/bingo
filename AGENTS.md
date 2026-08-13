# Repository Guidelines

本文档面向贡献者，概述本仓库的模块结构、开发流程，便于快速上手并保持一致的协作质量。

## Build, Test, and Development Commands

使用 Android Studio 或命令行 Gradle：

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
./gradlew lint                   # 运行 Android Lint
```

构建应用需要在 `app/` 下提供 `google-services.json`（用于 Firebase）。
`web` 模块会在 `preBuild` 阶段构建 `web-ui/` 并复制静态资源，需要本地可用 `pnpm`。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。

命名习惯：模块名为小写目录（如 `ai/`、`speech/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

## Testing Guidelines

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

## Android 应用内更新

### 固定配置

- 更新清单：`https://gitee.com/chenyifan888/bingo-releases/raw/master/update.json`
- APK 下载：`https://download.bingoapi.top/releases/Bingo-<versionName>.apk`
- 官网固定下载：`https://download.bingoapi.top/releases/Bingo.apk`
- R2 存储桶：`bingoapp`；上传凭证只在 `.r2-credentials`，该文件已忽略，绝不提交或输出。
- `app/build.gradle.kts` 的 `BuildConfig.UPDATE_URL` 必须保持为更新清单地址。
- 当前基线：`1.0.5 / versionCode 7`；下次发布至少使用 `versionCode 8`。

### 官网下载地址约定

- 官网源码位于同级目录 `../bingo测试首页/`，下载按钮必须始终指向固定地址
  `https://download.bingoapi.top/releases/Bingo.apk`。
- 不得在官网源码中写入 `Bingo-1.0.5.apk`、`Bingo-1.0.6.apk` 等带版本号的 APK
  地址，也不得为了 App 发版而重新修改或构建官网。
- 固定地址不随版本变化：每次发版用新 APK 覆盖 R2 的 `releases/Bingo.apk`，因此用户访问
  同一个官网链接时下载到的是最新安装包。
- 带版本号的 `releases/Bingo-<versionName>.apk` 必须同时保留，用于应用内更新清单、版本归档、
  哈希校验和必要时回滚；它与官网固定对象的内容应完全一致。
- `Bingo.apk` 当前已配置为官网固定对象。除非下载域名或存储方案整体迁移，否则后续对话不得
  更换这个官网链接。

### 每次发布

1. 递增 `versionName` 和 `versionCode`，执行 `./gradlew :app:assembleRelease --console=plain`。
2. 使用 `app/build/outputs/apk/release/app-arm64-v8a-release.apk`，核对 SHA-256、包名和 v2 签名。
3. 将同一 APK 上传到 R2 的两个对象：

   - `releases/Bingo-<versionName>.apk`：版本归档及应用内更新使用。
   - `releases/Bingo.apk`：官网固定下载入口，每次发布覆盖。

   两个对象都设置 `Content-Type: application/vnd.android.package-archive`。
   `Content-Disposition` 的下载文件名分别与对象用途一致：

   ```text
   版本归档：attachment; filename="Bingo-<versionName>.apk"
   官网固定：attachment; filename="Bingo.apk"
   ```

4. 从固定公开地址完整下载一次，确认 HTTP 200、上述响应头及 SHA-256 与本地一致。
5. 最后更新 Gitee 的 `update.json`：版本、时间、说明、R2 URL、大小、SHA-256。清单必须始终是单一合法 JSON，不能追加第二份内容。

```json
{
  "version": "1.0.6",
  "publishedAt": "2026-08-13T06:00:00Z",
  "changelog": "1. 更新内容",
  "downloads": [{
    "name": "Android arm64",
    "url": "https://download.bingoapi.top/releases/Bingo-1.0.6.apk",
    "size": "37 MB",
    "sha256": "64 位小写 SHA-256"
  }]
}
```

如果上传前访问过目标 URL，Cloudflare 可能缓存 404。先换一个未访问过的版本化路径或清除该 URL 缓存；未确认固定 URL 可下载前，不更新 `update.json`。

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
