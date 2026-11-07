package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen

class Navigator(
    private val backStack: MutableList<NavKey>,
    private val unanchoredOrigins: MutableMap<String, String> = mutableMapOf(),
) {
    fun navigateWithoutAnchor(screen: Screen) = navigate(screen) {
        withoutAnchor = true
    }

    fun navigate(screen: Screen, builder: NavigateOptionsBuilder.() -> Unit = {}) {
        val options = NavigateOptionsBuilder().apply(builder)

        options.popUpToScreen?.let { target ->
            val targetIndex = backStack.indexOfLast { it == target }
            if (targetIndex != -1) {
                val removeFromIndex = if (options.popUpToInclusive) targetIndex else targetIndex + 1
                repeat(backStack.size - removeFromIndex) {
                    backStack.removeLastOrNull()
                }
            }
        }

        if (options.launchSingleTop && backStack.lastOrNull() == screen) {
            return
        }

        if (unanchoredOrigins.isNotEmpty()) {
            val active = backStack.mapTo(HashSet()) { it.toString() }
            unanchoredOrigins.keys.retainAll(active)
        }
        val source = backStack.lastOrNull()
        if (options.withoutAnchor && source != null) {
            unanchoredOrigins[screen.toString()] = source.toString()
        } else {
            unanchoredOrigins.remove(screen.toString())
        }
        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        unanchoredOrigins.clear()
        backStack.clear()
        backStack.add(screen)
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
}

class NavigateOptionsBuilder {
    internal var popUpToScreen: Screen? = null
    internal var popUpToInclusive: Boolean = false
    internal var withoutAnchor: Boolean = false
    var launchSingleTop: Boolean = false

    fun popUpTo(screen: Screen, builder: PopUpToBuilder.() -> Unit = {}) {
        val options = PopUpToBuilder().apply(builder)
        popUpToScreen = screen
        popUpToInclusive = options.inclusive
    }
}

class PopUpToBuilder {
    var inclusive: Boolean = false
}

val LocalNavController = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
