package me.rerere.rikkahub.ui.components.visualization

import me.rerere.rikkahub.data.visualization.VizFrame
import me.rerere.rikkahub.data.visualization.VizNode
import me.rerere.rikkahub.data.visualization.str

internal enum class VizControlGroup(val title: String) { Actions("操作"), Parameters("参数"), Information("信息") }

internal data class VizFullscreenLayout(
    val scenes: List<VizNode>,
    val groups: Map<VizControlGroup, List<VizNode>>,
)

/** 只投影已编译节点；不解析协议、不复制动作、不深入 scene 的图元。 */
internal fun projectFullscreen(frame: VizFrame): VizFullscreenLayout {
    val scenes = mutableListOf<VizNode>()
    fun collect(node: VizNode) {
        if (node.type == "scene") scenes += node else node.children.forEach(::collect)
    }
    frame.nodes.forEach(::collect)
    fun project(node: VizNode, group: VizControlGroup, inherited: VizControlGroup?): VizNode? {
        if (node.type == "scene") return null
        val declared = when (node.values["slot"]?.str()) {
            "controls" -> VizControlGroup.Actions
            "parameters" -> VizControlGroup.Parameters
            "status", "main" -> VizControlGroup.Information
            else -> inherited
        }
        if (node.type == "row" || node.type == "column") {
            val children = node.children.mapNotNull { project(it, group, declared) }
            return if (children.isEmpty()) null else node.copy(children = children)
        }
        val category = declared ?: when (node.type) {
            "button", "stepper" -> VizControlGroup.Actions
            "slider", "numberInput", "select", "toggle" -> VizControlGroup.Parameters
            else -> VizControlGroup.Information
        }
        return node.takeIf { category == group }
    }
    val groups = VizControlGroup.entries.associateWith { group ->
        frame.nodes.mapNotNull { project(it, group, null) }
    }.filterValues { it.isNotEmpty() }
    return VizFullscreenLayout(scenes, groups)
}
