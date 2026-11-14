package com.minwook.pleoswork

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Preference changes never mutate an authenticated dispatch, its requirements, or its route. */
fun applyWorkPreferences(s: BoardState, mode: ServiceMode, cold: Boolean): BoardState {
    val empty = s.ledger == null || s.ledger.streamId == "local-profile"
    val ledger = if (empty) WorkLedger(accountId = "local-profile", streamId = "local-profile",
        driverName = "업무 연결 전", mode = mode, refrigerated = cold, visits = emptyList(), tasks = emptyList(),
        currentVisitId = null, sourceLabel = "연결 전") else s.ledger
    return s.copy(ledger = ledger, preferredMode = mode, preferredCold = cold)
}

val BoardState.canPresentNative: Boolean get() = navigationMode == GuidanceMode.NATIVE_SAMPLE &&
    ledger?.let { !it.dayClosed && !it.drivingEnded && it.current != null && route?.visitId == it.currentVisitId } == true

val LedgerTask.needsAttention: Boolean get() = remaining > 0 || awaitingSync
val List<LedgerTask>.confirmedComplete: Boolean get() = isNotEmpty() && all { it.remaining == 0 && it.sync == SyncMark.CONFIRMED }
val WorkLedger.attentionKind: TaskKind? get() = currentTasks.firstOrNull { it.kind == primaryKind && it.needsAttention }?.kind
    ?: currentTasks.firstOrNull { it.needsAttention }?.kind ?: primaryKind

val WorkLedger.serviceLabel: String get() = if (mode == ServiceMode.PARCEL) "택배" else "장거리 화물"

/** Preview follows the supplied visit order. Only CURRENT events change the actual destination. */
fun upcomingVisits(l: WorkLedger): List<LedgerVisit> {
    if (l.dayClosed || l.current == null) return emptyList()
    val currentIndex = l.visits.indexOfFirst { it.id == l.currentVisitId }
    return l.visits.drop(currentIndex + 1).filter { visit ->
        !l.tasks.filter { it.visitId == visit.id }.confirmedComplete
    }
}

/** Separate task kinds and units so receipts, returns and freight handling are not counted as parcels. */
fun remainingQuantityText(tasks: List<LedgerTask>): String {
    if (tasks.isEmpty()) return "수량 미수신"
    val quantities = tasks.filter { it.remaining > 0 }.groupBy { it.kind to it.unit }
        .map { (key, items) -> "${key.first.label} ${items.sumOf { it.remaining }}${key.second}" }
    val pending = tasks.count { it.awaitingSync }
    return (quantities + listOfNotNull(if (pending > 0) "전송 대기 ${pending}건" else null))
        .joinToString(" · ").ifBlank { "처리 완료" }
}

data class WorkMapLayout(val beside: Boolean, val above: Boolean)
fun workMapLayout(width: Float, mode: GuidanceMode): WorkMapLayout {
    val visible = mode == GuidanceMode.DEMO
    return WorkMapLayout(beside = visible && width >= 1150f, above = visible && width < 1150f)
}

enum class WorkHeadline { EMPTY, ACTIVE, PENDING, RECEIPT, COMPLETE, DRIVING_ENDED, DAY_CLOSED }
data class WorkSummary(val state: WorkHeadline, val title: String, val detail: String)

fun workSummary(l: WorkLedger): WorkSummary {
    val tasks = l.currentTasks
    val remaining = l.tasks.count { it.remaining > 0 }
    val preserved = listOfNotNull(if (remaining > 0) "미처리 ${remaining}건 보존" else null,
        if (l.pendingCount > 0) "전송 대기 ${l.pendingCount}건" else null).joinToString(" · ").ifBlank { "확인된 처리 기록을 보관합니다" }
    return when {
        l.dayClosed -> WorkSummary(WorkHeadline.DAY_CLOSED, "오늘 업무 종료", preserved)
        l.drivingEnded -> WorkSummary(WorkHeadline.DRIVING_ENDED, "오늘 운전 마침", preserved)
        l.current == null -> WorkSummary(WorkHeadline.EMPTY, "현재 배정이 없습니다", "단말에서 배정된 업무를 받으면 여기에 표시합니다")
        tasks.any { it.awaitingSync } -> WorkSummary(WorkHeadline.PENDING, "처리 결과 전송 대기",
            "단말 처리 ${tasks.filter { it.awaitingSync }.sumOf { it.completed }} · 회사 시스템 확인 전")
        tasks.any { it.kind == TaskKind.UNLOAD } && tasks.filter { it.kind == TaskKind.UNLOAD }.confirmedComplete &&
            tasks.any { it.kind == TaskKind.RECEIPT && it.remaining > 0 } -> WorkSummary(WorkHeadline.RECEIPT, "하역 완료 · 인수 미완료", "단말에서 인수 결과를 받으면 반영합니다")
        tasks.confirmedComplete -> WorkSummary(WorkHeadline.COMPLETE,
            if (tasks.any { it.kind == TaskKind.RECEIPT }) "하역 완료 · 인수 확인" else "현재 작업 처리 완료", "다음 배정 대기")
        else -> WorkSummary(WorkHeadline.ACTIVE, "", "")
    }
}

data class CargoStatus(val title: String, val detail: String, val exceptional: Boolean, val warning: Boolean)
fun cargoStatus(l: WorkLedger, now: Long): CargoStatus {
    val sample = l.sensor
    val v = l.current
    val range = if (v?.requiredLow != null && v.requiredHigh != null) "${v.requiredLow} ~ ${v.requiredHigh}°C" else "미확인"
    if (sample?.fresh(now) != true) return CargoStatus("현재 온도 확인 불가",
        "마지막 수신 ${recordTime(sample?.observedAt ?: 0)} · 요구 $range", true, false)
    val outside = v?.requiredLow != null && v.requiredHigh != null && sample.measuredC!! !in v.requiredLow..v.requiredHigh
    val settingOutside = v?.requiredLow != null && v.requiredHigh != null && sample.setpointC?.let { it !in v.requiredLow..v.requiredHigh } == true
    return CargoStatus(when { outside -> "${sample.measuredC}°C · 요구 범위 이탈"; settingOutside -> "설정 온도 확인 필요"; else -> "화물칸 ${sample.measuredC}°C" },
        "요구 $range · 설정 ${sample.setpointC?.let { "$it°C" } ?: "미확인"}", outside || settingOutside || sample.doorOpen == true, outside || settingOutside)
}

/** Always include the date: overnight freight must not look as fresh as today's HH:mm. */
fun recordTime(at: Long): String = if (at <= 0) "미수신" else SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(at))

fun mapStatus(s: BoardState): String {
    val l = s.ledger ?: return "안내 준비"
    val summary = workSummary(l)
    if (summary.state in setOf(WorkHeadline.DAY_CLOSED, WorkHeadline.DRIVING_ENDED)) return summary.title
    if (summary.state == WorkHeadline.COMPLETE) return "현재 작업 완료 · 다음 배정 대기"
    if (s.route?.status == GuidanceStatus.ARRIVED) return when {
        !s.connected && !l.streamId.startsWith("local-") -> "도착 · 마지막 업무 기록 표시"
        summary.state == WorkHeadline.PENDING -> "도착 · 처리 결과 전송 대기"
        else -> "도착 · 현재 작업 유지"
    }
    return when (s.route?.status) {
        null -> "안내 준비"
        GuidanceStatus.FAILED -> "안내 상태 확인 필요"
        else -> "목적지로 이동 중"
    }
}
