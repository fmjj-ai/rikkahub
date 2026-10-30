# RikkaHub 后台生成保活 · 技术方案

> 文档性质：**实现方案（How）**，供需求方审核后实施
> 目标读者：需求方（审核）/ 后续承接实现的 AI Agent（执行）
> 代码基线：当前工作区 `D:\PythonProject\ky\rikkahub`（Kotlin + Jetpack Compose，多模块）
> 版本：v1.3（新增常规设置开关 + 开启即跳系统电池设置；WakeLock 简化为固定超时；评审意见处置见附录）

---

## 0. 问题与结论

### 现象

在聊天中发送消息后切到后台（或锁屏），生成中断/报错；豆包、Kimi 等 app 随意切换不受影响。

### 根因

豆包/Kimi 是"云端生成"架构：答案在服务端生成并持久化，app 的连接只是"看直播"，断了重拉即可。RikkaHub 是**手机直连 LLM 服务商**，SSE 流式连接就是生成的全部：

- `ChatService`（`app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:141`）是 Koin 单例而非 Android Service 组件；
- 生成跑在 `appScope` 协程上（`ChatService.kt:335` 起，`handleMessageComplete` 内 collect SSE 流）；
- 应用切后台后进程进入 cached 状态：Android 12+ 的 cached app freezer 冻结进程线程，国产 ROM（vivo/小米等）有更激进的后台网络管控；线程冻结 / 断网 → 流式连接超时断开 → 生成失败；
- OpenAI 兼容的 chat completions 不支持断点续传，断开即作废，无法"回来后重新同步"。

### 结论

直连架构没有服务端兜底，唯一可行路线是**让本地连接不断**：

1. **前台服务（Foreground Service）**：生成期间拉起，进程升为前台优先级 → 不被 freezer 冻结（freezer 只冻 adj≥900 的 cached 进程，FGS 进程 adj≈200）、Doze 下网络可用、不被后台清理杀掉。
2. **PARTIAL_WAKE_LOCK（固定超时）**：有在途任务时持有，防熄屏后 CPU 休眠拖慢/中断流式收包；任务清空即释放，15 分钟硬超时兜底（需求方确认后台场景仅数分钟，不做活动续期）。
3. **常规设置开关 + ROM 白名单引导**：常规设置页加"后台生成保活"开关（默认开），开启时顺带跳系统电池设置引导允许后台运行；关闭则完全不启 FGS/不持锁，等价现状。

ChatGPT 官方客户端、Chatbox 等直连型 app 均为同款方案。已排除：WorkManager（底层同为 FGS 且不适合流式）、仅 WakeLock（不防冻结）、独立进程（照杀）、云端中转（无服务端）、静音保活 hack（灰色手段）。

---

## 1. 方案总览

| 组件 | 作用 | 位置 |
|---|---|---|
| `ChatGenerationService`（新建） | 前台服务 + 常驻通知 | `app/src/main/java/me/rerere/rikkahub/service/` |
| 生成任务保活管理（改造） | 在途任务集合 → 互斥启停服务 + WakeLock 续期 | `ChatService.kt` |
| Manifest | 服务声明 + `WAKE_LOCK` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限 | `AndroidManifest.xml` |
| 设置开关（新增条目） | 常规设置页"后台生成保活"开关；开启时跳系统电池优化页 | `SettingPreferencesGeneralPage.kt` + `PreferencesStore.kt` |
| 字符串 | 通知标题、设置项文案 ×6 语言 | `res/values*/strings.xml` |

不改动的部分：`ChatNotificationManager` 的 Live Update 进度通知与完成通知逻辑不变；生成中通知栏有"生成中"常驻通知（FGS 门票）+ 现有进度通知，两者同渠道低打扰，完成通知照旧。

---

## 2. 详细设计

### 2.1 `ChatGenerationService`（新建前台服务）

仿照 `WebServerService`（`service/WebServerService.kt`）结构实现 `Service`。

**Manifest 声明**（放在 `WebServerService` 块后，属性名与 `AndroidManifest.xml:138` 保持一致）：

```xml
<service
  android:name=".service.ChatGenerationService"
  android:exported="false"
  android:foregroundServiceType="specialUse">
  <property
    android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    android:value="keep SSE stream alive while generating chat responses" />
</service>
```

- `foregroundServiceType` 选 **`specialUse`**：`FOREGROUND_SERVICE_SPECIAL_USE` 已声明；`dataSync` 要新增权限且 Android 15+/targetSdk37 有每日时长配额，specialUse 无配额限制，对超长工具链生成更安全。
- `START_NOT_STICKY`：服务被强杀说明进程已死、生成已废，不重启。

**`onStartCommand`**：

1. `startForegroundCompat()`：API 34+ 走 `ServiceCompat.startForeground(this, ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`，低版本直接 `startForeground`；整体 try/catch（`SecurityException` 等 OEM 拒绝场景），失败则 `stopSelf()` 早返回（避免 5 秒未调 startForeground 触发 ANR），仅降级保活能力。
2. 支持 `ACTION_STOP`：`stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`。

**`onDestroy`**：取消 `serviceScope`（WakeLock 不在服务里，见 2.3）。

**常驻通知**：

- 渠道：复用 `CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID`（`IMPORTANCE_LOW`，`RikkaHubApp.kt:207`）。
- ID：`NOTIFICATION_ID = 2002`（避开 WebServerService 的 2001 与 Live Update 的 `hashCode()+10000` 区间）。
- 内容：`ongoing + onlyAlertOnce`，标题"正在生成回复"（新字符串 `notification_chat_generation_foreground`），`contentIntent` 指回 `RouteActivity`。
- 该通知**不带** `requestPromotedOngoing`/`shortCriticalText`，不会与 Live Update 的 chip 展示争抢。

### 2.2 生成任务计数 → 服务启停（`ChatService.kt` 改造）

核心原则：**集合成员即"未完成任务"**——不按 `Job.isActive` 判定（`compressConversation` 是 `LAZY` job，注册到 `start()` 之间 isActive=false，会被误判为空；且被 cancel 的旧 job 仍走 `invokeOnCompletion` 正常移出集合）。**判定与启停全部收敛进同一 `synchronized` 临界区**，停止路径延迟二次确认。

```kotlin
private val keepAliveLock = Any()
private val keepAliveJobs = ConcurrentHashMap.newKeySet<Job>()
private var pendingStopJob: Job? = null

private fun Job.trackKeepAlive(): Job {
    synchronized(keepAliveLock) {
        pendingStopJob?.cancel()           // 有新任务：取消待执行的服务停止
        keepAliveJobs.add(this)
        if (settingsStore.settingsFlow.value.displaySetting.enableBackgroundKeepAlive) {
            context.startForegroundServiceCompat()
            ensureWakeLockLocked()         // 见 2.3
        }
    }
    invokeOnCompletion {                   // 参数是 Throwable?，显式丢弃
        synchronized(keepAliveLock) {
            keepAliveJobs.remove(this)
            if (keepAliveJobs.isEmpty()) scheduleStopLocked()
        }
    }
    return this
}

// 集合空后不立即停：延迟 1.5s 二次确认，吸收 "cancel 旧 job → 注册新 job" 的交错窗口
private fun scheduleStopLocked() {
    pendingStopJob = appScope.launch {
        delay(1500)
        synchronized(keepAliveLock) {
            if (keepAliveJobs.isEmpty()) {
                stopKeepAliveLocked()
            }
        }
    }
}

private fun stopKeepAliveLocked() {
    context.stopService(Intent(context, ChatGenerationService::class.java))
    releaseWakeLockLocked()
}

private fun Context.startForegroundServiceCompat() {
    runCatching { startForegroundService(Intent(this, ChatGenerationService::class.java)) }
        .onFailure { Log.w(TAG, "startForegroundService failed (background start?), $it") }
}
```

关于"主生成结束 → 标题/建议任务"的衔接（评审关注点）：`generateTitle`/`generateSuggestion` 由 `onSuccess` 块**在主 job 协程体内同步 launch**（`ChatService.kt:681-686`），子任务注册发生在父 job 完成之前，集合全程非空，天然无空档；`scheduleStopLocked` 的延迟确认用于覆盖其余真实竞态（如 `sendMessage` 中 `previousJob.cancel()` 的 completion 与新 job 注册的线程交错）。

挂钩点（6 处，均为返回值套 `.trackKeepAlive()`）：

| 位置 | 原写法 | 改法 |
|---|---|---|
| `sendMessage` | `session.setJob(job)` | `session.setJob(job.trackKeepAlive())` |
| `regenerateAtMessage` | 同上 | 同上 |
| `handleToolApproval` | 同上 | 同上 |
| `compressConversation` | `session.setJob(job)` | 同上（LAZY 语义无碍：成员即未完成任务） |
| `translateMessage` | `appScope.launch(IO){…}` 返回值被丢弃 | `appScope.launch(IO){…}.trackKeepAlive()` |
| `launchWithConversationReference` | `appScope.launch{…}` | `appScope.launch{…}.trackKeepAlive()`（覆盖标题/建议生成） |

**后台启动限制兜底**：后台触发时 `startForegroundService` 可能抛 `ForegroundServiceStartNotAllowedException`（API 31+），被 `runCatching` 吞掉记日志，任务照常执行——等价现状不回退。注：Web UI 触发生成时 `WebServerService` 的 FGS 已在跑，app 处于前台服务态，可正常拉起，此兜底实际很少触发。

**开关联动**：`displaySetting.enableBackgroundKeepAlive`（见 2.5）关闭时，`trackKeepAlive` 只做集合登记，不启服务不持锁，行为等价现状。`ChatService.init` 里 `distinctUntilChangedBy` 观察该字段：运行中若被关闭，立即 `stopKeepAliveLocked()` 并取消 `pendingStopJob`（用户主动关就是要它立刻停）；开启无需动作（下一个任务自然拉起）。

### 2.3 WakeLock：固定超时模型

需求方明确后台场景只持续几分钟，采用最简单的模型：**集合空→非空时获取，非空→空时（延迟停服确认后）释放，15 分钟硬超时兜底**。不做活动续期。

```kotlin
private const val WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L
private const val WAKELOCK_TAG = "rikkahub:chat-generation"

private val wakeLock: PowerManager.WakeLock by lazy {
    context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        .apply { setReferenceCounted(false) }   // 重复 acquire 不叠计数，重复 release 不报错
}

// 均在 synchronized(keepAliveLock) 内调用：
private fun ensureWakeLockLocked() { if (!wakeLock.isHeld) wakeLock.acquire(WAKELOCK_TIMEOUT_MS) }
private fun releaseWakeLockLocked() { if (wakeLock.isHeld) wakeLock.release() }
```

- **管理位置**：锁与计数都由 `ChatService` 管（WakeLock 是进程级 `PowerManager` 对象，不要求在 Service 内），`ChatGenerationService` 只负责 FGS 身份与通知，职责干净。
- **审批等待**：工具等待用户审批期间锁仍持有，上限 15 分钟；该场景少见且时长可接受，不做特殊处理。
- **超时语义**：`acquire(timeout)` 到期系统强制释放；若届时仍有任务在跑，下一次任务注册/chunk 无关补锁——15 分钟已远超正常生成时长，判为可接受的保护上限。
- 权限：manifest 新增 `<uses-permission android:name="android.permission.WAKE_LOCK" />`（normal 级）。

### 2.4 Manifest 汇总

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

- `WAKE_LOCK`：normal 权限。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：normal 权限，仅服务于 2.5 的一键跳转。**Play 分发提示**：该权限属 Play 敏感权限，上架需用途声明且审批偏严；本项目以 APK 直发为主，保留以获得一键弹窗体验；若未来做 Play 渠道包，把它拆进独立 flavor 或直接去掉（链路有降级兜底，无此权限时走列表页，见下）。

### 2.5 功能开关 + ROM 白名单引导（常规设置页）

位置：**设置 → 偏好设置 → 常规**（`SettingPreferencesGeneralPage.kt`，追加一个 CardGroup）。存储进 `DisplaySetting`（`PreferencesStore.kt:594`，JSON 序列化，新增字段带默认值向后兼容）：

```kotlin
val enableBackgroundKeepAlive: Boolean = true   // 默认开：本功能即为修复痛点，关闭=完全等价现状
```

条目形态（开关 + 状态副文案）：

- 标题：`后台生成保活`
- 副标题：关闭时"切后台/锁屏时生成可能中断"；开启后按 `PowerManager.isIgnoringBatteryOptimizations(packageName)` 显示"已允许后台运行"或"建议到系统设置允许后台高耗电"；进页面（`Lifecycle` resume）时刷新状态。
- `onCheckedChange(true)`：先持久化，再依次尝试跳转（逐级 try/catch 降级）：
  1. `Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName")` —— 一键弹窗
  2. `Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)` —— 电池优化列表页（不需权限）
  3. `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName")` —— 应用详情页兜底
- `onCheckedChange(false)`：仅持久化（运行中的服务由 ChatService 的开关观察者停止，见 2.2）。
- 副标题提示"部分机型需在电池设置中手动选『允许后台高耗电』"（vivo 等 ROM 的后台耗电管理与该 intent 不完全等价）。

### 2.6 通知权限被拒时的策略（评审遗留产品决策）

**决定：照常启动 FGS**。理由：保活是功能本体，用户切后台的行为本身就要求生成继续；通知权限被拒时 FGS 通知不在通知栏显示（API 33+ 仅任务管理器可见），但进程照样活着。与 `RikkaHubApp.kt:163-171` 无权限则跳过 WebServer 服务的先例不同——Web 服务无通知等于不可用，而生成保活无通知仍工作。

### 2.7 双通知说明

FGS 常驻通知（ID 2002，普通 ongoing）与 Live Update 进度通知（chip/promoted）同渠道并存：chip 通知承载进度展示，常驻通知只是 FGS 的"门票"。可接受的视觉成本（豆包后台生成同样是一长驻 + 一进度）。不做"复用同一 ID"——进度通知按 conversationId 分发、随会话生灭，FGS 需要全局固定 ID，合并反而引入耦合。

### 2.8 字符串资源

六处 `strings.xml`（values / zh / zh-rTW / ru / ja / ko-rKR）新增：

| key | zh | en |
|---|---|---|
| `notification_chat_generation_foreground` | 正在生成回复 | Generating response |
| `setting_display_page_background_keep_alive` | 后台生成保活 | Background keep-alive |
| `setting_display_page_background_keep_alive_desc` | 切后台或锁屏时保持生成继续；开启时将跳转系统设置以允许后台运行 | Keep generation running in background or with screen off; enabling opens system settings to allow background running |
| `setting_display_page_background_keep_alive_allowed` | 已允许后台运行 | Background running allowed |

### 2.9 改动文件清单

| 文件 | 改动 |
|---|---|
| `app/src/main/AndroidManifest.xml` | +`WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；+`ChatGenerationService` 声明块 |
| `app/src/main/java/me/rerere/rikkahub/service/ChatGenerationService.kt` | **新建**，约 100 行 |
| `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt` | +保活管理（锁/集合/延迟停止/开关联动）；6 处 `.trackKeepAlive()` |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt` | `DisplaySetting` +`enableBackgroundKeepAlive` 字段（默认 true） |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesGeneralPage.kt` | +开关条目（开启时跳系统设置） |
| `app/src/main/res/values*/strings.xml` ×6 | +4 key |
| `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt` | `onTerminate` 补 `stopService(ChatGenerationService)` |
| `AGENTS.md` | Concepts 处补 `ChatGenerationService` 一句说明 |

无新增依赖；`material3`、`ai` 等模块不动。

---

## 3. 验证方式

1. `./gradlew :app:compileDebugKotlin` 编译通过；`./gradlew :app:assembleDebug` 打包。
2. **真机验收（vivo 为主）**：装包发长回复消息 → 立即切后台/锁屏 → 30s~1min 后回来 → 回复完整生成，无报错卡片。
3. 生成中通知栏见"正在生成回复"常驻通知；结束自动消失；点停止按钮服务立即退出。
4. 生成结束 → 标题/建议任务窗口期内 logcat 无 `ForegroundServiceStartNotAllowedException` 或启动失败日志。
5. 连续两会话并发生成，服务存活至最后一个任务结束；日志中无多余 start/stop 抖动。
6. 锁屏后台数分钟的正常生成（含一轮工具调用）完整跑完；>15 分钟的极端任务不属本次保障范围。
7. 常规设置页开关：开启时跳出系统电池设置页；`isIgnoringBatteryOptimizations` 状态回显正确；**关闭开关后**生成切后台回到旧行为（中断属预期），且运行中的服务立即退出、通知消失。
8. 回归：前台生成、断网报错、翻译、压缩、工具审批流、Web UI 触发生成（WebServerService 运行时）均正常。
9. 打包后 `./gradlew --stop`。

---

## 4. 风险与已知限制

- **通知权限被拒**：FGS 仍生效（见 2.6 决策）。
- **ROM 极限管控**：vivo 睡眠待机优化 / 小米神隐模式下 FGS 也可能被限网络，2.5 的引导即为此兜底；无法 100% 保证。
- **后台启动 FGS 被拒**：catch 后等价现状不回退（触发面很小）。
- **WakeLock 耗电**：任务存续期间持有（含工具审批等待），15 分钟硬超时兜底；正常生成分钟级，耗电可忽略。
- **FGS 通知被用户手动关闭**（Android 14+ 可在系统设置关整类）：FGS 继续跑，属平台行为不处理。
- **进程强杀**：生成丢失不可避免（所有方案的共同边界）。

---

## 附录：评审处置表（v1.0 → v1.1）

| 评审项 | 结论 | 处置 |
|---|---|---|
| Manifest 属性名写错（`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`） | **误报** | 文档 2.1 与 manifest 均为 `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，无需改 |
| P0-1 主生成→标题/建议空档期停服 | 部分采纳 | 特定路径实际无空档（子任务在父 job 体内注册）；延迟停服仍采纳，用于吸收 cancel/注册交错 |
| P0-2 `refreshKeepAlive` 无互斥 | 采纳 | 全量收敛 `synchronized(keepAliveLock)` 临界区 + `pendingStopJob` 延迟二次确认 |
| 代码块编译错误（newKeySet 泛型、invokeOnCompletion 参数名） | 采纳 | 2.2 代码块已修正 |
| `translateMessage` 拿不到 Job | 采纳 | 改法为 `.trackKeepAlive()` 直接挂在返回值上，无需改签名 |
| WakeLock 30 分钟超时太短/审批等待白耗 | 调整 | v1.2 需求方明确"后台仅几分钟"→ 简化为固定 15 分钟超时模型，不再做活动续期（2.3） |
| LAZY job 计数时机 | 采纳 | 判定改为"集合成员"而非 `isActive` |
| POST_NOTIFICATIONS 被拒策略 | 已拍板 | 照常启动 FGS（2.6） |
| 双通知语义 | 维持设计 | 2.7 说明 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` Play 敏感 | 部分采纳 | 保留权限+一键弹窗（APK 直发场景），补降级链路第 2 级 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 与 Play 提示（2.4/2.5） |
| 验收补两条 | 采纳 | 已并入 §3 第 4、6 条 |
| v1.3 需求方新增：功能做成开关，开启即跳系统设置，放常规设置页 | 采纳 | `DisplaySetting.enableBackgroundKeepAlive`（默认 true）+ `SettingPreferencesGeneralPage` 开关条目；运行中关闭即时停止服务与释放锁（2.2/2.5） |
