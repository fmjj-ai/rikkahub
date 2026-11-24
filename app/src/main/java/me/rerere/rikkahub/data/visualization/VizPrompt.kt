package me.rerere.rikkahub.data.visualization

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

object VizPrompt {
    /** 在主聊天最终请求中追加；不参与压缩、标题和翻译，不修改历史及用户设置。 */
    fun appendToChat(messages: List<UIMessage>, enabled: Boolean): List<UIMessage> {
        if (!enabled) return messages
        if (messages.any { it.role == MessageRole.SYSTEM && it.parts.any { part ->
            part is UIMessagePart.Text && part.text == text
        } }) return messages
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        if (systemIndex < 0) return listOf(UIMessage.system(text)) + messages
        return messages.mapIndexed { index, message ->
            if (index == systemIndex) message.copy(parts = message.parts + UIMessagePart.Text(text)) else message
        }
    }

    val text = """
你可以在普通回答里使用原生交互图卡，普通问题正常回答，文字结论放在围栏外。函数图优先 plot_function，普通流程图可用 Mermaid；自由页面仍可输出 HTML/SVG/JavaScript 代码供用户预览。原生图卡不运行 JS，不导入代码或访问网络，不改变用户的流式输出设置。

新图卡使用顶层 rikka-viz-stream 围栏和 version:3。每行是一个完整 JSON 对象加换行，完整合法组件会立即显示，不等 done 或回答结束。按“最小 init → 少量必要 state → 第一项有意义组件 → 其余内容”输出，不先塞入整套轨迹或空画布；不额外请求模型。init 必填 op/version/id，可选 title、成对 width/height（默认340×220，范围32–2048）、state/actions。其余记录 seq 从1递增。最后输出 done 并闭合围栏。依赖先声明；id 为1–64字符，在数据、派生值、动作、图元、组件之间唯一，不用 __ 前缀；state 名称独立。
记录操作：${VizComponentRegistry.operations.joinToString()}。
state {op,seq,key,value} 只新增默认状态，不能覆盖用户值。
data {op,seq,id,value,inputKeys?,inputSnapshot?} 声明只读字面量，内部的 $ 不执行；轨迹可用 inputKeys 与对应 inputSnapshot 标明输入来源，输入改变会提示失效。appendData {op,seq,id,items} 只向已有数组追加完整帧，合理分批，累计最多256项。
derived {op,seq,id,value} 声明纯表达式，先声明依赖，不允许循环或覆盖。
action {op,seq,id,data}，本地 data={kind:"local",set:{状态名:表达式},when:可选布尔表达式}，右值读取同一旧快照，不改变状态类型。消息 data={kind:"message",prompt:"用户可见的请求文字",include:["状态名"],when:可选布尔表达式}，只能用户点击后发送正常聊天消息，按钮明确标明“发送给 AI”；不放URL、工具或隐藏模板。生成期间消息动作禁用，本地操作照常。
shape/replace {op,seq,id,data:图元}；remove {op,seq,id}。node/replaceNode {op,seq,id,slot?,data:组件}；removeNode {op,seq,id}。data 内不重复写 id/slot。node slot 默认 controls，可选 parameters/main/status/controls，按这个顺序布局，replaceNode 默认保持 slot 与顺序。替换目标必须已存在。主场景独立，首个图元到达才占高度。

表达式形式 {"$" : ["操作",参数…]}，不能写字符串公式。支持操作：${vizArities.keys.joinToString()}。
get(key) 仅读 state；data(id) 读只读数据；derived(id) 复用派生计算；field(对象表达式,"静态字段名") 读对象字段。var(name) 仅用于 plot 的 x、repeat 的 item/index。add/sub/mul/div/pow/min/max、eq/lt/lte/gt/gte 是二元；neg/abs/sin/cos/sqrt/not 一元；and/or 二元短路；if(条件,真值,假值) 惰性；at(数组,索引)、len(数组)、swap(数组,i,j)；cat 至少2个标量，fixed(数字,0..6) 返回定点字符串。避免除零、越界和超深 if 链。text/label/value 显示字段支持字符串、有限数字、布尔值，null 显示占位；结构字段仍必须为正确类型。

${VizComponentRegistry.description()}
所有视图/图元可用 visible 布尔表达式。row 会换行。numberInput/slider 的 min/max/step 为有限常量，min<max，step>0，numberInput 的 min/max 可省略（默认 -1e12/1e12），step 默认1；初值必须在范围内。输入确认或失焦后提交，按 min+round((value-min)/step)*step 量化并保持上下端点。select options=[{value:同类型标量,label:显示标量}]，最多32项，初值必须匹配。toggle 绑定布尔 state。
status tone=neutral/info/success/warning/error。stepper bind 为整数state，count 为整数表达式，count=0 时 bind=0并等待，其他时候0..count-1；count 通常取 len(data)，不会跳到未收到帧。可用 resetOn:["输入名"] 指定输入变化时回到首步，缺省监测该游标之外的状态。回到首步与全卡重置参数不同。
sequence items 为数值数组，mode=cells/bars，roles 为等长 normal/current/pivot/compare/sorted 数组。bars 按真实正负数值显示零基线。table columns=[{key:"字段名",label:"列名"}]，rows 为对象数组，最多8列64行，单元格为显示标量。
labeledNode x/y/w/h 为坐标尺寸，label/value/subtitle 自动排版，至少24×24，推荐留足文字空间；connector from/to 指向同一 scene 已声明的 labeledNode ID，fromAnchor/toAnchor=auto/top/right/bottom/left，route=straight/orthogonal（默认正交），arrow=none/start/end/both（默认end）。先移除引用它的连线才删除端点，不自动避开任意障碍物；复杂绕线仍用 polyline/path。
自由图元继续支持：text 的y是基线，anchor=start/middle/end，size默认12；rect r默认0；polyline points为二维点数组；path d仅支持M/L/H/V/Z及小写；repeat 使用items与shapes，plot使用xMin/xMax/samples和y表达式，scaleX/scaleY为正。scene 等比例裁剪，坐标y向下，plot数学y向上。
配色沿用应用主题；可用协调的马卡龙/莫兰迪浅填充如 #C9DDF2、#C7E8D5、#E1D2F0，配深字 #30343B；透明画布用 @onSurface。颜色支持 #RRGGBB、none、@primary、@onSurface、@surface、@outline、@success、@warning。fill/stroke 默认none（文字fill默认@onSurface），sw默认1。不要只靠颜色表达状态。
预算：每消息4块；每块${VizLimits.DOCUMENT_BYTES}字节，每行${VizLimits.RECORD_BYTES}字节，${VizLimits.RECORDS}条记录；JSON根深度0最大${VizLimits.JSON_DEPTH}，表达式最多${VizLimits.EXPRESSION_DEPTH}层；state32项，data16个，derived32个，action64个，字符串2048字符，数组256项，整树64节点，实际展开512图元，repeat64次且最多2层，plot每条2–512点且总2048，单次求值50000步。坏记录跳过并保留此前内容，修复用新序号。不要用颜色变化伪装运算；CPU步骤使用当前输入派生计算，模型预生成排序轨迹必须标明输入来源。

短完整示例：
```rikka-viz-stream
{"op":"init","version":3,"id":"sum-demo","title":"参数与派生结果","width":340,"height":130}
{"op":"state","seq":1,"key":"a","value":5}
{"op":"state","seq":2,"key":"b","value":7}
{"op":"node","seq":3,"id":"input-a","slot":"parameters","data":{"type":"numberInput","label":"A","bind":"a","min":-100,"max":100,"step":1}}
{"op":"derived","seq":4,"id":"sum","value":{"$": ["add",{"$": ["get","a"]},{"$": ["get","b"]}]}}
{"op":"shape","seq":5,"id":"result","data":{"type":"labeledNode","x":20,"y":20,"w":300,"h":84,"label":"A + B","value":{"$": ["derived","sum"]},"fill":"#C9DDF2"}}
{"op":"node","seq":6,"id":"input-b","slot":"parameters","data":{"type":"numberInput","label":"B","bind":"b","min":-100,"max":100}}
{"op":"action","seq":7,"id":"explain","data":{"kind":"message","prompt":"解释当前参数下的计算结果","include":["a","b"]}}
{"op":"node","seq":8,"id":"ask-ai","data":{"type":"button","label":"发送给 AI 解释","action":"explain"}}
{"op":"done","seq":9}
```
    """.trimIndent()
}
