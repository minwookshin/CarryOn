package com.minwook.pleoswork

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable fun CurrentLoadSummary(s: CompanionState) {
    val stop = s.dispatch?.current ?: return
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        stop.parcel?.let { load ->
            if (load.deliveries > 0 || load.pickups == 0) DispatchFact("이번 배송", "${load.deliveries}개", Modifier.weight(1f))
            if (load.pickups > 0) DispatchFact("회수 예정", "${load.pickups}건", Modifier.weight(1f))
        }
        stop.freight?.let { load ->
            DispatchFact(stop.operation, "${load.units}${load.unit}", Modifier.weight(1f))
            DispatchFact("화물 중량", "${load.weightKg} kg", Modifier.weight(1f))
        }
    }
}

@Composable fun DispatchTiming(s: CompanionState) {
    val plan = s.dispatch ?: return
    val arrival = s.arrivalMinute() ?: return
    val comparison = compareArrival(arrival, plan.current.window)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Text("예상 ${dispatchTime(arrival)} · 예약 ${plan.current.window.label}", style = MaterialTheme.typography.bodyLarge)
        Text(comparison.label, style = MaterialTheme.typography.bodySmall,
            color = if (comparison.kind == ArrivalKind.LATE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${dispatchTime(plan.referenceMinute)} 기준 · 내비 예시", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun DispatchPanel(s: CompanionState, car: VehicleState, previewId: String?, select: (String) -> Unit, openSite: () -> Unit) {
    val plan = s.dispatch
    if (plan == null) {
        Text(if (!s.demo) "운송 정보 미연결" else if (!s.terminalConnected) "단말 연결 끊김" else "배차 대기", style = MaterialTheme.typography.headlineMedium)
        Text(if (s.driver?.dispatch == null) "배정된 운송을 받으면 일정과 물량이 표시됩니다." else "단말에서 받은 방문 순서·물량·시간 정보를 표시하는 영역입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val stop = plan.preview(previewId, car.canWork)
    val current = stop.id == plan.current.id
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("배차 ${plan.id}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text("단말 예시 · ${dispatchTime(plan.referenceMinute)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.stops.forEachIndexed { index, item ->
            val stage = if (index < plan.currentIndex) "완료" else if (index == plan.currentIndex) "현재" else "예정"
            DispatchStopRow(index + 1, item, stage, item.id == stop.id, car.canWork) { select(item.id) }
        }
    }
    Divider()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (current) "이번 방문 · ${stop.name}" else "선택한 방문지 · ${stop.name}",
            Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleLarge)
        val facts = buildList {
            add("방문 시간" to stop.window.label)
            stop.parcel?.let { load ->
                add("배송·회수" to "${load.deliveries}개 · ${load.pickups}건")
                add("적재 위치" to load.zones.joinToString(" · ") { "${it.name} ${it.units}개" })
            }
            stop.freight?.let { load ->
                add("화물" to load.name)
                add("수량·중량" to "${load.units}${load.unit} · ${load.weightKg} kg")
                add("적재 위치" to load.location)
                add("요구 온도" to (load.temperature?.label ?: "지정 없음"))
                add("필요 장비" to load.equipment)
            }
            add("현장 진입" to if (current) "${s.entrance?.name ?: "미수신"} · ${s.bay?.name ?: "배정 대기"}" else stop.access)
        }
        DispatchFacts(facts)
        if (current) stop.freight?.temperature?.let { requirement ->
            val measured = s.airTemperature
            Text("적재함 공기 ${measured?.let { "$it°C" } ?: "미수신"} · ${if (requirement.contains(measured)) "요구 범위 내" else "확인 필요"} · 예시",
                style = MaterialTheme.typography.bodySmall, color = if (requirement.contains(measured)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarbonIcon(R.drawable.ic_info, null)
            Text(stop.note, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
        if (current) QuietButton(onClick = openSite, enabled = car.canWork, focusKey = "open-site") {
            Text("진입·정차 정보"); Spacer(Modifier.width(12.dp)); CarbonIcon(R.drawable.ic_arrow_right, null)
        }
    }
}

@Composable private fun DispatchStopRow(number: Int, stop: DispatchStop, stage: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.listRow)
        .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
        .carFocusOutline("visit-${stop.id}").selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = choose)
        .semantics(mergeDescendants = true) { stateDescription = "$stage, ${if (selected) "상세 표시 중" else "상세 보기"}" }
        .padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(44.dp).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        Text("%02d".format(number), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stop.name, style = MaterialTheme.typography.titleMedium)
            Text("${stop.operation} · ${stop.window.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stage, color = if (stage == "현재") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        CarbonIcon(R.drawable.ic_arrow_right, null)
    }
}

@Composable private fun DispatchFacts(facts: List<Pair<String, String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 560.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            facts.chunked(columns).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    chunk.forEach { (label, value) -> DispatchFact(label, value, Modifier.weight(1f)) }
                    if (chunk.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable private fun DispatchFact(label: String, value: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
