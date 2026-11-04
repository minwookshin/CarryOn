package com.minwook.pleoswork

enum class JourneyActionKind { DOCK, COMPLETE, RECEIPT, DEPART, HANDOFF, FINISH, TERMINAL, REBOARD, REVIEW_RESULTS }

/** A button belongs to the state the driver saw; stale/double taps cannot act on a new step. */
data class JourneyAction(
    val sessionId: String, val visitId: String, val stage: RunStage, val sequence: Int,
    val kind: JourneyActionKind, val label: String,
)

/** Direct controls for the local example dispatch. Never generates native arrival or carrier data. */
object InteractiveJourney {
    fun action(s: CompanionState): JourneyAction? {
        val r = s.run ?: return null
        if (!s.demo || s.guidanceMode != GuidanceMode.DEMO || r.resting || r.dayEnded || r.pending.isNotEmpty()) return null
        val next = if (r.index == r.plan.stops.lastIndex) "센터 복귀" else "다음 방문지"
        val pair = when (r.stage) {
            RunStage.WAITING -> JourneyActionKind.DOCK to "도크 배정 확인"
            RunStage.WORKING -> if (s.mode == ServiceMode.PARCEL)
                JourneyActionKind.TERMINAL to if (r.parcelAway) "단말 작업 이어보기" else "단말에서 작업하기"
                else JourneyActionKind.COMPLETE to "${r.stop.operation} 완료"
            RunStage.REVIEW -> when {
                r.stop.parcel != null && r.parcelAway -> JourneyActionKind.REBOARD to "재탑승 · 결과 확인"
                r.ready -> JourneyActionKind.DEPART to if (r.stop.freight != null && r.stop.operation != "하차") "출발 준비 확인" else "$next 안내"
                r.stop.parcel != null -> JourneyActionKind.REVIEW_RESULTS to "단말에서 미처리 확인"
                r.stop.operation == "하차" && r.result.units == r.stop.freight?.units -> JourneyActionKind.RECEIPT to "인수 확인 · $next"
                else -> JourneyActionKind.COMPLETE to "남은 작업 완료"
            }
            RunStage.DEPARTURE -> JourneyActionKind.DEPART to "출발 준비 완료 · $next"
            RunStage.RETURNED -> if (r.handedOver) JourneyActionKind.FINISH to "운행 종료"
                else JourneyActionKind.HANDOFF to "인계 완료 · 운행 종료"
            else -> return null
        }
        return JourneyAction(r.sessionId, r.navigationVisitId, r.stage, r.lastSequence, pair.first, pair.second)
    }

    fun perform(s: CompanionState, action: JourneyAction, parked: Boolean, now: Long): CompanionState {
        if (!parked || this.action(s) != action) return s
        val r = s.run!!
        fun send(kind: TerminalKind) = DemoWorkflow.terminal(s, r.event(kind), parked, now)
        return when (action.kind) {
            JourneyActionKind.TERMINAL -> s.copy(run = r.copy(parcelAway = true))
            JourneyActionKind.REBOARD -> s.copy(run = r.copy(parcelAway = false))
            JourneyActionKind.REVIEW_RESULTS -> s
            JourneyActionKind.DOCK -> send(TerminalKind.DOCK)
            JourneyActionKind.COMPLETE -> {
                val received = send(TerminalKind.RESULT)
                // Queueing or a missing receipt must never start the next leg.
                if (received.run!!.stage == RunStage.REVIEW && received.run!!.ready && received.run!!.pending.isEmpty())
                    GuidanceFlow.next(received, parked, now) else received
            }
            JourneyActionKind.RECEIPT -> GuidanceFlow.next(send(TerminalKind.RECEIPT), parked, now)
            JourneyActionKind.HANDOFF -> GuidanceFlow.next(send(TerminalKind.HANDOFF), parked, now)
            JourneyActionKind.DEPART, JourneyActionKind.FINISH -> GuidanceFlow.next(s, parked, now)
        }
    }
}

data class JourneyNotice(val id: String, val title: String, val body: String)

/** Persistent arrival/action state; only departure acknowledgement expires with visible travel time. */
fun journeyNotice(s: CompanionState): JourneyNotice? {
    val r = s.run ?: return null
    if (s.guidanceMode != GuidanceMode.DEMO || r.resting || r.dayEnded || r.pending.isNotEmpty()) return null
    val leg = r.guidance
    val text = when {
        r.stage == RunStage.ENDED -> "오늘 운행을 마쳤습니다" to "${r.plan.stops.size}곳 방문 · 센터 인계 완료"
        r.stage == RunStage.RETURNED -> "센터에 도착했습니다" to "회수품·남은 물품을 인계하세요"
        r.stage == RunStage.DEPARTURE -> "${r.stop.operation} 완료" to "적재 고정·문·서류를 확인한 뒤 출발하세요"
        r.stop.parcel != null && r.stage == RunStage.REVIEW && r.parcelAway ->
            "단말 결과를 받았습니다" to "재탑승 후 배송·회수 결과를 확인하세요"
        r.stop.parcel != null && r.stage == RunStage.REVIEW && !r.ready ->
            "재탑승 · 미처리 확인" to "미배송 ${r.missingDeliveries}개 · 미회수 ${r.missingPickups}건"
        r.stop.parcel != null && r.stage == RunStage.REVIEW && r.ready ->
            "처리 결과를 확인하세요" to if (r.result.reason.isNotBlank()) "미처리 사유가 인계되었습니다" else "배송·회수 완료 · 다음 안내를 시작할 수 있습니다"
        r.stop.parcel != null && r.stage == RunStage.WORKING && r.parcelAway ->
            "단말에서 작업 중입니다" to "배송·회수 결과 수신 대기"
        r.stage == RunStage.REVIEW && r.stop.operation == "하차" && !r.result.receipt && r.result.units == r.stop.freight?.units ->
            "하차 수량을 확인했습니다" to "인수 확인 후 다음 방문지로 안내합니다"
        r.stage == RunStage.REVIEW && r.ready -> "작업이 완료됐습니다" to "${r.visitName} · 출발 준비"
        r.stage == RunStage.WORKING && r.result.dock.isNotBlank() -> "${r.result.dock} 배정" to "${r.stop.operation} 작업을 진행하세요"
        leg?.status == GuidanceStatus.ARRIVED && r.stage in setOf(RunStage.WORKING, RunStage.WAITING) ->
            "목적지에 도착했습니다" to if (r.stage == RunStage.WAITING) "${r.visitName} · 도크 배정 대기" else r.visitName
        leg?.status == GuidanceStatus.ACTIVE && leg.elapsedMs < 5_000 && (r.index > 0 || r.returning) ->
            (if (r.returning) "센터 복귀 안내" else "다음 방문지 안내") to r.visitName
        else -> return null
    }
    return JourneyNotice("${r.sessionId}:${r.navigationVisitId}:${r.stage}:${r.lastSequence}:${r.parcelAway}", text.first, text.second)
}
