package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「快速模式 / 优先级模式」开关用的闪电图标。
 *
 * hugeicons-compose 只提供 stroke（描边）变体，包内没有 solid 系列，
 * 因此这里自绘一对图标：**两者共用同一套路径数据**（[_zapPath]），仅在 fill / stroke 上区分，
 * 保证开关切换时形状完全一致、无视觉跳动。路径取自 `HugeIcons.Zap`，与工具栏其他图标同源。
 *
 * 颜色不在此处写死，由调用方通过 Icon 的 tint 控制
 * （关闭态 = 工具栏常规色，开启态 = 主题强调色，均不使用黄色）。
 */
private fun ImageVector.Builder.zapPath(fill: Brush?, stroke: Brush?) = path(
    fill = fill,
    stroke = stroke,
    strokeLineWidth = 2.0f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathFillType = PathFillType.NonZero,
) {
    moveTo(8.62814f, 12.6736f)
    horizontalLineTo(8.16918f)
    curveTo(6.68545f, 12.6736f, 5.94358f, 12.6736f, 5.62736f, 12.1844f)
    curveTo(5.31114f, 11.6953f, 5.61244f, 11.0138f, 6.21504f, 9.65083f)
    lineTo(8.02668f, 5.55323f)
    curveTo(8.57457f, 4.314f, 8.84852f, 3.69438f, 9.37997f, 3.34719f)
    curveTo(9.91142f, 3.0f, 10.5859f, 3.0f, 11.935f, 3.0f)
    horizontalLineTo(14.0244f)
    curveTo(15.6632f, 3.0f, 16.4826f, 3.0f, 16.7916f, 3.53535f)
    curveTo(17.1007f, 4.0707f, 16.6942f, 4.78588f, 15.8811f, 6.21623f)
    lineTo(14.8092f, 8.10188f)
    curveTo(14.405f, 8.81295f, 14.2029f, 9.16849f, 14.2057f, 9.45952f)
    curveTo(14.2094f, 9.83775f, 14.4105f, 10.1862f, 14.7354f, 10.377f)
    curveTo(14.9854f, 10.5239f, 15.3927f, 10.5239f, 16.2074f, 10.5239f)
    curveTo(17.2373f, 10.5239f, 17.7523f, 10.5239f, 18.0205f, 10.7022f)
    curveTo(18.3689f, 10.9338f, 18.5513f, 11.3482f, 18.4874f, 11.7632f)
    curveTo(18.4382f, 12.0826f, 18.0918f, 12.4656f, 17.399f, 13.2317f)
    lineTo(11.8639f, 19.3523f)
    curveTo(10.7767f, 20.5545f, 10.2331f, 21.1556f, 9.86807f, 20.9654f)
    curveTo(9.50303f, 20.7751f, 9.67833f, 19.9822f, 10.0289f, 18.3962f)
    lineTo(10.7157f, 15.2896f)
    curveTo(10.9826f, 14.082f, 11.1161f, 13.4782f, 10.7951f, 13.0759f)
    curveTo(10.4741f, 12.6736f, 9.85877f, 12.6736f, 8.62814f, 12.6736f)
    close()
}

/** 关闭态：描边 / 空心闪电，与工具栏其他未激活图标风格一致。 */
val ZapOutlineIcon: ImageVector
    get() {
        if (_zapOutline != null) return _zapOutline!!

        _zapOutline = ImageVector.Builder(
            name = "ZapOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            zapPath(fill = null, stroke = SolidColor(Color.Black))
        }.build()

        return _zapOutline!!
    }

private var _zapOutline: ImageVector? = null

/** 开启态：同一路径改为实心填充，配合主题强调色呈现"内部填满"的开关语义。 */
val ZapFilledIcon: ImageVector
    get() {
        if (_zapFilled != null) return _zapFilled!!

        _zapFilled = ImageVector.Builder(
            name = "ZapFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            zapPath(fill = SolidColor(Color.Black), stroke = null)
        }.build()

        return _zapFilled!!
    }

private var _zapFilled: ImageVector? = null
