package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import me.rerere.rikkahub.ui.components.nav.LocalExpandedNavigation
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope

internal fun assistantAvatarOverlayZIndex(expandedNavigation: Boolean): Float =
    if (expandedNavigation) 2f else 0f

@Composable
internal fun Modifier.assistantAvatarNavigation(key: String): Modifier = heroAnimation(
    key = key,
    zIndexInOverlay = assistantAvatarOverlayZIndex(LocalExpandedNavigation.current),
)

@Composable
fun Modifier.heroAnimation(
    key: Any,
    zIndexInOverlay: Float = 0f,
): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    return with(sharedTransitionScope) {
        this@heroAnimation.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope,
            zIndexInOverlay = zIndexInOverlay,
        )
    }
}
