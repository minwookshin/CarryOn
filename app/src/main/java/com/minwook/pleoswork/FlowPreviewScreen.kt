package com.minwook.pleoswork

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

/** A reversible, local walkthrough. Closing it restores the untouched driver's workflow. */
@Composable fun FlowPreviewScreen(initialMode: ServiceMode, car: VehicleState, close: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    val scenes = remember(mode) { FlowPreview.scenes(mode, System.currentTimeMillis()) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var elapsed by rememberSaveable { mutableLongStateOf(0) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val focus = LocalWorkFocus.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val scene = scenes[index.coerceIn(scenes.indices)]
    val state = remember(scene, elapsed) { FlowPreview.stateAt(scene, elapsed) }
    val finished = index == scenes.lastIndex && elapsed >= scene.durationMs
    fun seek(next: Int) { playing = false; index = next.coerceIn(scenes.indices); elapsed = 0 }
    fun chooseMode(next: ServiceMode) {
        if (mode != next) { playing = false; index = 0; elapsed = 0; mode = next }
    }
    fun dismissChooser() { choosing = false; focus?.moveTo("preview/scenes") }
    BackHandler { close() }
    LaunchedEffect(lifecycle, car.canWork) {
        if (!lifecycle.isAtLeast(Lifecycle.State.STARTED) || !car.canWork) playing = false
    }
    LaunchedEffect(playing, index, lifecycle, car.canWork) {
        if (playing && car.canWork && lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
            while (true) {
                delay(1_000)
                elapsed = minOf(elapsed + 1_000, scene.durationMs)
                if (elapsed >= scene.durationMs) {
                    if (index < scenes.lastIndex) { index++; elapsed = 0 } else playing = false
                    break
                }
            }
        }
    }
    CompositionLocalProvider(LocalFocusScope provides "preview") {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconAction(R.drawable.ic_close, "흐름 보기 닫기 · 기존 작업으로", focusKey = "close", onClick = close)
                Text("흐름 보기", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                ServiceMode.entries.forEach { option ->
                    ActionButton(option.label, Modifier.width(152.dp), isSelected = mode == option, enabled = car.canWork,
                        icon = if (option == ServiceMode.FREIGHT) R.drawable.ic_truck else R.drawable.ic_delivery) { chooseMode(option) }
                }
            }
            Text("이동·도착·단말 결과 시뮬레이션", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1} / ${scenes.size} · ${scene.label}", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium)
                IconAction(R.drawable.ic_list, "전체 장면 순서", enabled = car.canWork, focusKey = "scenes") { playing = false; choosing = true }
            }
            if (!car.canWork) Text("정차 후 P에서 재생할 수 있습니다", Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 1150.dp
                if (wide) Row(Modifier.fillMaxSize()) {
                    FlowPreviewMap(state, scene.notice, Modifier.weight(.54f).fillMaxHeight(), compact = false)
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(.46f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        GuidedCurrentStop(state, car, System.currentTimeMillis()) {}
                    }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FlowPreviewMap(state, scene.notice, Modifier.fillMaxWidth().height(280.dp), compact = true)
                    Column(Modifier.fillMaxWidth().padding(24.dp)) { GuidedCurrentStop(state, car, System.currentTimeMillis()) {} }
                }
            }
            LinearProgressIndicator(progress = { (index + (elapsed.toFloat() / scene.durationMs)) / scenes.size },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconAction(R.drawable.ic_arrow_left, "이전 장면", enabled = car.canWork && index > 0, focusKey = "previous") { seek(index - 1) }
                ActionButton(if (finished) "처음부터 다시 보기" else if (playing) "일시정지" else if (index == 0 && elapsed == 0L) "흐름 재생" else "이어서 재생",
                    Modifier.weight(1f), enabled = car.canWork,
                    icon = if (playing) R.drawable.ic_pause else if (finished) R.drawable.ic_reset else R.drawable.ic_play,
                    focusKey = "play") {
                    if (finished) { index = 0; elapsed = 0 }
                    playing = !playing
                }
                IconAction(R.drawable.ic_arrow_right, "다음 장면", enabled = car.canWork && index < scenes.lastIndex, focusKey = "next") { seek(index + 1) }
            }
        }
        if (choosing) AlertDialog(onDismissRequest = ::dismissChooser, shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
            title = { Text("장면 선택", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scenes.forEachIndexed { number, item ->
                        ActionButton("${number + 1}. ${item.label}", Modifier.fillMaxWidth(), isSelected = number == index,
                            enabled = car.canWork, focusKey = "scene-$number") { seek(number); choosing = false; focus?.moveTo("preview/play") }
                        Text(item.state.run!!.visitName, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { QuietButton(onClick = ::dismissChooser) { Text("닫기") } })
    }
}

@Composable private fun FlowPreviewMap(state: CompanionState, notice: FlowPreviewNotice?, modifier: Modifier, compact: Boolean) {
    Box(modifier) {
        DemoNavigationPane(state, Modifier.fillMaxSize(), compact)
        // In-app notice: no OS overlay, native navigation popup or focus-grabbing modal.
        if (notice != null) Surface(Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp).padding(top = 88.dp)
            .fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            shape = RectangleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CarbonIcon(R.drawable.ic_location, null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(notice.title, style = MaterialTheme.typography.titleLarge)
                    Text(notice.body, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
