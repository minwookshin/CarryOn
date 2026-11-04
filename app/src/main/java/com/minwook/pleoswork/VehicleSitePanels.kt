package com.minwook.pleoswork

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.ceil

fun sitePanelTitle(panel: String, mode: ServiceMode) = when (panel) {
    "dispatch" -> "운송 정보"
    "entrance" -> "진입로"
    "bay" -> if (mode == ServiceMode.PARCEL) "정차·하차 위치" else "도크·대기 위치"
    "cargo" -> "적재함"
    "link" -> "단말 연결"
    else -> "주차 기울기"
}

@Composable fun SiteOverview(s: CompanionState, nav: NavReading?, selected: String, select: (String) -> Unit) {
    val site = s.site
    val realNav = nav?.takeIf { !s.demo }
    val seconds = site?.let { it.seconds + s.trafficDelayMinutes * 60 } ?: realNav?.seconds
    val meters = site?.meters ?: realNav?.meters
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (s.demo) "현재 방문지" else "내비 목적지", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(site?.name ?: realNav?.target?.name ?: if (s.demo && s.terminalConnected && s.driver?.assignment == null) "배차 대기" else "방문지 미수신", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text(site?.location ?: if (!s.demo) "현장 정보 미연결" else if (!s.terminalConnected) "단말 연결 끊김" else "${s.driver?.carrier} · 등록된 배차가 없습니다", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CurrentLoadSummary(s)
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CarbonIcon(R.drawable.ic_location, null)
            Column(Modifier.weight(1f)) {
                Text(if (seconds == null) "도착 시간 —" else "${ceil(seconds / 60.0).toInt()}분 남음", style = MaterialTheme.typography.titleLarge)
                Text(if (meters == null) "거리 —" else String.format(Locale.US, "%.1f km", meters / 1000.0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (site == null) Text(if (realNav != null) "내비 연결" else "미수신", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DispatchTiming(s)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SiteItem("dispatch", "운송 정보", s.dispatch?.let { "현재 ${it.currentIndex + 1} / ${it.stops.size} · ${it.current.operation}" } ?: "정보 미수신", R.drawable.ic_list, selected, select)
            SiteItem("entrance", "진입로", s.entrance?.name ?: "정보 미수신", R.drawable.ic_location, selected, select)
            SiteItem("bay", if (s.mode == ServiceMode.PARCEL) "정차·하차 위치" else "도크·대기 위치", s.bay?.name ?: "정보 미수신", R.drawable.ic_delivery, selected, select)
        }
    }
}

@Composable private fun SiteItem(id: String, label: String, value: String, icon: Int, selected: String, select: (String) -> Unit) {
    val active = id == selected
    Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.listRow)
        .background(if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
        .carFocusOutline(id).clickable(role = Role.Button, onClickLabel = "$label 보기") { select(id) }
        .semantics(mergeDescendants = true) { this.selected = active }
        .padding(vertical = 16.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.width(3.dp).height(44.dp).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface))
        CarbonIcon(icon, null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CarbonIcon(R.drawable.ic_arrow_right, null)
    }
}

@Composable fun EntrancePanel(s: CompanionState, car: VehicleState, repo: CompanionRepository) {
    val site = s.site
    if (site == null) { SiteMissing(s); return }
    SiteDiagram(s, false)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("진입구 선택", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text("차고 ${height(s.vehicleHeightCm)} · 예시", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    site.entrances.forEachIndexed { index, entrance ->
        val blocked = entrance.heightCm <= s.vehicleHeightCm
        SiteChoice(entrance.name, "제한 ${height(entrance.heightCm)} · ${if (blocked) "설정 차고보다 낮음" else entrance.note}", s.entranceIndex == index,
            enabled = !blocked && car.canWork, id = "entrance-$index", warning = blocked) {
            repo.update { CompanionReducer.entrance(it, index, car.canWork) }
        }
    }
    Text("구역도·높이는 예시입니다. 현장 표지와 실제 차량 높이를 확인하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun BayPanel(s: CompanionState, car: VehicleState, repo: CompanionRepository) {
    val site = s.site
    if (site == null) { SiteMissing(s); return }
    SiteDiagram(s, true)
    site.bays.forEachIndexed { index, bay ->
        SiteChoice(bay.name, "${bay.side} · ${bay.note}", s.bayIndex == index, car.canWork, "bay-$index") {
            repo.update { CompanionReducer.bay(it, index, car.canWork) }
        }
    }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CarbonIcon(R.drawable.ic_time, null)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(site.waitingPlace, style = MaterialTheme.typography.titleMedium)
            Text("정차 구역이 사용 중일 때 · 예시", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One entrance-to-stop path; alternatives appear only when the driver asks to change one. */
@Composable fun SiteAccessPanel(s: CompanionState, car: VehicleState, repo: CompanionRepository) {
    val site = s.site ?: run { SiteMissing(s); return }
    var entrances by rememberSaveable(s.run?.navigationVisitId) { mutableStateOf(false) }
    var bays by rememberSaveable(s.run?.navigationVisitId) { mutableStateOf(false) }
    val assignedDock = s.mode == ServiceMode.FREIGHT && s.run?.returning != true
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("현장 동선 · 예시", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AccessStep(R.drawable.ic_location, "진입 · ${s.entrance?.name ?: "미수신"}",
            s.entrance?.let { "높이 제한 ${height(it.heightCm)} · ${it.note}" } ?: "진입구 정보 미수신")
        Box(Modifier.padding(start = 15.dp).width(2.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
        AccessStep(R.drawable.ic_truck, "${if (assignedDock && s.run?.result?.dock.isNullOrBlank()) "대기" else "정차"} · ${s.stoppingPlace()}",
            if (assignedDock) if (s.run?.result?.dock.isNullOrBlank()) "도크 배정은 단말에서 받습니다" else "배정 도크 · 후면 접안"
            else listOfNotNull(s.bay?.side, s.bay?.note).joinToString(" · "))
    }
    Text("차고 ${height(s.vehicleHeightCm)} · 구역·높이는 예시입니다. 현장 표지를 확인하세요.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (site.entrances.size > 1) {
        QuietButton(onClick = { entrances = !entrances }, enabled = car.canWork,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (entrances) "펼쳐짐" else "접힘" }, focusKey = "entrance-options") {
            Text(if (entrances) "진입구 선택 닫기" else "다른 진입구 확인", Modifier.weight(1f)); CarbonIcon(R.drawable.ic_location, null)
        }
        if (entrances) site.entrances.forEachIndexed { index, entrance ->
            val blocked = entrance.heightCm <= s.vehicleHeightCm
            SiteChoice(entrance.name, "제한 ${height(entrance.heightCm)} · ${if (blocked) "설정 차고보다 낮음" else entrance.note}",
                s.entranceIndex == index, !blocked && car.canWork, "entrance-$index", blocked) {
                repo.update { CompanionReducer.entrance(it, index, car.canWork) }
            }
        }
    }
    if (!assignedDock && site.bays.size > 1) {
        QuietButton(onClick = { bays = !bays }, enabled = car.canWork,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (bays) "펼쳐짐" else "접힘" }, focusKey = "bay-options") {
            Text(if (bays) "정차 구역 선택 닫기" else "다른 정차 구역 확인", Modifier.weight(1f)); CarbonIcon(R.drawable.ic_truck, null)
        }
        if (bays) site.bays.forEachIndexed { index, bay ->
            SiteChoice(bay.name, "${bay.side} · ${bay.note}", s.bayIndex == index, car.canWork, "bay-$index") {
                repo.update { CompanionReducer.bay(it, index, car.canWork) }
            }
        }
    }
    if (s.stoppingPlace() != site.waitingPlace) Text("대기 위치 · ${site.waitingPlace}",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun AccessStep(icon: Int, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CarbonIcon(icon, null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SiteMissing(s: CompanionState) {
    Text(if (!s.demo) "현장 정보 미연결" else if (!s.terminalConnected) "단말 연결 끊김" else "배차 대기", style = MaterialTheme.typography.titleLarge)
    Text("진입구와 정차 구역을 수신하면 이곳에 표시합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun SiteChoice(label: String, detail: String, selected: Boolean, enabled: Boolean, id: String, warning: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.listRow).background(MaterialTheme.colorScheme.surfaceVariant)
        .carFocusOutline(id).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) { stateDescription = if (warning) "설정 차고 초과, 선택할 수 없음" else if (selected) "선택됨" else "선택 안 됨" }
        .padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(44.dp).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (warning) CarbonIcon(R.drawable.ic_warning, "높이 제한")
        else Text(if (selected) "선택" else "변경", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}

/** An abstract yard diagram; intentionally not a map or vehicle camera feed. */
@Composable private fun SiteDiagram(s: CompanionState, bayView: Boolean) {
    val site = s.site ?: return
    val line = MaterialTheme.colorScheme.outlineVariant
    val blue = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().background(surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (bayView) { if (s.mode == ServiceMode.PARCEL) "정차 구역" else "도크 배치" } else "현장 진입", style = MaterialTheme.typography.titleMedium)
            Text("구역 예시", color = ink, style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.fillMaxWidth().height(200.dp).clearAndSetSemantics {
            contentDescription = "구역 예시. ${s.entrance?.name}에서 ${s.bay?.name}, ${s.bay?.side}"
        }) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                drawRect(line, Offset(w * .06f, h * .08f), Size(w * .38f, h * .27f))
                drawRect(line, Offset(w * .58f, h * .08f), Size(w * .36f, h * .27f))
                drawLine(ink.copy(alpha = .20f), Offset(0f, h * .85f), Offset(w, h * .85f), 2.dp.toPx())
                val x = if (s.bayIndex == 0) w * .25f else w * .76f
                val path = Path().apply { moveTo(w * .08f, h * .85f); lineTo(x, h * .85f); lineTo(x, h * .47f) }
                drawPath(path, blue, style = Stroke(5.dp.toPx()))
                drawRect(blue.copy(alpha = .12f), Offset(x - w * .12f, h * .40f), Size(w * .24f, h * .25f))
                drawCircle(blue, 7.dp.toPx(), Offset(w * .08f, h * .85f))
                drawCircle(blue, 7.dp.toPx(), Offset(x, h * .47f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 22.dp)) {
                site.bays.take(2).forEach { bay ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(bay.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CarbonIcon(if (bayView) R.drawable.ic_delivery else R.drawable.ic_location, null)
            Text(if (bayView) "${s.bay?.name} · ${s.bay?.side}" else s.entrance?.note.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun height(cm: Int) = String.format(Locale.US, "%.2f m", cm / 100.0)
private fun degree(value: Float) = String.format(Locale.US, "%.1f°", kotlin.math.abs(value))
