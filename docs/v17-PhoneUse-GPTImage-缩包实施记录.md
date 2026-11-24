# v17：Phone-use、GPT Image 与缩包实施记录

日期：2026-11-14。迁移前基线提交 `41c29b3b` 已推送至 `origin/codex/upstream-integration-20261102`。

## 交付

最终目录：`delivery/phone-use-image-v17-final-20261114-155053/`。

- arm64：`app-github-arm64-v8a-release.apk`，27,043,616 字节，25.79 MiB。
- x86_64：`app-github-x86_64-release.apk`，27,140,879 字节，25.88 MiB。
- 通用包：`app-github-universal-release.apk`，29,381,926 字节，28.02 MiB。
- 版本名：`2.4.3-english-v17`，versionCode：`2026092702`。
- applicationId 保持 `me.rerere.rikkahub`；签名证书与原包一致。
- 三个最终 APK 都通过签名及 zipalign 16 KB 对齐检查；哈希见同目录 `checksums.json`。
- 旧包完整保存在 `delivery/before-phone-use-20261114-150402/`；其他同日目录为中间验证版本，安装请选带 `final` 的目录。

## 已落实

### Phone-use

迁入官方全部 **11 类动作**：observe、screenshot、tap、long_press、swipe、scroll、type、press、open_app、list_apps、wait。原对比文档和早期进度中的“12 类”是计数错误。

包含 GitHub 专属 AccessibilityService、手势指示器、读屏/截图/输入/应用列表、逐助手开关、参数校验、官方限制应用规则、截图会话文件保存及工具结果 UI。坐标保持官方 0–1000 网格。

增加管理器级互斥、排队操作停止代数、30 秒单调用超时、截图取消后的资源清理。增加操作控制条与独立停止通知，服务断开时结束控制。没有通知权限或通知渠道被关闭时，不隐藏控制条，以保证能停止。

重要更正：实际当前基线没有官方 `ChatFloatingWindows` 或 `enableFloatingChatWindow` 设置，原分析中的相关描述不准确。本次实现使用无障碍覆盖层提供动作状态、返回聊天和停止控制，而不是迁入官方整套聊天浮窗。手机操作动作能力完整；完整聊天内容浮窗、浮窗输入等并未迁入。

GitHub flavor/API 30+ 才提供工具；Play 编译通过，Play Release 合并 Manifest 不含无障碍服务。共享管理器/工具类型仍在 main 源集，不声称 Play 不含任何相关代码。

启用方法：助手设置 → 本地工具 → Phone Use → 开启系统中的“RikkaHub Phone Use”。建议同时允许通知，以便截图/手势让出控制条时仍可从通知停止。Android 可能要求先在应用信息中允许受限制的设置。

### GPT Image

保留现有独立 Images API 和旧 ImgGen/图集，不恢复新版 MediaCreation。

Responses 在流式请求的 image_generation 工具中请求 `partial_images=2`；按 item_id 替换图片快照，支持交错多图、忽略过时预览、最终图和非流式图片输出。元信息使用既有 metadata，不改变消息主序列化结构。结束/中断清理未完成图片；有效最终图片保留原编码写入托管文件，避免不必要的 PNG 转码和内存峰值。

模型字符串保持原有 `gpt-image-2`，未盲目替换为官方另一字符串。服务商是否实际支持该模型和 partial_images，仍取决于所用服务。

### 缩包

- 扫码复用 ZXing + CameraX，移除 Quickie bundled 与 ML Kit 直接依赖；相册二维码导入保留。
- PDF 文本提取改用官方纯 Kotlin parser、字形/CID 资源和测试。旧 MuPDF 源文件保留用于回滚，但不编译或打入 native 库。
- 三张未发现运行时引用的历史 banner 仅从 APK 打包排除，源码文件不删除。
- 离线词典（9 个资源条目）、英语资源完整保留；未删数据库和旧图片数据。

最终 arm64 相比原 40,668,227 字节减小 **13,624,611 字节，约 12.99 MiB / 33.50%**。ZIP 实查没有 libmupdf、libbarhopper、mlkit_barcode_models 或 assets/banner。

主要 ZIP 压缩内容：DEX 8,580,200 字节；assets 7,957,944；static 3,230,036；res 2,763,582；lib 2,239,449；resources.arsc 1,856,400。

当前保留额外词典、英语、Web 静态内容及定制 UI，不承诺在功能不变的前提下等于官方 15.57 MiB。

## 验证

- ai 单测：174，全部通过。
- app GitHub 单测：408，全部通过。
- document 单测：14，13 通过，1 个外部 PDF corpus 测试因未配置输入目录跳过。核心 PDF parser 12 项全部通过。
- mediagen 单测：3，全部通过。
- 合计 599 项：598 通过、1 跳过、0 失败。
- Play Debug Kotlin 编译通过；Play Release 主 Manifest 检查通过。
- 最终 GitHub Release 打包通过；签名、对齐、版本、资源排除和副本哈希检查通过。

构建通过 `scripts/Invoke-IntegrationChecks.ps1` 执行。脚本新增 `-HeapMegabytes` 参数，默认仍为 2048；本次 app/Release 使用 4096、1 worker、离线、Kotlin 进程内编译。每轮成功或失败均执行 Gradle stop；结束时未发现 Java/Gradle/Kotlin 后台残留。检查设备时启动的 ADB server 也已停止。

## 尚未真机验证的边界

无连接的 Android 设备，所以无障碍授权、OEM 窗口行为、真实点击/输入/扫码和控制条交互没有实机验证。没有使用真实服务端凭据运行 GPT Image 生图；已验证请求构造和模拟流事件。

纯 Kotlin PDF 通过官方样例，不表示对任意复杂 PDF 与 MuPDF 完全等价；特殊字体、极端文档或损坏文件仍需用户实际文档回归。控制条在无通知权限时保持可见，可能出现在截图中或遮住顶部少量区域，优先确保可停止。

## 回滚

迁移前基线 `41c29b3b` 已在远端。回滚应按本次功能提交撤回，不重置/删除此前函数图、多协议、账号、相机等定制。旧 APK 在独立目录，但 Android 降级安装及新工具配置的旧版本兼容需要单独处理；先备份设置和数据库。旧版本不认识 `phone_use` sealed 类型时，应关闭并移除该工具选项后再做兼容导出。