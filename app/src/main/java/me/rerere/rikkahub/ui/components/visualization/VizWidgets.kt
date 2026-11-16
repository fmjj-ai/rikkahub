package me.rerere.rikkahub.ui.components.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.visualization.*

@Composable
internal fun VizWidget(
    session: VizSession,
    node: VizNode,
    presentation: VizPresentationState,
    sessionKey: String,
) {
    val v = node.values
    val stale = v["stale"]?.bool() == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (stale) Text("输入已变化，需要重新生成演示", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        when (node.type) {
            "numberInput" -> NumberInput(session, node, presentation, sessionKey)
            "select" -> SelectInput(session, node)
            "toggle" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = session.frame.state.value(v.string("bind")).bool(),
                    onCheckedChange = { session.enqueueInput(node, JsonPrimitive(it)) },
                    modifier = Modifier.semantics { contentDescription = v.string("label") })
                Text(v.string("label"))
            }
            "status" -> {
                val color = when (v["tone"]?.str()) {
                    "error" -> MaterialTheme.colorScheme.errorContainer
                    "warning" -> MaterialTheme.colorScheme.tertiaryContainer
                    "success", "info" -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
                Surface(color = color, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.padding(12.dp)) {
                        v["label"]?.let { Text(it.displayText(), style = MaterialTheme.typography.labelMedium) }
                        Text(v.string("text"))
                    }
                }
            }
            "stepper" -> {
                val count = v.value("count").int()
                val index = session.frame.state.value(v.string("bind")).int()
                v["label"]?.let { Text(it.displayText(), style = MaterialTheme.typography.labelLarge) }
                Text(if (count == 0) "等待步骤" else "第 ${index + 1} / $count 步")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { session.enqueueInput(node, JsonPrimitive(0)) }, enabled = !stale && index > 0) { Text("回到首步") }
                    OutlinedButton(onClick = { session.enqueueInput(node, JsonPrimitive(index - 1)) }, enabled = !stale && index > 0) { Text("上一步") }
                    Button(onClick = { session.enqueueInput(node, JsonPrimitive(index + 1)) }, enabled = !stale && index + 1 < count) { Text("下一步") }
                }
            }
            "sequence" -> Sequence(node)
            "table" -> Table(node)
        }
    }
}

@Composable
private fun NumberInput(
    session: VizSession,
    node: VizNode,
    presentation: VizPresentationState,
    sessionKey: String,
) {
    val v = node.values
    val nodeId = v.string("id")
    val renderedInViewer = remember(sessionKey) { presentation.active?.sessionKey == sessionKey }
    val number = session.frame.state.value(v.string("bind"))
    val saved = presentation.numberDraft(sessionKey, nodeId)?.takeIf {
        it.resetRevision == session.resetRevision
    }
    var draft by remember(session, nodeId, session.resetRevision) {
        mutableStateOf(saved?.text ?: number.displayText())
    }
    var focused by remember { mutableStateOf(false) }
    var dirty by remember(session, session.resetRevision) { mutableStateOf(saved?.dirty == true) }
    var issue by remember(session, session.resetRevision) { mutableStateOf(saved?.issue) }
    LaunchedEffect(number, session.resetRevision, session.error) {
        if (!focused && !dirty) draft = number.displayText()
    }
    fun saveDraft() {
        presentation.saveNumberDraft(
            sessionKey,
            nodeId,
            VizNumberDraft(draft, focused, dirty, issue, session.resetRevision),
        )
    }
    SideEffect { saveDraft() }
    fun commit() {
        if (!dirty) return
        val value = draft.toDoubleOrNull()
        if (value == null || !value.isFinite() || value !in v.number("min")..v.number("max")) {
            issue = "请输入 ${v.value("min").displayText()} 到 ${v.value("max").displayText()} 之间的数值"
            return
        }
        val quantized = VizEngine.quantize(value, v.number("min"), v.number("max"), v["step"]?.num() ?: 1.0)
        draft = JsonPrimitive(quantized).displayText()
        dirty = false
        issue = null
        session.enqueueInput(node, JsonPrimitive(value))
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it; dirty = true; issue = null; saveDraft() },
        label = { Text(v.string("label")) },
        isError = issue != null,
        supportingText = issue?.let { text -> { Text(text) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.widthIn(min = 140.dp, max = 280.dp).onFocusChanged {
            // 展开/关闭导致 renderer 转移，不应把尚未确认的输入当作普通失焦提交。
            if (focused && !it.isFocused &&
                renderedInViewer == (presentation.active?.sessionKey == sessionKey)
            ) commit()
            focused = it.isFocused
            saveDraft()
        },
    )
}

@Composable
private fun SelectInput(session: VizSession, node: VizNode) {
    val v = node.values
    var expanded by remember { mutableStateOf(false) }
    val selected = session.frame.state.value(v.string("bind"))
    val options = v.value("options").arr().map { it.obj() }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("${v.string("label")}：${options.first { equalValues(it.value("value"), selected) }.value("label").displayText()}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.value("label").displayText()) }, onClick = {
                    expanded = false
                    session.enqueueInput(node, option.value("value"))
                })
            }
        }
    }
}

private fun roleLabel(role: String) = when (role) {
    "current" -> "当前"; "pivot" -> "基准"; "compare" -> "比较"; "sorted" -> "已排序"; else -> ""
}

@Composable
private fun Sequence(node: VizNode) {
    val v = node.values
    val items = v.value("items").arr()
    val roles = v["roles"]?.arr()?.map { it.str() } ?: List(items.size) { "normal" }
    val colors = MaterialTheme.colorScheme
    v["label"]?.let { Text(it.displayText(), style = MaterialTheme.typography.labelLarge) }
    if (items.isEmpty()) { Text("空序列", style = MaterialTheme.typography.bodySmall); return }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val numbers = items.map { it.num() }
        val high = maxOf(0.0, numbers.maxOrNull() ?: 0.0)
        val low = minOf(0.0, numbers.minOrNull() ?: 0.0)
        val range = (high - low).takeIf { it > 0.0 } ?: 1.0
        items.forEachIndexed { index, value ->
            val role = roles[index]
            val color = when (role) {
                "current", "pivot" -> colors.primary
                "compare" -> colors.tertiary
                "sorted" -> colors.secondary
                else -> colors.outline
            }
            Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (v["mode"]?.str() == "bars") {
                    val baseline = (high / range).toFloat()
                    val end = ((high - value.num()) / range).toFloat()
                    Canvas(Modifier.fillMaxWidth().height(110.dp).semantics {
                        contentDescription = "第 ${index + 1} 项 ${value.displayText()} ${roleLabel(role)}"
                    }) {
                        val y0 = size.height * baseline
                        val y1 = size.height * end
                        drawRect(color.copy(alpha = 0.65f), Offset(10.dp.toPx(), minOf(y0, y1)),
                            Size((size.width - 20.dp.toPx()).coerceAtLeast(1f), kotlin.math.abs(y0 - y1)))
                        drawLine(colors.onSurface, Offset(0f, y0), Offset(size.width, y0), 1.dp.toPx())
                    }
                }
                Surface(color = color.copy(alpha = 0.13f), shape = MaterialTheme.shapes.small) {
                    Text(value.displayText(), Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Text(roleLabel(role).ifEmpty { "${index + 1}" }, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Table(node: VizNode) {
    val v = node.values
    val columns = v.value("columns").arr().map { it.obj() }
    val rows = v.value("rows").arr()
    v["label"]?.let { Text(it.displayText(), style = MaterialTheme.typography.labelLarge) }
    if (rows.isEmpty()) { Text("暂无数据", style = MaterialTheme.typography.bodySmall); return }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            columns.forEach { Text(it.value("label").displayText(), Modifier.width(132.dp).padding(8.dp), style = MaterialTheme.typography.labelLarge) }
        }
        HorizontalDivider()
        rows.forEach { row ->
            Row {
                columns.forEach { column -> Text((row.obj()[column.string("key")] ?: JsonNull).displayText(),
                    Modifier.width(132.dp).padding(8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
