package com.minwook.pleoswork

enum class GuidanceMode { DEMO, NATIVE_SAMPLE }
enum class GuidanceStatus { REQUESTED, ACTIVE, ARRIVED, FAILED }

/** One request belongs to one visit. Demo motion never becomes a vehicle/GPS reading. */
data class VisitGuidance(
    val id: String, val visitId: String, val mode: GuidanceMode,
    val status: GuidanceStatus, val target: NavTarget? = null,
    val startedAt: Long, val issuedAt: Long = 0, val confirmedAt: Long = 0,
    val elapsedMs: Long = 0, val paused: Boolean = false,
    val arrivedAt: Long = 0, val message: String = "",
) {
    val progress: Float get() = (elapsedMs / 18_000f).coerceIn(0f, 1f)
    val remainingSeconds: Int get() = ((18_000 - elapsedMs).coerceAtLeast(0) + 999).div(1000).toInt()
}

val DemoRun.guidance: VisitGuidance? get() = visitGuidance?.takeIf { it.visitId == navigationVisitId }
val DemoRun.navigationTarget: NavTarget? get() =
    (if (returning) plan.returnTarget else stop.navigationTarget)?.takeIf { it.valid() }

/** Navi Service resolves a requested POI to its canonical coordinates. Adopt it only at acknowledgement. */
fun requestedDestinationMatches(requested: NavTarget, observed: NavTarget): Boolean =
    requested.samePlace(observed) || requested.valid() && observed.valid() &&
        requested.name == observed.name && requested.poiId.isNotBlank() && requested.poiId == observed.poiId &&
        (requested.poiSubId.isNullOrBlank() || observed.poiSubId.isNullOrBlank() || requested.poiSubId == observed.poiSubId)

/** Official Kotlin examples, used as test destinations, not real delivery addresses. */
val nativeSampleTargets = listOf(
    NavTarget("42dot", 37.48544722, 127.03636666, "서울시 강남구 남부순환로 2621", "36khp37hh8gqcm5nqhe5", "0"),
    NavTarget("소프트웨어드림센터", 37.41275833, 127.09481944, "경기도 성남시 수정구 창업로40번길 6", "36khp2hdj2cfcm5nqhe3", "0"),
)

object GuidanceFlow {
    fun start(s: CompanionState, parked: Boolean, now: Long): CompanionState {
        val r = s.run ?: return s
        if (!s.demo || !parked || !r.canConnectNavigation || s.cargoOpen || r.pending.isNotEmpty()) return s
        val native = s.guidanceMode == GuidanceMode.NATIVE_SAMPLE
        val target = if (native) r.navigationTarget else null
        val leg = VisitGuidance("${r.sessionId}:${r.navigationVisitId}:$now", r.navigationVisitId, s.guidanceMode,
            if (native && target == null) GuidanceStatus.FAILED else if (native) GuidanceStatus.REQUESTED else GuidanceStatus.ACTIVE,
            target = target, startedAt = now,
            message = if (native && target == null) "방문지 좌표 미수신 · 단말에서 목적지를 확인하세요" else "")
        return s.copy(run = r.copy(stage = if (r.returning) RunStage.RETURN_TRAVEL else RunStage.TRAVEL,
            visitGuidance = leg, navigationBinding = null))
    }

    fun next(s: CompanionState, parked: Boolean, now: Long): CompanionState {
        val before = s.run ?: return s
        val next = DemoWorkflow.advance(s, parked)
        return if (next.run?.navigationVisitId != before.navigationVisitId) start(next, parked, now) else next
    }

    fun pause(s: CompanionState, parked: Boolean): CompanionState {
        val r = s.run ?: return s
        val leg = r.guidance ?: return s
        if (!parked || leg.mode != GuidanceMode.DEMO || leg.status != GuidanceStatus.ACTIVE) return s
        return s.copy(run = r.copy(visitGuidance = leg.copy(paused = !leg.paused)))
    }

    fun mode(s: CompanionState, mode: GuidanceMode, parked: Boolean): CompanionState =
        if (!parked || s.guidanceMode == mode) s else s.copy(guidanceMode = mode,
            run = s.run?.copy(visitGuidance = null, navigationBinding = null))

    fun issued(s: CompanionState, id: String, accepted: Boolean, now: Long): CompanionState {
        val r = s.run ?: return s
        val leg = r.guidance ?: return s
        if (leg.id != id || leg.mode != GuidanceMode.NATIVE_SAMPLE || leg.issuedAt != 0L || leg.status != GuidanceStatus.REQUESTED) return s
        return s.copy(run = r.copy(visitGuidance = leg.copy(issuedAt = now,
            status = if (accepted) GuidanceStatus.REQUESTED else GuidanceStatus.FAILED,
            message = if (accepted) "목적지 전달됨 · 내비 응답 대기" else "내비에 목적지를 전달하지 못했습니다")))
    }

    /** Called for visible foreground time only; background time never fast-forwards a demo. */
    fun tick(s: CompanionState, car: VehicleState, now: Long, demoElapsedMs: Long): CompanionState {
        val r = s.run ?: return s
        var leg = r.guidance ?: return s
        if (!s.demo || !r.showNavigation || leg.mode == GuidanceMode.DEMO && r.pending.isNotEmpty()) return s
        if (leg.mode == GuidanceMode.DEMO) {
            if (leg.status == GuidanceStatus.ACTIVE && !leg.paused && car.canWork && !s.cargoOpen) {
                val elapsed = (leg.elapsedMs + demoElapsedMs.coerceIn(0, 1500)).coerceAtMost(18_000)
                leg = leg.copy(elapsedMs = elapsed,
                    status = if (elapsed == 18_000L) GuidanceStatus.ARRIVED else GuidanceStatus.ACTIVE,
                    arrivedAt = if (elapsed == 18_000L) now else 0)
            }
        } else {
            val reading = car.navigation?.takeIf { it.fresh(now) && it.receivedAt >= leg.issuedAt && leg.issuedAt > 0 }
            val matches = reading != null && leg.target?.let {
                if (leg.status == GuidanceStatus.REQUESTED) requestedDestinationMatches(it, reading.target)
                else it.samePlace(reading.target)
            } == true
            if (leg.status == GuidanceStatus.REQUESTED) {
                leg = when {
                    matches -> leg.copy(status = GuidanceStatus.ACTIVE, target = reading!!.target,
                        confirmedAt = reading.receivedAt, message = "기본 내비 안내 중")
                    leg.issuedAt > 0 && car.routeStatus == "error" && car.routeEventAt >= leg.issuedAt -> leg.copy(status = GuidanceStatus.FAILED, message = "내비가 경로를 연결하지 못했습니다 · 목적지와 연결 상태를 확인하세요")
                    leg.issuedAt > 0 && now - leg.issuedAt >= 30_000 || leg.issuedAt == 0L && car.navigationStatus != "연결됨" && now - leg.startedAt >= 30_000 ->
                        leg.copy(status = GuidanceStatus.FAILED, message = "내비 응답 없음 · 연결 상태를 확인한 뒤 다시 시도하세요")
                    else -> leg
                }
            }
            if (leg.status == GuidanceStatus.ACTIVE) {
                val arrival = car.navigationArrival
                leg = when {
                    arrival != null && arrival.receivedAt > leg.issuedAt && arrival.fresh(now) && leg.target?.samePlace(arrival.target) == true ->
                        leg.copy(status = GuidanceStatus.ARRIVED, arrivedAt = now, message = "기본 내비에서 도착 수신")
                    reading != null && !matches -> leg.copy(status = GuidanceStatus.FAILED, message = "내비 목적지가 바뀌었습니다 · 자동 안내 중지")
                    car.routeStatus in setOf("cancelled", "error") && car.routeEventAt >= maxOf(leg.issuedAt, leg.confirmedAt) ->
                        leg.copy(status = GuidanceStatus.FAILED, message = "내비 안내가 중단되었습니다")
                    !matches && car.navigationObservedAt >= leg.confirmedAt &&
                        now - maxOf(leg.confirmedAt, car.navigation?.receivedAt ?: 0L) > 90_000 ->
                        leg.copy(status = GuidanceStatus.FAILED, message = "내비 연결 확인이 필요합니다 · 다시 안내를 시작하세요")
                    else -> leg
                }
            }
        }
        val next = s.copy(run = r.copy(visitGuidance = leg,
            navigationBinding = if (leg.mode == GuidanceMode.NATIVE_SAMPLE && leg.status in setOf(GuidanceStatus.ACTIVE, GuidanceStatus.ARRIVED))
                NavigationBinding(r.navigationVisitId, leg.target!!, leg.issuedAt) else r.navigationBinding))
        // Retain arrival while awaiting P; an arrival is never a delivery/receipt confirmation.
        return if (leg.status == GuidanceStatus.ARRIVED) DemoWorkflow.atSite(next, car.canWork,
            advanceExampleClock = leg.mode == GuidanceMode.DEMO) else next
    }
}
