package com.minwook.pleoswork

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** The phone side of the local handoff. No recipient records are duplicated on the IVI. */
@Composable
fun ParcelTerminalPanel(s: CompanionState, car: VehicleState, modifier: Modifier = Modifier,
    send: (Int, Int, String?) -> Unit) {
    val r = s.run ?: return
    val load = r.stop.parcel ?: return
    val reviewing = r.stage == RunStage.REVIEW
    // The first example intentionally returns one unfinished delivery and pickup.
    val exampleMissing = !reviewing && r.index == 0
    var delivered by rememberSaveable(r.sessionId, r.stop.id, r.lastSequence) {
        mutableIntStateOf(if (reviewing) r.result.deliveries else (load.deliveries - if (exampleMissing) 1 else 0).coerceAtLeast(0))
    }
    var collected by rememberSaveable(r.sessionId, r.stop.id, r.lastSequence) {
        mutableIntStateOf(if (reviewing) r.result.pickups else (load.pickups - if (exampleMissing) 1 else 0).coerceAtLeast(0))
    }
    var defer by rememberSaveable(r.sessionId, r.stop.id, r.lastSequence) { mutableStateOf(false) }
    var reason by rememberSaveable(r.sessionId, r.stop.id) { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val workFocus = LocalWorkFocus.current
    val focusScope = LocalFocusScope.current
    val enabled = car.canWork && !r.resting && !r.dayEnded && r.pending.isEmpty() &&
        r.stage in setOf(RunStage.WORKING, RunStage.REVIEW)
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            VisitHeading(r.stop.name, if (reviewing) "미처리 확인" else "전송할 예시 처리 결과")
            ParcelCountInput("배송", delivered, load.deliveries, "개", enabled && !defer) { delivered = it }
            if (load.pickups > 0) ParcelCountInput("회수", collected, load.pickups, "건", enabled && !defer) { collected = it }
            Text(if (delivered == load.deliveries && collected == load.pickups) "미처리 없음"
                else "미배송 ${load.deliveries - delivered}개 · 미회수 ${load.pickups - collected}건",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge,
                color = if (delivered == load.deliveries && collected == load.pickups) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            if (reviewing && !r.ready && (defer || delivered < load.deliveries || collected < load.pickups)) {
                if (!defer) QuietButton(onClick = {
                    delivered = r.result.deliveries; collected = r.result.pickups; defer = true
                    workFocus?.moveTo("$focusScope/reason-absent")
                }, enabled = enabled, focusKey = "defer-result") { Text("미처리 사유로 인계", style = MaterialTheme.typography.labelLarge) }
                else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietButton(onClick = { reason = "고객 부재 · 센터 인계" }, enabled = enabled,
                            modifier = Modifier.weight(1f), focusKey = "reason-absent") {
                            Text("고객 부재", style = MaterialTheme.typography.labelLarge)
                        }
                        QuietButton(onClick = { reason = "진입 불가 · 센터 인계" }, enabled = enabled,
                            modifier = Modifier.weight(1f), focusKey = "reason-access") {
                            Text("진입 불가", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    OutlinedTextField(reason, { reason = it.take(80) },
                        Modifier.fillMaxWidth().carFocusOutline("handoff-reason").onPreviewKeyEvent { key ->
                            if (key.key == Key.Tab && key.type == KeyEventType.KeyDown) {
                                keyboard?.hide(); focusManager.moveFocus(if (key.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true
                            } else false
                        }, label = { Text("인계 사유") }, minLines = 2, maxLines = 3, enabled = enabled)
                    QuietButton(onClick = {
                        keyboard?.hide(); defer = false; workFocus?.moveTo("$focusScope/배송-count")
                    }, focusKey = "edit-counts") {
                        Text("처리 수량으로 돌아가기", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Text(if (s.terminalConnected) "차량에 결과를 보내면 현재 방문지에서 확인할 수 있습니다."
                else "연결 끊김 · 결과는 전송 대기로 보관됩니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Divider()
        Row(Modifier.fillMaxWidth().padding(WorkDimens.inset)) {
            ActionButton(if (defer) "인계 사유 전송" else "작업 결과 전송", Modifier.fillMaxWidth(),
                enabled = enabled && (!defer || reason.trim().length >= 2), icon = R.drawable.ic_mobile, focusKey = "terminal-send") {
                keyboard?.hide()
                send(delivered, collected, if (defer) reason.trim() else null)
            }
        }
    }
}

@Composable private fun ParcelCountInput(label: String, count: Int, total: Int, unit: String,
    enabled: Boolean, change: (Int) -> Unit) {
    val focus = LocalWorkFocus.current
    val scope = LocalFocusScope.current
    fun update(value: Int) {
        change(value)
        // Retain the numeric control as the anchor when a +/- button becomes disabled.
        // Focusing its value also prevents a repeated Enter from immediately undoing it.
        if (value == 0 || value == total) focus?.moveTo("$scope/$label-count")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label 처리", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(onClick = { update(count - 1) }, enabled = enabled && count > 0, focusKey = "$label-minus",
                modifier = Modifier.semantics { contentDescription = "$label 처리 수량 줄이기" }) {
                Text("−", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineMedium)
            }
            Text("$count / $total$unit", Modifier.weight(1f).heightIn(min = WorkDimens.control)
                .carFocusOutline("$label-count").focusable(enabled).wrapContentHeight().clearAndSetSemantics {
                contentDescription = "$label 처리 $count$unit, 예정 $total$unit"
            }, style = MaterialTheme.typography.headlineMedium)
            QuietButton(onClick = { update(count + 1) }, enabled = enabled && count < total, focusKey = "$label-plus",
                modifier = Modifier.semantics { contentDescription = "$label 처리 수량 늘리기" }) {
                Text("+", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
