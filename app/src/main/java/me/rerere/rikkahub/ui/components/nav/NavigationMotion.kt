package me.rerere.rikkahub.ui.components.nav

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope

private const val EXPANDABLE_NAVIGATION_ENTRY = "rikkahub.expandableNavigation"
private const val NAVIGATION_SCREEN_KEY = "rikkahub.navigationScreen"
private const val EXPANSION_DURATION = 300

internal val LocalExpandedNavigation = compositionLocalOf { false }
internal val LocalUnanchoredNavigation = compositionLocalOf<Map<String, String>> { emptyMap() }

private data class NavigationContainerKey(val route: Screen)
private data class NavigationAnchor(
    val source: Screen,
    val additionalSource: ((Screen) -> Boolean)?,
)

private val containerBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = EXPANSION_DURATION, easing = FastOutSlowInEasing)
}
private val containerShape = RoundedCornerShape(18.dp)

internal fun canExpandNavigation(source: Map<String, Any>?, destination: Map<String, Any>?): Boolean {
    val anchor = destination?.get(EXPANDABLE_NAVIGATION_ENTRY) as? NavigationAnchor ?: return false
    val sourceRoute = source?.get(NAVIGATION_SCREEN_KEY) as? Screen ?: return false
    return anchor.source == sourceRoute || anchor.additionalSource?.invoke(sourceRoute) == true
}

internal fun unanchoredNavigationTransition(): ContentTransform =
    fadeIn(tween(190, easing = FastOutSlowInEasing)) togetherWith
        fadeOut(tween(190, easing = FastOutSlowInEasing))

internal fun expandedNavigationTransition(): ContentTransform {
    // 共享容器负责内容交接。底页保持可见到接近结束，避免父场景先淡出而露出底色。
    val exit = fadeOut(tween(durationMillis = 60, delayMillis = EXPANSION_DURATION - 60))
    return EnterTransition.None togetherWith exit
}

@Composable
internal fun navigationSourceModifiers(vararg routes: Screen): Map<Screen, Modifier> {
    if (!LocalExpandedNavigation.current) return routes.associateWith { Modifier }

    val sources = mutableMapOf<Screen, Modifier>()
    for (route in routes) {
        sources[route] = key(route) { Modifier.navigationSource(route) }
    }
    return sources
}

@Composable
internal fun Modifier.navigationSource(route: Screen): Modifier {
    if (!LocalExpandedNavigation.current) return this
    return navigationContainerBounds(route, isPage = false)
}

@Composable
private fun Modifier.navigationContainerBounds(route: Screen, isPage: Boolean): Modifier {
    val visibilityScope = LocalNavAnimatedContentScope.current
    return with(LocalSharedTransitionScope.current) {
        this@navigationContainerBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(NavigationContainerKey(route)),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = containerBoundsTransform,
            // 新内容先出现，再隐去旧内容，减少交接时两边都半透明的时段。
            enter = if (isPage) fadeIn(tween(95, delayMillis = 55)) else fadeIn(tween(90, delayMillis = 40)),
            exit = if (isPage) fadeOut(tween(90, delayMillis = 90)) else fadeOut(tween(80, delayMillis = 120)),
            // 卡片内容保持原尺寸，页面内容则在最终尺寸布局，避免转场中重新换行。
            resizeMode = if (isPage) ResizeMode.RemeasureToBounds
            else ResizeMode.scaleToBounds(ContentScale.None, Alignment.TopStart),
            zIndexInOverlay = if (isPage) 1f else 0f,
            clipInOverlayDuringTransition = OverlayClip(containerShape),
        )
    }
}

internal inline fun <reified T : Screen> EntryProviderScope<NavKey>.expandableNavigationEntry(
    source: Screen,
    noinline additionalSource: ((Screen) -> Boolean)? = null,
    crossinline content: @Composable (T) -> Unit,
) {
    expandableNavigationEntry<T>(sourceFor = { source }, additionalSource = additionalSource) { content(it) }
}

internal inline fun <reified T : Screen> EntryProviderScope<NavKey>.expandableNavigationEntry(
    crossinline sourceFor: (T) -> Screen,
    noinline additionalSource: ((Screen) -> Boolean)? = null,
    crossinline content: @Composable (T) -> Unit,
) {
    entry<T>(
        metadata = { route -> expandableNavigationMetadata(route, sourceFor(route), additionalSource) },
    ) { route ->
        ExpandedNavigationPage(route) { content(route) }
    }
}

internal fun navigationScreenMetadata(route: Screen): Map<String, Any> =
    mapOf(NAVIGATION_SCREEN_KEY to route)

internal fun expandableNavigationMetadata(
    route: Screen,
    source: Screen,
    additionalSource: ((Screen) -> Boolean)? = null,
): Map<String, Any> =
    navigationScreenMetadata(route) + (EXPANDABLE_NAVIGATION_ENTRY to NavigationAnchor(source, additionalSource))

@Composable
internal fun ExpandedNavigationPage(route: Screen, content: @Composable () -> Unit) {
    if (!LocalExpandedNavigation.current || route.toString() in LocalUnanchoredNavigation.current) {
        content()
        return
    }

    val bounds = Modifier
        .navigationContainerBounds(route, isPage = true)
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    // 页面始终按最终尺寸布局，只在过渡中按共享边界裁切，避免文字和列表反复重排。
    val sharedTransitionScope = LocalSharedTransitionScope.current
    Box(modifier = with(sharedTransitionScope) { bounds.skipToLookaheadSize() }) {
        content()
    }
}
