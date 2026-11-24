# RikkaHub Android 交互式函数绘图组件——开发实施计划

> 文档状态：交给电脑端 AI / Codex 执行的实施方案（**仅本地修改计划文档，尚未修改 GitHub 仓库或编译项目**）  
> 编制日期：2026-11-10；本次修订：v1.1（改为现有 codex 分支内直接开发，不新建分支或 worktree）  
> 目标仓库：`fmjj-ai/rikkahub`  
> **唯一目标开发分支：`codex/upstream-integration-20261102`（使用电脑上已有的该分支工作区，直接继续开发）**  
> 查阅时该分支提交：`de2e193227afbd4cd5966b73671aca6248f238f0`（实施时重新核对 HEAD）  
> 平台：**仅 Android**；Kotlin + Jetpack Compose；优先离线、原生渲染。

## 0. 给电脑端开发助手的执行指令（请先读；本次修订重点）

**这份计划由手机端交给电脑端 AI 执行。用户明确要求：直接在电脑上已有的 `codex/upstream-integration-20261102` 分支继续开发；不要新建 Git 分支，不要新建 `git worktree`，不需要让手机端 AI 修改 GitHub。**

1. **先只读检查当前电脑仓库，再动代码**。进入用户已在使用的 RikkaHub 工作目录，执行：

   ```bash
   git branch --show-current
   git status --short
   git diff --stat
   git diff --cached --stat
   git ls-files --others --exclude-standard
   git rev-parse HEAD
   ```

   这些命令只读取状态。确认当前分支就是 `codex/upstream-integration-20261102`；若不是，**不要擅自 checkout / switch**，先向用户说明现状，让用户安排正确的开发目录。
2. **把电脑本地现有文件当作事实来源**，不得根据上面记录的旧远端 commit 覆盖本地代码。该分支可能已有用户未提交、已暂存或未跟踪的修改；先查看相关文件的当前内容与 diff，再决定插入点。不要假定远端代码比本地新。
3. **任何情况下不要未经用户明确批准执行以下操作**：`git reset --hard`、`git clean`、`git restore`（覆盖文件）、`git stash`、`git checkout` / `git switch`、`git pull`（隐含 merge/rebase）、`git merge`、`git rebase`、强推，或用生成的整文件直接覆盖用户已修改的文件。也不要新建分支或 worktree。
4. **直接在当前 codex 分支的现有工作树里进行增量开发**：优先新增专属绘图模块；必须修改已有文件时，精确到函数/代码块做小范围补丁。遇到同一位置存在用户未提交变更且无法安全融合时，暂停该文件的修改并说明冲突，不能删改原有工作成果。
5. 不要自动把用户已有的改动一起 `git add`、提交或推送。默认完成代码和本地测试即可，**提交、推送等 Git 写入动作须另获用户明确指示**；验收报告要将“原有改动”和“本功能新增改动”区分开。
6. 优先实现本文 **MVP → Compose UI → AI 工具接入 → 增强** 的顺序；每阶段报告真实代码修改、测试结果与剩余问题。开始前重新通读当前工作树真实代码，若与本文描述不一致，以电脑上代码为准，并解释方案调整。
7. 无法构建或缺乏 Android SDK / 真机时明确说明，不得宣称“编译通过”“实测流畅”。本计划本身不要求修改当前 GitHub 远端。

**原则：不为了实施这份计划改变分支结构或破坏用户未提交工作；与用户当前 codex 工作进度并行、保守地集成。**

## 1. 项目现状（已根据指定 codex 分支核查）

| 模块 | 已观察到的代码 / 作用 | 计划切入点 |
| --- | --- | --- |
| AI 本地工具集合 | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt`，`getTools(options)` | 注册 `plot_function` |
| 工具开关 | `.../data/ai/tools/local/LocalToolOption.kt`，序列化 sealed 类 | 增加 `FunctionGraph` 选项及 UI 开关/默认值 |
| 工具 UI 注册表 | `.../ui/components/message/tools/ToolUI.kt`，`ToolUIRenderer` / `ToolUIRegistry` | 增加 `PlotFunctionToolUI` |
| 工具调用消息展示 | `.../ui/components/message/ChatMessageTools.kt` | 先做内联摘要，再优化图表独立显示 |
| 消息主渲染 | `.../ui/components/message/ChatMessage.kt`，`MessagePartsBlock` / `groupMessageParts()` | 保留消息顺序，在适当位置插图 |
| 文本/Markdown | `.../ui/components/richtext/Markdown.kt` 和 `MarkdownNew.kt` | 第二入口处理显式 `function-graph` 代码块 |
| Web Markdown 预览 | `.../ui/components/richtext/MarkdownWeb.kt` | 非 MVP；要么降级显示代码，要么以后补支持 |
| 现有图像工具 | `.../data/ai/tools/local/ImageGenerationTool.kt`，工具名 `draw_image` | **绝不可混用**；数学图和 AI 生成图片是不同能力 |
| Android 配置 | `app/build.gradle.kts`：Compose、`minSdk=26`、JVM 17 | 沿用既有工具链、序列化与依赖管理 |
| 依赖管理 | `gradle/libs.versions.toml` | 如选表达式库，在此增版本及别名 |

**注意已发现的展示限制：** 当前 `ChatMessage.kt` 会通过 `groupMessageParts()` 把推理与工具分组为 `ThinkingBlock`，工具 UI 会在可折叠步骤中展示。只加 `ToolUIRenderer.Summary()` **不能保证**实现 ChatGPT 式“正文中独立的绘图卡片”。必须单独做一次消息布局适配，确保工具结果不会被折叠步骤挡住或重复显示。

## 2. 产品目标与边界

### 2.1 目标使用体验

用户发送：“画出 `arcsin(x)`、`arccos(x)`、`arctan(x)` 三张图。”

期望：
- 支持工具的模型通过 `plot_function` 提供每张图或多张图的结构化定义。
- 在 AI 回复对应位置生成绘图卡片；卡片含标题、坐标轴、函数曲线、图例、展开按钮。
- 卡片内部可滑动查看坐标（默认手势不抢聊天滚动）；点击打开全屏交互页。
- 全屏支持单指平移、双指缩放、复位、读取坐标；支持浅色/深色主题。
- 关闭网络或重启应用，再打开历史聊天仍可由保存的**表达式与参数**重新绘图，不依赖云端静态图片。
- 普通 Markdown、LaTeX、代码块、其他工具调用、消息流式输出行为保持不变。

### 2.2 首版范围（MVP）

- 直角坐标系显函数：`y = f(x)`。
- 多条曲线叠加；也能一次请求返回多张独立绘图卡片。
- 基础算术、括号、常量 `pi`、`e`、幂函数、`sqrt`、`abs`、`exp`、`ln`、`log`、`sin`、`cos`、`tan`、`asin`、`acos`、`atan`（以解析器实际映射测试为准）。
- 可选定义域（含端点是否闭合）、视窗范围、标题、曲线标签。
- 处理 NaN、无穷、定义域外与常见不连续点。
- 语义清晰的错误提示（表达式无效、函数超出定义域、超出资源限制）。

### 2.3 非首版范围（明确暂不做）

- 隐函数 `F(x,y)=0`、参数曲线、极坐标、不等式区域填充、二重积分区域、3D、符号求导。
- 从任意一句 LaTeX 文本自动猜测应当绘图（误触发率高）。
- 让模型生成或执行 HTML / JavaScript / 任意 Kotlin 代码作为绘图指令。
- 将图库图片生成 `draw_image` 的流程改造成数学绘图。

## 3. 总体架构

```text
用户提问
   |
   v
AI 模型
   |--------------------------|
   | A. Function Calling      | B. 显式 function-graph 代码块（第二阶段）
   v                           v
plot_function 工具          结构化块解析器
   |                           |
   +------------+--------------+
                v
       GraphSpec v1 数据模型
                |
       校验 / 规范化 / 限流
                |
       ExpressionCompiler (一次编译)
                |
       FunctionSampler (按视窗采样)
                |
       Compose Canvas 坐标绘制
                |
   聊天独立卡片 / 全屏查看 / 历史恢复
```

**三层职责：**
1. **数据协议层**：`GraphSpec` 是用户数据，独立于 UI、工具和具体模型；版本化，能被序列化到历史工具结果。
2. **纯计算层**：数学解析、数值求值、采样与 discontinuity 检测均为纯 Kotlin，便于 JVM 单测。
3. **展示层**：Compose 负责手势、坐标变换、画曲线与主题，不应每帧重新编译表达式。

## 4. GraphSpec v1 协议（建议）

### 4.1 JSON 示例

```json
{
  "version": 1,
  "title": "反三角函数",
  "curves": [
    {"id": "f1", "expression": "asin(x)", "label": "arcsin(x)", "domain": {"min": -1, "max": 1, "includeMin": true, "includeMax": true}},
    {"id": "f2", "expression": "acos(x)", "label": "arccos(x)", "domain": {"min": -1, "max": 1, "includeMin": true, "includeMax": true}},
    {"id": "f3", "expression": "atan(x)", "label": "arctan(x)"}
  ],
  "viewport": {"xMin": -4, "xMax": 4, "yMin": -3.5, "yMax": 3.5},
  "showGrid": true,
  "showLegend": true
}
```

这里的示例把三条线叠在同一个坐标系；若用户明确要求“三个独立图”，工具需按协议创建三个 `GraphSpec`（多次工具调用或输出 `graphs: [GraphSpec...]`，二者择一并保持固定）。**建议首版工具单次调用返回 `graphs` 数组**，使单次请求可稳定创建多卡片；每张图内部可包含多条线。

### 4.2 输入与结果区分

- 模型传给 `plot_function` 的参数：`{"graphs":[GraphSpec, ...]}`，工具内部忽略或拒绝非白名单字段。
- 工具最终输出：`{"ok":true,"graphs":[GraphSpec, ...]}`；异常时 `{"ok":false,"error":{"code":"INVALID_EXPRESSION","message":"..."}}`。
- 只保存**绘图定义和必要校验结果**，不保存每个采样点的海量数组；历史打开时重新采样。
- 对 `version` 进行显式验证；未知版本显示提示/原始数据而不是崩溃。

### 4.3 表达式语法约定

- 计算语法与显示 LaTeX 分离：`expression = "asin(x)"`，`label = "\\arcsin x"` 或普通文字。
- 统一输入 `arcsin`→`asin`、`arccos`→`acos`、`arctan`→`atan`、`π`→`pi`；仅对严格词法 token 规范化，禁止随意对任意字符串做 replace。
- 解析器默认三角函数采用**弧度制**；界面提示。
- 首版不直接接受通用复杂 LaTeX（如 `\\frac{...}{...}`）作为计算表达式；需要另外受控的转换器才能支持。
- `log` / `ln` 的数学含义必须规范（建议 `ln` 为自然对数、`log10` 为十进制对数；如保留 `log` 明确映射为自然对数），并由测试验证。

### 4.4 安全与容量限额（可配置常量，先给保守默认值）

- 每次最多 **4 张图**；每图最多 **6 条曲线**。
- 单个表达式最多 **256 字符**；嵌套深度不超过 **24 层**。
- 视窗边界必须为有限数；满足 `min < max`；缩放倍率设上下限，避免无限采样。
- 每条曲线单轮最多 **约 2,048 个基础采样点**；自适应加密有全局预算及耗时/取消检查。
- 函数名、变量名、运算符白名单；不要执行任何模型生成的代码，不读取文件、不访问网络。
- 拒绝除 `x` 外的未知变量（未来要参数滑块再扩展）。
- 错误向 UI 显示用户可读文本，日志保留安全诊断，不输出堆栈到聊天。

## 5. 数学表达式引擎

### 5.1 选型

**优先评估 `net.objecthunter:exp4j:0.4.8`**：轻量 JVM 表达式求值库，Maven Central 可获取，Apache-2.0 许可；与 Compose 完全解耦。它比较老（2017 年发布），正式引入前检查兼容性、函数集合、负号/幂的运算优先级以及 R8 混淆构建。资料：
- https://central.sonatype.com/artifact/net.objecthunter/exp4j/0.4.8
- https://www.objecthunter.net/exp4j/

建议在 `gradle/libs.versions.toml` 声明版本/库别名，并在 `app/build.gradle.kts` 依赖。**不能盲信**库函数支持情况，先写探索单测。若测试无法满足明确语法需求，可采用受限的自研 token + Pratt/parser，但要有完整的词法/优先级/边界测试，避免临时用字符串求值器糊弄。

### 5.2 计算过程

1. 参数 JSON 解码→schema 检查→表达式 token 规范化。
2. 一次构建已编译的 `CompiledExpression`；绘图期间只更新变量 `x` 并求值。
3. 按视窗像素宽度/采样预算生成 `x`，由 `f(x)` 得到 `y`。
4. 将 `NaN`、`Infinity`、定义域外结果标记为**断点**，不连线。
5. 对 `1/x`、`tan(x)` 等强变化/疑似极点，结合像素高度跳变、区间中点复采样或局部细分，并在无法确认连续时保守断线；不能只依据有限值就把极点两侧连起来。
6. 对可见区间外线段做裁剪，避免溢出或无限大的 path。
7. 拖动或缩放时只更新 `viewport`，对采样任务进行取消/防抖；旧任务不可覆盖新视窗结果。

**需特别测试：**
- `asin(x)` 和 `acos(x)` 定义域 `[-1,1]`，端点可见；
- `atan(x)` 的水平渐近表现（不能画成有限 x 处的截断终点）；
- `1/x` 在 0 处断开；
- `tan(x)` 在 `π/2+kπ` 附近断开；
- `sqrt(1-x^2)` 只在 [-1,1] 有实数值；
- `ln(x)` 在 x≤0 不可见；
- 常数、偶函数、奇函数、极陡曲线、空图（无可见有效点）、边界上函数。

## 6. Compose 绘图组件设计

### 6.1 推荐组件结构

```text
app/src/main/java/me/rerere/rikkahub/
  data/graph/
    GraphSpec.kt                  # @Serializable 协议及验证
    GraphSpecValidator.kt         # 边界、限额、错误码
    ExpressionNormalizer.kt       # arcsin/π 等规范化
    ExpressionCompiler.kt         # 受限表达式编译与缓存
    FunctionSampler.kt            # 连续区间、断点、自适应采样
    GraphViewport.kt              # 视窗、坐标变换、缩放约束
  ui/components/graph/
    FunctionGraphCard.kt          # 聊天中的图表卡片
    FunctionGraphCanvas.kt        # 网格、轴、曲线、刻度绘制
    FunctionGraphControls.kt      # 复位、提示、图例、坐标查询
    FunctionGraphExpanded.kt      # 全屏/弹窗模式
  data/ai/tools/local/
    PlotFunctionTool.kt           # AI Tool 协议与校验执行
  ui/components/message/tools/
    PlotFunctionToolUI.kt         # 现有 ToolUIRenderer 实现
```

文件名可根据仓库当前惯例调整，**不要求机械照抄**。

### 6.2 UI 交互策略

- 聊天内：`fillMaxWidth()`、默认高度约 220–260dp（随布局适配）；显示图例、标题与“放大查看”。
- **默认避免内嵌画布直接抢占聊天列表的垂直滚动**：聊天卡片优先点击展开；如开启卡片内缩放/拖动，必须有明确手势模式并验证与列表滚动、长按复制、折叠手势无冲突。
- 全屏：单指平移、双指以手指焦点缩放；缩放后视窗比例保持连续；双击/按钮复位；长按/十字准星查询坐标（可选）。
- 坐标轴刻度：使用 1/2/5 × 10^n 的 nice ticks，不固定 1 单位；零轴突出；x、y 标签不会溢出容器；可选 π 的常用角度刻度。
- 使用 `MaterialTheme.colorScheme` 适配深浅色；多条曲线色彩和线型可区分，不能仅依赖颜色。
- 保存结果应为结构化 GraphSpec；保存或分享静态图（PNG）可后续增加，不阻塞 MVP。
- 明确无有效点、表达式错误、超出资源限制时的空态。
- 注意 Compose `remember(spec, viewport)`、`drawWithCache`、后台计算，不要在 `Canvas` 的 draw 回调里执行解析、JSON 解码或大量数学求值。

## 7. AI 工具接入（首选途径）

### 7.1 `plot_function` 工具

在 `data/ai/tools/local/PlotFunctionTool.kt` 按现有 `Tool(name, description, parameters, execute)` 风格实现：
- `name = "plot_function"`。
- 工具描述只解释“生成数学坐标图，不是生成图片”，不诱导模型每次提到函数都调用工具。
- `parameters`：必须是静态、严格的 JSON schema；入参为 `graphs`（1–4），子字段以 `GraphSpec v1` 定义为准。
- `execute`：解码、校验、规范化、可选探测编译，然后返回 `UIMessagePart.Text` 内的 `{"ok":...,"graphs":...}`；不能把非有限 `NaN` 数值序列化到 JSON。
- 绘图的繁重采样由本地 UI 使用者侧完成，不要把上千采样点再作为工具结果发给模型。
- 错误需要结构化、可恢复，不抛异常中断整个对话。

### 7.2 工具的注册与开关

- 在 `LocalToolOption.kt` 添加可序列化的选项，如 `@SerialName("function_graph") data object FunctionGraph`。
- 在 `LocalTools.kt` 添加 `plotFunctionTool` 与对应 `getTools(...)` 条件。
- 检查并接入**真实的助手设置页、工具选项持久化、默认值与迁移逻辑**；仅添加 sealed class 不代表 UI 可见或默认开启。
- 功能不需要生图模型，不应跟随 `ImageGeneration`/`draw_image` 是否可用。
- 模型不支持 Function Calling 时不注入无效工具；使用明确降级提示或第二阶段代码块入口。

### 7.3 工具展示

- 在 `ToolUIRegistry` 注册 `PlotFunctionToolUI`。
- 第一轮先实现 `Summary()` 能稳定显示曲线和工具错误，`Preview()` 展示大图；工具状态可识别执行中/完成/失败。
- **关键改造：独立正文卡片。** 评估 `groupMessageParts()` 以及 `ThinkingBlock` 结构，在保留普通工具追踪记录的同时，输出“GraphDisplayBlock”并按消息原始顺序插在适当位置；禁止同图在折叠工具步骤和正文卡片重复出现。
- 不能假定 `Summary()` 会自动显示在消息正文：当前代码将工具放在可折叠的思考/步骤组里。
- 每个图卡片使用稳定 id（工具 call ID + graph index 等）以避免流式重绘时闪烁或错位。
- 历史重新进入、切换消息分支、重新生成回复应正确重建图卡片；无需重新调用 AI。

## 8. 第二入口：无 Function Calling 模型（后做）

使用显式、完整的 fenced code block：

````markdown
```function-graph
{"version":1,"title":"正弦函数","curves":[{"id":"f1","expression":"sin(x)"}],"viewport":{"xMin":-6.3,"xMax":6.3,"yMin":-1.5,"yMax":1.5}}
```
````

实现要求：
- 只识别独立的 `function-graph` 栅栏块，不能碰普通 `json`、`kotlin`、LaTeX 或散文。
- 流式生成时 **仅在完整闭合、解析成功后**转为卡片；半截 JSON 显示为流式代码或等待，不造成反复闪烁。
- 解析层可复用 `GraphSpecValidator`，与工具入口一致。
- 对 `Markdown.kt`、`MarkdownNew.kt` 两条实际渲染分支做好兼容；可以在 `MarkdownBlock` 入口创建“文本片段 + 图块”的顶层分割器，但需要正确处理嵌套代码围栏和原有 markdown AST，避免正则误截取。优先利用代码块 AST 节点或可信分词逻辑。
- 对复制/转发/分享，保留原始代码块文本；渲染卡片是视图变化，不应篡改源消息。
- 未知或无效块保留原始内容并给出友好提示，不丢失 AI 文本。
- WebView Markdown 预览是否支持图卡片需另列验收项，不默认为已支持。

## 9. 用户与历史数据兼容

1. `GraphSpec` 必须带 schema 版本。
2. 不更改现有消息数据库结构作为 MVP 前提；优先复用已持久化 `UIMessagePart.Tool` 的 `input/output`。
3. 若产品已对工具结果做截断、清理、迁移、导出，需逐项核实 GraphSpec 能否保留；若不能，才设计最小向后兼容迁移。
4. 历史绘图失败不能让整条聊天消息空白；渲染错误占位且正常显示其他文字。
5. 仅在主动开启绘图工具/显式代码块时出图，**不要根据 `arcsin` 字样扫描所有文字自动绘图**。
6. 如果同一条助手消息包含多段文字、图表与其他工具调用，应保持实际输出顺序。

## 10. 性能、安全与可访问性

### 性能
- 表达式只编译一次，采样异步执行且可取消；快速多次缩放只应用最新的结果。
- 曲线 path 和刻度缓存依赖视窗/尺寸/主题；拖动中允许降低精度、手势结束后恢复。
- 限制点数、图数与后台任务数；长历史列表中的不可见卡片不应持续采样。
- 不要每次消息流式 token 更新就重新执行完整图形绘制。
- 首版验收应在中端真实 Android 设备上对“多张图同时存在、聊天高速滚动、缩放、前后台切换”做帧率/内存观察，而不是预设绝对 FPS 保证。

### 安全
- `GraphSpec` 当作不可信外部输入：严格长度/深度/范围验证、函数白名单、有限数检查。
- 不使用模型提供的 JS/HTML，不让公式引用任意文件/网络，不用现成 `eval_javascript` 工具承载绘图。
- 异常函数与数值溢出仅导致曲线断段或错误卡片，不影响整个聊天界面。
- 审查新依赖的许可和 Android 构建兼容；不能偷偷引入 WebView CDN。

### 无障碍
- 卡片有清晰的无障碍描述：标题、曲线名称、可操作按钮。
- 颜色以外有线型/图例辅助区分；支持系统字体缩放；按钮可通过无障碍焦点访问。

## 11. 测试计划与验收清单

### 11.1 JVM 单元测试（先写）

| 场景 | 期望 |
| --- | --- |
| `sin(x)`, `cos(x)`, `x^2`, `sqrt(x)` | 正确数值与图形趋势 |
| `arcsin(x)`→`asin(x)` | 规范化正确，域外不绘制 |
| `acos(x)` | 单调递减、值域表现正确 |
| `atan(x)` | 原点附近单调递增，两侧渐近 |
| `1/x` | x=0 附近不跨渐近线连线 |
| `tan(x)` | 在极点附近有效分段 |
| `sqrt(1-x^2)` | 仅有效定义域可见 |
| `ln(x)` | x≤0 不绘制 |
| `x^2` 的负号/优先级、`2*pi`、嵌套括号 | 和定义语法一致 |
| 未知函数、错误括号、非法字符、超长输入 | 校验失败且不崩溃 |
| 多图超限、无限/颠倒/极端 viewport | 拒绝或归一化至安全范围 |
| 图表 JSON 保存→读取→重建 | `GraphSpec v1` 往返一致 |
| 快速连续取消旧采样任务 | 不返回陈旧图像 |

### 11.2 Compose / 集成测试

- 聊天消息流式生成时布局稳定，不出现重复卡片或突然跳转。
- 工具执行成功、失败、模型拒绝调用及工具关闭时 UI 正确。
- 普通 Markdown + LaTeX + 图卡片 + 多工具调用混排正常。
- 用户长按复制、滚动聊天、点击图卡片、全屏拖动/双指缩放不冲突。
- 深浅色、横竖屏、多窗口/大屏、字号放大、离线恢复、历史消息切换。
- APP 重启后图表仍可从持久化数据恢复；旧聊天无回归。

### 11.3 建议实际执行命令

```bash
# 在电脑现有 codex 分支的工作目录中，先检查 Java/Android SDK 与 Gradle Wrapper
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
# 已具备模拟器/手机后，再运行适用的 connected tests
./gradlew :app:connectedDebugAndroidTest
```

若现有仓库/测试配置与这些任务名不一致，先通过 `./gradlew tasks` 核对。原仓库存在自己的构建前置条件（签名、Google Services、环境版本等），不能因环境缺失就将失败归责于绘图模块；但也不能跳过解释。

### 11.4 MVP 验收条件

必须全部满足：
1. AI 能通过 `plot_function` 成功创建包含 `asin/acos/atan` 的图，且在 Android 中可见。
2. 用户明确请求三张独立图时，生成**三张不同图卡片**，不是把三条曲线误叠为一图。
3. `1/x`、`tan(x)` 不出现跨极点的明显假连接线。
4. 图中可区分刻度与图例，深色/浅色均清晰。
5. 全屏缩放、平移、复位有效，聊天列表滑动不受破坏。
6. 历史消息重新打开、离线打开无需调用模型或网络即可重绘。
7. 无效表达式安全降级，程序不崩溃。
8. 普通文字消息、其他 AI 工具、LaTeX 公式、消息复制不回归。
9. JVM 测试、可执行的 Android 构建/设备测试结果均附于交付报告；未运行的明确标注。

## 12. 建议开发里程碑与交付物

### M0：准备与代码核对
- 确认当前就是 `codex/upstream-integration-20261102`；记录当前 HEAD 与所有未提交/已暂存/未跟踪文件的状态。**不创建分支、不创建 worktree、不切换分支。**
- 重新核对 AI Tool 注册/配置路径、消息分组、持久化、测试框架。
- 记录与本文不一致的实际代码、碰撞风险与开发中遇到的约束；必要时在当前工作树添加 `IMPLEMENTATION_NOTES.md`，但不要覆盖同名既有文件。

### M1：纯 Kotlin 数学引擎
- `GraphSpec`、严格验证、表达式编译与求值、采样与断段。
- JVM 单测先过，无 Android UI 依赖。
- **验收**：`asin/acos/atan/1/x/tan` 的关键数值、域与断段测试正确。

### M2：原生绘图 UI
- Compose Canvas、坐标网格、nice ticks、曲线/图例、主题、全屏手势。
- 先在本地 Preview / 测试页用固定 GraphSpec 展示，不接 AI。
- **验收**：三种反三角函数展示可读，手势与颜色测试通过。

### M3：工具链端到端
- `PlotFunctionTool.kt`、`LocalToolOption.kt`、`LocalTools.kt`、工具 UI 注册。
- 首先保证工具内图像展示稳定；然后将图卡片升级为消息正文独立块，维护顺序/去重。
- **验收**：模型调用—参数校验—工具返回—消息展示—历史恢复完整贯通。

### M4：兼容性与交付
- 历史、多图、异常、暗色、手势、性能测试；整理修改文件、问题、运行记录。
- 第二入口 `function-graph` 可在此阶段追加，也可作为独立后续提交，不影响主 MVP。
- 交付：改动清单、测试报告、演示截图/录屏、已知限制、后续任务。

### M5（后续增强，不属于 MVP）
- 隐函数/参数曲线/极坐标；不等式与二重积分区域；坐标点标记；图片导出；参考线与 π 刻度；模型能力不足时的可靠降级。

## 13. 在现有 codex 分支开发的防冲突原则（重要）

- **只使用电脑已经在工作的 `codex/upstream-integration-20261102` 分支**；不新建分支，不新建 worktree，不对 `master` 操作，不在开始时 `pull` / `reset` / `rebase`。
- **绝不清理未提交内容**：已有修改（包括 staged / unstaged / untracked）都属于用户工作成果。更改之前查看对应文件差异；若该文件已被用户改动，只对目标函数打最小补丁并保留用户新增逻辑。
- 若本任务与已有未提交改动冲突、自动补丁将覆盖代码，**先停下这一处修改并报告**；不能通过恢复文件、覆盖整文件或自动 stash 来“解决”冲突。可以继续推进不受影响的独立新增文件。
- 优先新增 `data/graph/` 和 `ui/components/graph/` 文件；对 `LocalTools.kt`、`LocalToolOption.kt`、`ToolUI.kt`、`ChatMessage.kt` 等已有文件只作必要的局部改动。不得借机重构无关的相机、图片生成、数据库、消息处理等功能。
- 运行代码格式化、自动修复或批量生成前，确认其范围仅限本功能，否则可能触碰用户其他未提交文件。
- 完成后执行 `git status --short` 和适用的 `git diff`，列明改动文件、哪些在开发前已修改、哪些是本任务新增；**默认不要自动 add/commit/push**，由用户决定如何保存、提交或推送。
- 单元测试/构建若生成缓存或临时文件，应使用常规忽略目录；不得以 `git clean` 等命令清理用户工作区。

## 14. 推荐给电脑端 AI / Codex 的直接任务文案（复制即可）

> 请按这份《RikkaHub Android 交互式函数绘图组件——开发实施计划》进行开发。**必须在我电脑目前使用的 `fmjj-ai/rikkahub` 仓库、`codex/upstream-integration-20261102` 分支上继续工作，不要创建新分支，也不要创建 git worktree。** 我的本地可能有未提交、已暂存和未跟踪的更改：先执行 `git branch --show-current`、`git status --short`、`git diff --stat`、`git diff --cached --stat` 并查看涉及文件的差异。不要执行 reset、clean、restore、stash、checkout、switch、pull、merge、rebase，也不要用远端同名文件覆盖本地代码。若当前不在目标分支，先告诉我，不要自行切换；若某个目标修改会覆盖我的既有改动，暂停那部分并报告，其他独立任务可继续。直接在现有工作树做小范围增量改动，优先新增文件。按 M1 数学计算与单测 → M2 Compose Canvas 与手势 → M3 `plot_function` AI 工具和正文内嵌卡片 → M4 回归测试/交付的顺序完成。首版应支持 `asin/acos/atan`、`1/x`、`tan(x)`，正确处理定义域和不连续点；支持缩放、平移、复位、历史恢复，不能把 `draw_image` 或 `eval_javascript` 当数学绘图入口。保持现有 Markdown、LaTeX、AI 工具和聊天滚动行为。每阶段汇报具体改动及实际测试结果；**未经我额外明确要求，不要执行 git add/commit/push 或任何远端写入。**

---

**交付状态：这次仅更新并提供本地 Markdown 计划文件；没有修改 GitHub 源码、分支、提交或 PR。**
