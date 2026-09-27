# 考研英语阅读 · WebView → Compose 原生界面 改造方案

> 版本：v1.1 ｜ 适用：RikkaHub 2.4.3-english-v15 源码（当前工作区）
> 性质：可行性方案（不含代码、不含测试实施细节）
> 分工约定：每步标注【简单】/【中等】/【复杂】——简单交本地助手执行，复杂交给 GPT 产出后再合入。

---

## 0. 结论先行

**可行，且非常适合改造。** 理由：

- 这是 Android 应用内的一个 **WebView 页面**，不是浏览器网页——"改成 Compose"= 把 WebView 换成原生 Compose 界面，属标准安卓开发，不涉及 Compose-for-Web。
- 页面里"像 Web 的部分"其实全不依赖 Web：数据是静态 assets、AI 能力已经由 Kotlin 的 `ChatService` 提供、生词/历史只是本地存储。UI 层替换后，**功能可以 1:1 保留**。
- 唯一需要重点投入的交互：**长按选区 → 工具栏精确贴在选区旁**（详见 6.3）——功能不丢，但实现成本高，已确认直接做"精确贴选区"，列为"复杂件"交 GPT。

总体策略一句话：**留数据、留 AI 逻辑、只换 UI 壳；分 8 个阶段渐进替换，每阶段可独立验收**，WebView 版作为对照基线保留到最后一步再删。

### 0.1 已确认决策（截至今晚定稿）

| 待定项 | 决策 | 影响 |
|---|---|---|
| 长按选区工具条 | ✅ 精确贴选区（方案 B） | 阶段 5 直接按此做，交 GPT |
| AI 会话历史迁移 | ✅ 清空重来（A） | 阶段 7 只迁生词本，AI 历史从零开始 |
| 数据源格式 | ✅ 转为纯 JSON | 阶段 0 一次性脚本 |
| 启动范围：全量推进 vs 阅读页 Pilot | ⏳ 明早定 | 不影响阶段 0/1，两者都要先做 |

---

## 1. 现状盘点（已完成调研）

### 1.1 当前链路

```
侧栏"书本"入口(ChatDrawer)
  └─> EnglishStudyActivity（Kotlin Activity）
        └─> WebView 加载 assets 里的 index.html（HTML+CSS+JS 单文件，约 1541 行）
              ├─ 三个 Tab：阅读 / 词汇 / AI 学习
              ├─ 内置迷你 Markdown 渲染器 + 迷你词典逻辑 + 选区/弹窗逻辑
              └─ 通过 JS 桥 "RikkaEnglish" 调原生能力
                    └─> ChatService / SettingsStore / 文件保存与分享 / 跳转主聊天
```

### 1.2 功能清单（改造后必须全部保留）

**阅读 Tab**
- 选择器：类型（英一/英二）→ 年份 → Text 编号；记住上次阅读位置；随机一篇
- 文章渲染：段落排版，**每个英文单词可点击**
- 点词 → 词典气泡：释义（来自词典数据，去掉音标行）、"加入生词本"、"AI 例句"（生成 3 个例句+翻译+搭配+记忆提示）
- 题目区：每题 A/B/C/D 选项可点选、已选高亮、进度（已做 x/y）
- 显示/隐藏答案：选项标正确(绿)错误(红)、每题给出"我的答案/正确答案"、得分统计、答案网格（每篇 5 题）
- 右侧栏：本篇已加入的生词列表（可移除）、词典查询
- 长按选中文段 → 工具栏（翻译 / 解析）：翻译输出到选区旁气泡；解析弹出"长难句解析"模态框

**词汇 Tab**
- 生词本列表：单词 + 释义 + 来源篇目，可移除，显示总数
- 闪卡：随机抽词 → 显示释义 → "记住并移除" / "下一个"
- 导出：保存 JSON 到系统下载 / 系统分享；导入：读取 JSON 合并（去重）

**AI 学习 Tab**
- 配置：助手下拉、模型下拉（来自设置数据）、推理开关、Enter 发送开关、"刷新列表"
- 聊天记录：多会话（右侧窗口 / 悬浮窗口 / 选区隐藏窗口三类）、会话卡片回放、清空记录
- AI 悬浮窗（可拖动、可缩放）+ 右侧 AI 交流窗（发送 / 翻译 / 词汇快捷按钮）
- 全文工具：翻译本篇、AI 提炼重点词、AI 解析本篇、复制/发送提示词到主程序
- 所有 AI 输出：流式接收 + 迷你 Markdown 渲染（标题/列表/表格/代码块/引用/粗斜体）

**数据与存储（localStorage 5 个 key）**
- 生词本（word/meaning/source/addedAt 数组）
- 上次阅读位置（type/year/textNum）
- AI 会话历史（多会话 + 活跃会话指针 + 自增序号）
- AI 配置（assistantIndex/modelId/reasoningEnabled/sendOnEnter）

**静态数据（assets）**
- readings.js：真题 136 篇 / 每篇 5 题 / 680 题（含答案）
- dictionary.js：单词 → 释义/音标 词典
- answers.json：备用答案数据（当前以 readings 内嵌答案为准）

### 1.3 原生桥（EnglishBridge）能力——改造后变为直接 Kotlin 调用

- 读取配置：助手列表、模型列表（来自 SettingsStore）
- 设置配置：assistantIndex + modelId + reasoningEnabled → 应用到会话
- 页面内 AI：`sendMessage` + 轮询会话流取最后一条助手文本回传（350ms 一次，最多 600 次）
- 重置会话：换新 conversationId
- 发送到主程序：ACTION_SEND 跳 RouteActivity
- 生词导出：写入系统下载（MediaStore）/ 系统分享（FileProvider）

---

## 2. 技术栈选型

| 层 | 选型 | 理由 / 备注 |
|---|---|---|
| 语言 | Kotlin | 项目已是 Kotlin |
| UI | **Jetpack Compose + Material3** | 项目主程序即 Compose；风格跟随现有页面模式 |
| 页面框架 | 单个 Compose Activity + Scaffold + TabRow | 3 个 Tab 用本地状态切换即可，无需引入导航库 |
| 状态管理 | ViewModel + StateFlow；轻量局部状态用 remember/rememberSaveable | 与现有 ChatViewModel 风格一致 |
| DI | Koin（inject） | 项目已有，直接注入 ChatService、SettingsStore |
| AI 对话 | **直接复用现有 ChatService** | 不新增网络层：configureEnglishSession / sendMessage / 会话流 / 生成状态 |
| 本地存储 | DataStore Preferences（存 JSON 字符串） | 替代 localStorage；生词本/历史/配置各一个 key 即可 |
| 数据解析 | kotlinx-serialization（项目已有）+ 一次性预处理脚本 | 把 readings.js / dictionary.js 转成纯 JSON 资源 |
| 协程 | Kotlin 协程（viewModelScope）+ delay 轮询 | 替代原 thread+sleep 轮询 |
| 文本选择 | Compose SelectionContainer + TextLayoutResult | 长按选区（详见 6.3） |
| 文件能力 | MediaStore（保存）/ FileProvider + ACTION_SEND（分享）/ ActivityResult OpenDocument（导入） | 原桥逻辑直接搬 |
| Markdown | 第一版自写"轻量 AnnotatedString 渲染器" | 行为与现版保持一致、零新增依赖；后续可换更强库 |
| 图标 | 跟随项目惯例（Lucide 图标集） | 保持风格一致 |
| 不引入 | 不新建 Retrofit/Ktor、不引入 WebView、不引入第三方 Markdown 重库（首版） | 降低风险 |

---

## 3. 目标架构（改完后的样子）

```
ChatDrawer 入口（不变）
  └─> EnglishStudyComposeActivity（Compose 壳）
        └─> EnglishStudyScreen
              ├─ Tab：阅读页（文章/题目/词典气泡/右侧生词）
              ├─ Tab：词汇页（生词列表/闪卡/导入导出）
              └─ Tab：AI 学习页（配置/会话历史）
              ├─ 浮层：AI 悬浮窗 / 选区工具条 / 长难句解析弹窗
              └─ ViewModel 层
                    ├─ 阅读与词典 State（数据来自 assets JSON）
                    ├─ 生词本 State（DataStore）
                    └─ AI 会话 State（调 ChatService，流式轮询）
```

拆分建议：把"如何操作 ChatService / SettingsStore"封装成一个独立模块（可叫 AI 会话管理器），这样**简单页面（阅读/词汇）与 AI 逻辑解耦**，AI 部分后续交给 GPT 时只动这一个模块。

---

## 4. 分阶段实施步骤（一步一步来）

### 阶段 0：准备与基线 【简单】

做什么：
1. 确认当前源码 `2.4.3-english-v15` 能正常构建、WebView 版功能全部可用（当作"对照基线"）。
2. 把 `assets` 里的 `readings.js`、`dictionary.js` 用一次性脚本/手工转成纯 JSON（去掉 `const XXX =` 包装），作为新数据源；原 JS 文件保留一份不动。
3. 确认数据字段结构（阅读：type/year/textNum/content/questions/answers；词典：word→释义）。

技术栈：无新增；一个预处理脚本（Node/python 均可，一次性）。

验收：生成的两个 JSON 能被任意 JSON 检查器打开且结构完整。

### 阶段 1：Compose 壳 + 三个 Tab 骨架 【简单】

做什么：
1. 新建 Compose Activity，`setContent` 挂 Compose 根布局（Scaffold + TabRow + 三个占位页面）。
2. 三个 Tab：阅读 / 词汇 / AI 学习，本地状态切换 + `rememberSaveable` 记住当前 Tab。
3. 入口不动（继续从侧栏进，先指向新 Activity 或临时双入口）。

技术栈：Compose + Material3；TabRow 或自定义胶囊 Tab（对齐现版样式）。

验收：能进新页面、能切 3 个 Tab、深色/浅色主题正常。

### 阶段 2：阅读页 【简单～中等，核心体验】

做什么：
1. 选择器：类型 → 年份 → Text 联动（数据来自阶段 0 的 JSON）；恢复上次阅读位置；随机一篇。
2. 文章渲染：按段落排版，**每个英文单词可点击**。
   - 技术做法：用 `BasicText` + `AnnotatedString` 对每个英文单词追加 `LinkAnnotation.Clickable`；同一段落整块渲染，避免逐词堆 Row 的排版问题。
   - 点击单词 → 弹词典气泡（Popup 定位：用 `TextLayoutResult.getBoundingBox(词首偏移)` 换算屏幕坐标，气泡显示释义 + "加入生词本" + "AI 例句"两个按钮）。
3. 题目区：选项按钮 A-D、选择高亮、进度统计、"显示/隐藏答案"、绿/红标记、得分、答案网格。
4. 右侧栏：本篇生词列表（可移除）。

技术栈：Compose（BasicText/LinkAnnotation/Popup）；词典查询、去音标显示、格式化逻辑照搬现版 JS。

验收：与 WebView 版并排对比，选同一篇，点击单词、答题、对答案完全一致。

### 阶段 3：词汇页 【简单】

做什么：
1. 生词本存储：DataStore Preferences，一个 key 存 JSON 数组（字段：word/meaning/source/addedAt），结构与现版 localStorage 一致。
2. 生词列表：总数、单词+释义+来源、移除。
3. 闪卡：随机抽词 → 显示释义 → "记住并移除"/"下一个"（空列表时显示占位）。
4. 导入导出：
   - 导出：把"保存到系统下载"和"系统分享"做成两个工具函数（逻辑直接搬自原生桥：MediaStore 写文件 / FileProvider + ACTION_SEND 分享 JSON）。
   - 导入：系统文件选择器（OpenDocument）读 JSON → 解析 → 按单词去重合并。

技术栈：DataStore + kotlinx-serialization + MediaStore/FileProvider + ActivityResultContracts。

验收：能增删、闪卡、导出的 JSON 能被原版导入，反之亦然（格式兼容）。

### 阶段 4：AI 学习页 【中等～复杂，独立模块化】

做什么：
1. **先做 AI 会话管理器模块**（重点，独立于 UI）：
   - 状态：conversationId、是否初始化、assistantIndex、modelId、reasoningEnabled。
   - 方法：读配置、改配置、重置会话、发送消息、流式接收。
   - 流式实现：`viewModelScope` 协程里调 `sendMessage` 后循环轮询会话流（`delay(350)`，沿用现版节奏），把"最后一条助手文本"推给 UI；同时监听生成状态判断结束。
   - 这个模块是原 JS 桥逻辑的 Kotlin 移植 + 原 AiPollRunnable 的协程化。
2. **配置 UI**：助手下拉、模型下拉（读 SettingsStore）、推理开关、Enter 发送开关、刷新列表。
3. **聊天界面**：三类会话（右侧窗口 / 悬浮窗 / 选区隐藏窗口）各自维护消息列表；会话历史持久化（DataStore，含活跃会话指针+自增序号），"AI 学习 Tab"里做会话卡片回放、清空。
4. **AI 工具栏**：翻译本篇 / 提炼重点词 / 解析本篇 / 复制提示词 / 发送到主程序（ACTION_SEND）——提示词模板照搬现版。
5. **悬浮窗**：可拖动、可缩放的浮动 AI 窗（见 6.2，标注复杂）。

技术栈：ViewModel + StateFlow + 协程 + DataStore + 现有 ChatService；浮窗用 Compose 浮层 + pointerInput 手势。

验收：三类窗口都能发消息、流式显示、历史持久化、重启后记录还在；配置与主程序共用（改助手/模型后主聊天不变）。

### 阶段 5：长按选区 + 精确贴选区工具条 【复杂，交 GPT】

> 已确认：直接做"精确贴选区"（方案 B），不做固定位置过渡。

做什么：
1. 文章段落用 `SelectionContainer` 包裹，监听选区变化：拿到选中文本 + 起止偏移（`SelectionContainerState.selection`）。
2. 坐标换算：用段落渲染的 `TextLayoutResult.getBoundingBox(起/止偏移)` 得到选区包围盒，叠加段落自身的屏幕位置（`onGloballyPositioned`）与滚动偏移，换算成选区的真实屏幕坐标。
3. 工具条"贴在选区旁"（仿现版：下方优先、放不下翻到上方、出屏时夹紧），两个按钮：
   - 翻译 → 复用 AI 管理器发翻译提示词，结果弹气泡显示。
   - 解析 → 弹"长难句解析"模态框，复用 AI 管理器。
4. 边界处理：多行选区用首/尾字包围盒取近似；容器滚动、窗口尺寸变化时跟随或自动隐藏；选区清空时隐藏工具条。

技术栈：Compose SelectionContainer + TextLayoutResult + onGloballyPositioned；无第三方依赖。

验收：长按选词/句 → 工具条出现在选区附近；翻译/解析结果正常；滚动后位置不串。

### 阶段 6：Markdown 渲染 【中等】

做什么：
1. 把现版 `renderMarkdown` 的行为（段落/标题/有序无序列表/表格/代码块/行内代码/引用/粗斜体/链接）移植成 Compose 渲染：
   - 简单做法：写一个"轻量 Markdown → AnnotatedString"构建器，或逐块生成 Compose 子组件（Block/Inline 两级解析）。
2. AI 输出、例句、解析结果统一走这个渲染器。

技术栈：纯 Compose，零新增依赖。

验收：拿现版 AI 输出样例对比，标题/列表/表格/代码块显示一致。

### 阶段 7：数据迁移与兼容 【简单】

做什么：
1. 新老存储互不感知。提供导出/导入 JSON 作为迁移通道（用户手动迁一次生词本；AI 历史可接受清空重来）。
2. 或临时保留"旧版 WebView 入口"一个版本，新老并存一周，确认无遗漏后删。

技术栈：无新增。

验收：用户生词本能无损搬入新版。

### 阶段 8：收尾替换 【简单】

做什么：
1. 侧栏入口统一指向 Compose 版；原 WebView Activity/桥/HTML 标记为废弃或删除（数据 JS→JSON 后原 JS 可删）。
2. 按第 7 节"功能对照验收清单"逐项人工过一遍，确认无功能缺失。

验收：功能对照清单全部打勾；WebView 版下线。

### 阶段 9（可选增强）：【复杂】

- AI 会话改用实时事件式输出（若 ChatService 支持 messageFlow，可去掉轮询）。
- 生词本升级为 Room 表（若有大批量/搜索/排序需求）。

---

## 5. 复杂度分工总表（按"谁来做"归类）

| 步骤 | 内容 | 复杂度 | 建议执行方 |
|---|---|---|---|
| 阶段 0 | 数据转 JSON、基线确认 | 简单 | 本地助手 |
| 阶段 1 | Compose 壳 + Tab 骨架 | 简单 | 本地助手 |
| 阶段 2 | 阅读页（逐词点击/词典气泡/答题） | 简单～中等 | 本地助手（气泡定位细节可要 GPT 补） |
| 阶段 3 | 词汇页 + 导入导出 | 简单 | 本地助手 |
| 阶段 4a | AI 会话管理器（桥逻辑 Kotlin 化） | 中等～复杂 | **GPT**（涉及会话并发/状态机） |
| 阶段 4b | AI 配置/聊天/历史 UI | 中等 | GPT 或本地助手 |
| 阶段 4c | 悬浮窗拖拽缩放 | 复杂 | **GPT** |
| 阶段 5 | 长按选区 + 精确贴选区工具条（方案 B） | 复杂 | **GPT** |
| 阶段 6 | Markdown 渲染 | 中等 | GPT 或本地助手 |
| 阶段 7 | 数据迁移 | 简单 | 本地助手 |
| 阶段 8 | 收尾替换 | 简单 | 本地助手 |

简单部分约占六成，复杂部分集中在 **AI 会话管理器、悬浮窗手势、选区精确跟随** 三处。

---

## 6. 风险与取舍（提前说清）

### 6.1 数据源格式
`readings.js`/`dictionary.js` 是 JS 语句而非纯 JSON，Kotlin 不能直接解析——必须先做一次性转换（阶段 0）。转换后建议核一遍条目数（136 篇/680 题）与字段完整性。

### 6.2 AI 轮询 vs 实时流
现版是"发消息 + 每 350ms 轮询会话"，改造首版**沿用同一节奏**保证行为一致；若 ChatService 底层有实时消息流可后续切换（列为增强项）。轮询放协程里做，不要用 Thread+sleep。

### 6.3 长按选区（已确认做"精确贴选区"）
- 核心功能（选中→翻译/解析→结果展示）**不丢**；用户已确认直接做"精确贴选区"方案 B。
- 实现要点：`SelectionContainer` 拿选中文本+起止偏移 → `TextLayoutResult.getBoundingBox` + 段落 `onGloballyPositioned` + 滚动偏移换算屏幕坐标 → 工具条贴选区（多行取近似，滚动跟随）。这是全项目最复杂的交互之一，**交给 GPT 产出**。
- 注意差异：Compose 选区限于**单段落内**（现版基本也是段落排版，影响很小）；长按出现的是**安卓原生选择手柄**（体验更顺手）。

### 6.4 会话历史迁移（已确认：清空重来）
AI 历史记录从 localStorage 迁移较繁琐（多会话+序号+活跃指针）。**已拍板采用 A：清空重来**——新版 AI 历史从零开始；生词本走 JSON 导出/导入迁移；旧版 WebView 在收尾前保留，可随时翻旧记录。

### 6.5 侧栏入口
保持入口动画/位置不变（现入口在侧栏头像旁书本图标处），只换目标页，避免用户找不到。

---

## 7. 功能对照验收清单（收尾时逐项打勾）

- [ ] 阅读：类型/年份/Text 联动选择
- [ ] 阅读：恢复上次阅读位置
- [ ] 阅读：随机一篇
- [ ] 阅读：文章段落排版 + 逐词可点击
- [ ] 阅读：点词弹词典气泡（释义/加入生词本/AI 例句）
- [ ] 阅读：题目 A-D 选择/高亮/进度
- [ ] 阅读：显示答案/绿红标记/得分/答案网格
- [ ] 阅读：右侧本篇生词列表
- [ ] 阅读：长按选区 → 翻译/解析（含长难句解析弹窗）
- [ ] 词汇：生词列表/移除/总数
- [ ] 词汇：闪卡（显示释义/记住并移除/下一个）
- [ ] 词汇：导出 JSON（保存下载/系统分享）
- [ ] 词汇：导入 JSON（去重合并）
- [ ] AI：助手/模型/推理/Enter 配置 + 刷新
- [ ] AI：三窗口聊天（右侧/悬浮/隐藏）+ 流式输出
- [ ] AI：会话历史持久化 + 回放 + 清空
- [ ] AI：工具栏（翻译/提炼/解析/复制/发主程序）
- [ ] AI：输出 Markdown 渲染（标题/列表/表格/代码块/引用）
- [ ] 主题：深浅色正常
- [ ] 入口：侧栏图标进入新版页面
- [ ] 数据：生词本可通过 JSON 迁移
- [ ] 性能：文章/题目/词典启动无卡顿（数据量不大，无需优化）

---

## 8. 一句话总结

**留数据、留 ChatService、留提示词模板，把 HTML/JS 这一层整体换成 Compose**；先骨架、后阅读、再词汇、最后 AI；AI 会话管理器、悬浮窗手势、长按选区精确贴选区是"复杂件"，交给 GPT。全程以现版功能清单做对照，逐阶段可独立验收。

---

## 9. 实施进度（工作区，持续更新）

> 状态符号：✅ 已完成（编译通过）｜⏳ 进行中｜⬜ 未开始

| 阶段 | 内容 | 状态 | 说明 |
|---|---|---|---|
| 0 | 数据转纯 JSON | ✅ | 新增 `app/src/main/assets/english/data/readings.json`（136 篇）与 `dictionary.json`（25383 词条）；原 JS 保留作 WebView 基线。转换脚本：`tools/convert_english_data.py` |
| 1 | Compose 壳 + 三 Tab | ✅ | 新增 `EnglishStudyComposeActivity` + `EnglishStudyScreen`（阅读/词汇/AI 三页签）；侧栏入口已切换到新版；旧 WebView Activity 保留（可 adb 对照） |
| 2 | 阅读页 | ✅ | 真题选择器（类型/年份/Text/随机/恢复上次）、`LinkAnnotation` 逐词可点击、词典气泡（释义+加入生词本+AI例句占位）、答题/对答案/得分/答案网格、本篇生词 |
| 3 | 词汇页 | ✅ | 生词列表/移除、闪卡、JSON 导入（去重合并）、保存到下载/系统分享（复刻原桥）；存储：SharedPreferences key `rikkahub_english_vocab_v2` |
| 7 | 迁移通道 | ✅ | 生词本走导出→导入 JSON 迁移（阶段 3 已含）；AI 历史按决策"清空重来" |
| 8 | 收尾替换 | ⏳ | 入口已切换；**待用户确认后**再删 WebView 版与数据 JS 文件 |
| 4 | AI 学习页 | ⬜ 复杂件→GPT | AI 会话管理器 / 配置 UI / 三类会话 / 悬浮窗（拖拽缩放） |
| 5 | 长按选区精确贴选区 | ⬜ 复杂件→GPT | SelectionContainer + TextLayoutResult 坐标换算（已确认方案 B） |
| 6 | Markdown 渲染 | ⬜ 复杂件→GPT | AI 输出渲染（列表/表格/代码块/引用） |

### 关键实现备注（交接给 GPT 时参考）

- **新代码位置**：阅读页 `ui/pages/english/ReadingTab.kt`；词汇页 `ui/pages/english/VocabTab.kt`；骨架 `ui/pages/english/EnglishStudyScreen.kt`；入口 `ui/activity/EnglishStudyComposeActivity.kt`；数据层 `data/english/`（ReadingsRepository / DictionaryRepository / VocabRepository / EnglishTextUtils）。
- **逐词点击实现**：单 Text + `LinkAnnotation.Clickable`（包在 `androidx.compose.ui.text` 下），点击取 `TextLayoutResult.getBoundingBox(词首 offset)` + 文本窗口坐标换算锚点，`Popup` 定位。此结构为阶段 5 选区保留单 Text/段落模型。
- **词典气泡"AI 例句"按钮**：当前为占位 Toast，接入 AI 会话管理器后替换为真实调用。
- **生词存储**：SharedPreferences（复用 `rememberSharedPreferenceString` 钩子），key `rikkahub_english_vocab_v2`，词汇页与阅读页共用同一响应式存储。