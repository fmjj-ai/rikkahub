package me.rerere.rikkahub.data.graph

import org.junit.Assert.*
import org.junit.Test

class GraphViewportTest {
    private val initial = GraphViewport(-1.3, 1.3, -1.3, 1.3)

    @Test fun unitCircleUsesEqualPixelRadiiOnCardsPortraitAndLandscape() {
        listOf(780 to 615, 1328 to 416, 860 to 1180, 1900 to 870).forEach { (width, height) ->
            val fitted = initial.fitAspectRatio(width, height)
            fitted.validate()
            assertEquals(width / fitted.width, height / fitted.height, 1e-8)
            assertTrue(fitted.xMin <= initial.xMin && fitted.xMax >= initial.xMax)
            assertTrue(fitted.yMin <= initial.yMin && fitted.yMax >= initial.yMax)
            assertEquals(0.0, (fitted.xMin + fitted.xMax) / 2, 1e-12)
            assertEquals(0.0, (fitted.yMin + fitted.yMax) / 2, 1e-12)
        }
        val offset = GraphViewport(10.0, 30.0, -1.0, 1.0)
        val fitted = offset.fitAspectRatio(180, 240)
        assertEquals(20.0, (fitted.xMin + fitted.xMax) / 2, 1e-12)
        assertEquals(offset.width, fitted.width, 1e-12)
        assertEquals(180 / fitted.width, 240 / fitted.height, 1e-12)
    }

    @Test fun repeatedRotationPreservesScaleAndCurrentCenterWithoutAccumulatedZoomOut() {
        val portrait = GraphViewportLayout(initial).resize(400, 800)
        val moved = portrait.copy(viewport = portrait.viewport.transform(0.5, 0.5, 0.2, -0.1, 2.0))
        var current = moved
        repeat(30) { current = current.resize(800, 400).resize(400, 800) }
        assertEquals(moved.viewport, current.viewport)
        assertEquals(current, current.resize(400, 800))
        assertEquals(current, current.resize(0, 0))
    }

    @Test fun axisZoomPreservesOtherAxisAndKeepsGestureFocus() {
        GraphZoomAxis.entries.forEach { axis ->
            val view = initial.fitAspectRatio(400, 800)
            val next = view.transform(0.25, 0.75, 0.0, 0.0, 2.0, axis)
            assertEquals(view.xMin + view.width * 0.25, next.xMin + next.width * 0.25, 1e-12)
            assertEquals(view.yMax - view.height * 0.75, next.yMax - next.height * 0.75, 1e-12)
            if (axis == GraphZoomAxis.Y) {
                assertEquals(view.xMin, next.xMin, 0.0)
                assertEquals(view.xMax, next.xMax, 0.0)
            } else assertEquals(view.width / 2, next.width, 1e-12)
            if (axis == GraphZoomAxis.X) {
                assertEquals(view.yMin, next.yMin, 0.0)
                assertEquals(view.yMax, next.yMax, 0.0)
            } else assertEquals(view.height / 2, next.height, 1e-12)
        }
    }

    @Test fun manualAxisRatioSurvivesResizeAndDefaultLayoutRestoresEqualUnits() {
        val layout = GraphViewportLayout(initial).resize(400, 800)
        val manual = layout.copy(viewport = layout.viewport.transform(0.5, 0.5, 0.0, 0.0, 2.0, GraphZoomAxis.X))
        val resized = manual.resize(800, 400)
        assertEquals(2.0, (800 / resized.viewport.width) / (400 / resized.viewport.height), 1e-12)
        assertEquals("2 : 1", resized.unitRatioLabel())
        // 恢复默认即丢弃手动视窗，按当前画布重新适配初始范围。
        val restored = GraphViewportLayout(initial).resize(800, 400)
        assertEquals(800 / restored.viewport.width, 400 / restored.viewport.height, 1e-12)
        assertEquals(initial.fitAspectRatio(800, 400), restored.viewport)
        assertEquals("1 : 1", restored.unitRatioLabel())
    }

    @Test fun eachStretchStepLengthensOnlyThatAxisAroundVisibleOrigin() {
        val view = GraphViewport(-2.0, 6.0, -1.0, 3.0)
        val wide = view.stretch(GraphZoomAxis.X, 1.25)
        assertEquals(view.width / 1.25, wide.width, 1e-12)
        assertEquals(view.xMin / view.width, wide.xMin / wide.width, 1e-12)
        assertEquals(view.yMin, wide.yMin, 0.0)
        assertEquals(view.yMax, wide.yMax, 0.0)
        val tall = view.stretch(GraphZoomAxis.Y, 1.25)
        assertEquals(view.height / 1.25, tall.height, 1e-12)
        assertEquals(view.yMax / view.height, tall.yMax / tall.height, 1e-12)
        assertEquals(view.xMin, tall.xMin, 0.0)
        assertEquals(view.xMax, tall.xMax, 0.0)
        // 原点不在视窗内时固定画布中心。
        val offset = GraphViewport(10.0, 30.0, 5.0, 9.0).stretch(GraphZoomAxis.X, 1.25)
        assertEquals(20.0, (offset.xMin + offset.xMax) / 2, 1e-12)
        assertEquals(16.0, offset.width, 1e-12)
    }

    @Test fun repeatedStretchClicksAccumulateAndPinchKeepsTheChosenRatio() {
        var layout = GraphViewportLayout(initial).resize(400, 800)
        assertEquals("1 : 1", layout.unitRatioLabel())
        assertTrue(layout.viewport.hasEqualUnits(400, 800))
        repeat(2) { layout = layout.copy(viewport = layout.viewport.stretch(GraphZoomAxis.X, 1.25)) }
        assertEquals("1.56 : 1", layout.unitRatioLabel())
        assertFalse(layout.viewport.hasEqualUnits(400, 800))
        val pinched = layout.copy(viewport = layout.viewport.transform(0.3, 0.6, 0.1, -0.2, 3.0))
        assertEquals("1.56 : 1", pinched.unitRatioLabel())
        val tall = GraphViewportLayout(initial).resize(400, 800).let {
            it.copy(viewport = it.viewport.stretch(GraphZoomAxis.Y, 1.25))
        }
        assertEquals("1 : 1.25", tall.unitRatioLabel())
    }

    @Test fun equalUnitViewsShareOneTickStepAndStretchedAxesPickTheirOwn() {
        listOf(780 to 615, 1328 to 416, 860 to 1180, 1900 to 870).forEach { (width, height) ->
            val view = GraphViewport().fitAspectRatio(width, height)
            val shared = graphAxisTicks(view, width, height, 9, 4)
            assertEquals(shared.xStep, shared.yStep, 0.0)
            assertEquals(maxOf(graphTickStep(view.width, 9), graphTickStep(view.height, 4)), shared.xStep, 0.0)
            // 相邻网格线的像素间距在两个方向上一致，网格为正方形。
            assertEquals(shared.xStep / view.width * width, shared.yStep / view.height * height, 1e-6)
            assertTrue(shared.x.isNotEmpty() && shared.y.isNotEmpty())
            assertTrue(shared.x.all { it in view.xMin..view.xMax } && shared.y.all { it in view.yMin..view.yMax })
            val stretched = view.stretch(GraphZoomAxis.X, 4.0)
            val own = graphAxisTicks(stretched, width, height, 9, 4)
            assertEquals(graphTickStep(stretched.width, 9), own.xStep, 0.0)
            assertEquals(graphTickStep(stretched.height, 4), own.yStep, 0.0)
        }
    }

    @Test fun wideCanvasTicksCoverTheWholeAxisWithoutTruncation() {
        // 平板横屏的横轴需要 21 个刻度，刻度序列必须一直排到画布右端。
        val view = GraphViewport().fitAspectRatio(2560, 1200)
        val ticks = graphAxisTicks(view, 2560, 1200, 24, 15)
        assertEquals(1.0, ticks.xStep, 1e-12)
        assertEquals(1.0, ticks.yStep, 1e-12)
        assertEquals(21, ticks.x.size)
        assertEquals(-10.0, ticks.x.first(), 1e-9)
        assertEquals(10.0, ticks.x.last(), 1e-9)
        assertTrue(ticks.y.size in 9..11)
        assertTrue(view.xMax - ticks.x.last() < ticks.xStep && ticks.x.first() - view.xMin < ticks.xStep)
    }

    @Test fun equalUnitsSurviveLongPinchAndPanSequences() {
        var view = initial.fitAspectRatio(1080, 1700)
        repeat(2000) { view = view.transform(0.3, 0.7, 0.01, -0.02, if (it % 2 == 0) 1.07 else 1 / 1.07) }
        view.validate()
        assertTrue(view.hasEqualUnits(1080, 1700))
        val ticks = graphAxisTicks(view, 1080, 1700, 10, 17)
        assertEquals(ticks.xStep, ticks.yStep, 0.0)
    }

    @Test fun combinedZoomAtSpanLimitsKeepsEqualUnits() {
        val original = initial.fitAspectRatio(400, 800)
        var view = original
        repeat(50) {
            view = view.transform(0.5, 0.5, 0.0, 0.0, 1e20)
            view.validate()
            assertEquals(0.5, view.width / view.height, 1e-10)
        }
        repeat(50) {
            view = view.transform(0.5, 0.5, 0.0, 0.0, 1e-20)
            view.validate()
            assertEquals(0.5, view.width / view.height, 1e-10)
        }
    }

    @Test fun extremeCoordinatesAndAspectRatiosStayFiniteAndValid() {
        listOf(GraphViewport(), GraphViewport(1e12 - 1.0, 1e12, -1e12, -1e12 + 1.0),
            GraphViewport(0.0, 1e-6, 0.0, 1e9)).forEach { view ->
            listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, 400 to 800).forEach { (width, height) ->
                val fitted = view.fitAspectRatio(width, height)
                fitted.validate()
                GraphZoomAxis.entries.forEach { axis ->
                    fitted.transform(0.1, 0.9, 1e20, -1e20, 1e20, axis).validate()
                    fitted.transform(0.1, 0.9, -1e20, 1e20, 1e-20, axis).validate()
                    var stretched = fitted
                    repeat(120) {
                        stretched = stretched.stretch(axis, 1.25)
                        stretched.validate()
                    }
                }
            }
        }
        assertEquals(initial, initial.fitAspectRatio(0, 200))
    }
}
