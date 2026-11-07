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

构建/打包结束后执行 `./gradlew --stop`，关闭残留的 Gradle/Kotlin daemon 进程，避免占用内存。

Windows 下集成校验优先使用 `scripts/Invoke-IntegrationChecks.ps1`。脚本固定离线、单次 daemon、
Kotlin 进程内编译和最多 2 个 worker，并在 `finally` 中执行 Gradle stop。不要一边长时间修改代码，
一边留下已经完成的编译 daemon；不得按进程名批量终止 Java，只处理能确认归属本次构建的进程。

Gradle 打包可能清理整个 variant 输出目录；构建前将需要保留的旧 APK 复制到独立 delivery/时间戳目录并校验哈希。
不得把唯一的备份副本留在 app/build/outputs/apk/release 或 debug 等由打包任务直接管理的目录中。

最终验收构建期间冻结源码；构建过程中改文件可能收到上一份输入快照的错误，需先核对当前源文件，
不要按旧错误反复修改或叠加启动构建。阶段验证结束后清理后台，再开始下一批修改。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。

命名习惯：模块名为小写目录（如 `ai/`、`speech/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

扩展使用尾随 lambda 的 Kotlin/Compose API 时，新增可选参数放在最后一个回调之前，保留回调在参数列表末尾；
修改后搜索所有调用方，避免原来的尾随 lambda 被解析为新增的 Boolean 等参数。

Windows 下 Python 工具若需要向终端输出中文，使用本次调用的 `-X utf8` 显式统一输出编码，
不要靠修改全局环境解决终端乱码，也不要把终端解码错误误判成源码文件损坏；文件仍需严格 UTF-8 校验。

Windows 下 `rg` 的文件模式使用 `-g '*.kt'` 等参数，不把 `目录/*.kt` 当作文件路径传入。

大屏永久侧栏下，面向整个窗口的相机等浮层应放在抽屉和聊天内容共同的根容器，不能按右侧聊天区域居中。
跨区域的入框动画统一使用窗口坐标，起点、落点和浮层原点不得混用局部坐标。

相机返回先完成逆向动画再销毁预览；恢复附件面板时重新测量真实按钮落点并保留滚动位置。
预览首帧就绪与展开动画结束分开判断，首帧到达即显示，不用固定淡入或等待制造加载提示。
相机和附件的连续转场必须使用同窗口、同根容器的图层；不得用主窗口相机与新建 Dialog 附件面板交叉淡入。
附件布局在相机期间保持挂载，遮罩只随整体浮层进出，不能随相机返回重新入场或叠加第二层遮罩。
CameraX 原生预览不得放入缩放、位移或淡出的 Compose 转场层；预览固定在最终位置，动效使用已校正方向的预览帧。
原生预览需要显式 clipToOutline；返回时先准备画面再启动收缩，测试应包含 AndroidView，不能只用纯 Compose 占位验证。
附件面板淡出使用完整窗口尺寸的独立父图层；BottomSheet 内部通过放置偏移定位，不能在其 modifier 上设置整体 alpha。
回归测试检查 alpha 刚低于 1、返回过程和面板底部的真实像素；仅检查节点存在、滚动状态或遮罩不能发现越界裁剪。

仅手机启用的布局或导航动效统一使用最小窗口宽度小于 600dp 的判断，不复用聊天页的永久侧栏判断；
平板路径不得额外套入限宽、居中或共享边界容器，拍照等两端共用动效另行处理。

同步官方内置提示词时只迁移精确匹配旧默认值的设置，不覆盖自定义文本；依赖不同工具架构的提示不得硬套。

## Testing Guidelines

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

设置持久化回归应比较完整序列化字段，不直接用整份 `Settings.equals` 判断往返是否一致：
提供商的 `@Transient` Compose 描述函数和部分非 data class 搜索选项使用引用相等。
运行时对象身份是否保留应另写专项测试，不能为通过往返测试而覆盖用户默认值或删掉配置字段。

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

- **ChatGenerationService**: A foreground service that keeps chat generation alive while generation tasks are running.
  It posts a persistent notification on the
  `CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID` channel to prevent the process from being frozen or losing network access
  when the app is backgrounded or the screen is off. It is started/stopped in tandem with the keepAlive task set in
  ChatService; the foreground service owns the bounded partial wake lock, supports an `ACTION_STOP` intent, and is gated by the
  `settings.displaySetting.enableBackgroundKeepAlive` setting. (
  app/src/main/java/me/rerere/rikkahub/service/ChatGenerationService.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
