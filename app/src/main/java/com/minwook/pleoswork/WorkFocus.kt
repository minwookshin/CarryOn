package com.minwook.pleoswork

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

@Composable
fun Modifier.workFocus(id: String?): Modifier {
    val registry = LocalWorkFocus.current ?: return this
    if (id == null) return this
    val key = "${LocalFocusScope.current}/$id"
    val qa = androidx.compose.ui.platform.LocalContext.current.packageName.endsWith(".qa")
    val requester = remember(key) { FocusRequester() }
    DisposableEffect(key, requester) {
        registry.targets[key] = requester
        onDispose { if (registry.targets[key] === requester) registry.targets.remove(key) }
    }
    return this.focusRequester(requester).onFocusChanged {
        if (it.isFocused) {
            registry.current = key
            if (qa) android.util.Log.d("WorkKeyboardQA", "focus=$key")
        }
    }
}
