package com.minwook.pleoswork

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged

/** Stable focus destinations survive replacement of a panel's composition. */
class WorkFocus {
    val targets = mutableMapOf<String, FocusRequester>()
    var current by mutableStateOf<String?>(null)
    var pending by mutableStateOf<Pair<Int, String>?>(null)
        private set
    private var serial = 0
    fun moveTo(id: String) { pending = ++serial to id }
    fun restore(id: String): Boolean {
        val target = targets[id] ?: return false
        target.requestFocus()
        return true
    }
}

val LocalWorkFocus = staticCompositionLocalOf<WorkFocus?> { null }
val LocalFocusScope = staticCompositionLocalOf { "main" }
val LocalWorkFocusEnabled = staticCompositionLocalOf { true }

@Composable
fun Modifier.workFocus(id: String?): Modifier {
    // A pager may compose neighboring pages before they are visible. Keep their
    // controls out of Tab order so traversing the current page reaches its navigation.
    val enabled = LocalWorkFocusEnabled.current
    val target = this.focusProperties { canFocus = enabled }
    val registry = LocalWorkFocus.current ?: return target
    if (id == null) return target
    val key = "${LocalFocusScope.current}/$id"
    val qa = androidx.compose.ui.platform.LocalContext.current.packageName.endsWith(".qa")
    val requester = remember(key) { FocusRequester() }
    DisposableEffect(key, requester) {
        registry.targets[key] = requester
        onDispose { if (registry.targets[key] === requester) registry.targets.remove(key) }
    }
    return target.focusRequester(requester).onFocusChanged {
        if (it.isFocused) {
            registry.current = key
            if (qa) android.util.Log.d("WorkKeyboardQA", "focus=$key")
        }
    }
}
