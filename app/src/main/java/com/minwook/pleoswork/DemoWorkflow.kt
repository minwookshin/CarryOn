package com.minwook.pleoswork

import com.google.gson.Gson
import java.util.UUID

/** Local, fictional dispatch execution. Never sends commands to a vehicle or a carrier. */
enum class RunStage(val label: String) {
    PREPARING("출발 준비"), TRAVEL("이동 중"), ARRIVED("도착"), WAITING("도크 배정 대기"),
    WORKING("단말 작업 중"), REVIEW("처리 결과"), DEPARTURE("상차 후 출발 준비"),
    RETURN_TRAVEL("센터 복귀 중"), RETURNED("복귀 인계"), ENDED("운행 종료")
}
enum class TerminalKind { DOCK, RESULT, RECEIPT, DEFER, HANDOFF }
enum class SensorExample { NORMAL, DEVIATION, OFFLINE }
data class StopResult(
    val deliveries: Int = 0, val pickups: Int = 0, val units: Int = 0,
    val receipt: Boolean = false, val reason: String = "", val dock: String = "",
)
data class TerminalEvent(
    val id: String, val sessionId: String, val sequence: Int, val stopId: String,
    val kind: TerminalKind, val deliveries: Int = 0, val pickups: Int = 0,
    val units: Int = 0, val reason: String = "",
)
data class DemoRun(
    val schema: Int = 1, val driverId: String, val sessionId: String,
    val expiresAt: Long, val stage: RunStage = RunStage.PREPARING, val index: Int = 0,
    val minute: Int, val results: Map<String, StopResult> = emptyMap(),
    val lastSequence: Int = 0, val seen: List<String> = emptyList(),
    val pending: List<TerminalEvent> = emptyList(), val lastReceivedAt: Long,
    val resting: Boolean = false, val restMinutes: Int = 0, val dayEnded: Boolean = false,
    val handedOver: Boolean = false, val endPending: Boolean = false,
    val sensor: SensorExample = SensorExample.NORMAL, val sensorChangedAt: Long,
    val sensorReading: Float = -19.2f, val doorOpenedAt: Long? = null,
    val navigationBinding: NavigationBinding? = null,
    val visitGuidance: VisitGuidance? = null,
    // Local handoff state, not a vehicle occupancy sensor. Results never imply reboarding.
    val parcelAway: Boolean = false,
) {
    val profile: DriverProfile get() = demoDrivers.first { it.id == driverId }
    val plan: DriverDispatch get() = profile.dispatch!!
    val stop: DispatchStop get() = plan.stops[index]
    val result: StopResult get() = results[stop.id] ?: StopResult()
    val returning: Boolean get() = stage in setOf(RunStage.RETURN_TRAVEL, RunStage.RETURNED, RunStage.ENDED)
    val travelling: Boolean get() = stage in setOf(RunStage.TRAVEL, RunStage.RETURN_TRAVEL) && !resting && !dayEnded
    val missingDeliveries: Int get() = stop.parcel?.let { (it.deliveries - result.deliveries).coerceAtLeast(0) } ?: 0
    val missingPickups: Int get() = stop.parcel?.let { (it.pickups - result.pickups).coerceAtLeast(0) } ?: 0
    val ready: Boolean get() = stop.parcel?.let { (missingDeliveries == 0 && missingPickups == 0) || result.reason.isNotBlank() }
        ?: (result.units == stop.freight?.units && (stop.operation != "하차" || result.receipt))
    val deferredDeliveries: Int get() = plan.stops.sumOf { (it.parcel?.deliveries ?: 0) - (results[it.id]?.deliveries ?: 0) }
    val uncollected: Int get() = plan.stops.sumOf { (it.parcel?.pickups ?: 0) - (results[it.id]?.pickups ?: 0) }
    val collected: Int get() = results.values.sumOf { it.pickups }

    fun event(kind: TerminalKind, partial: Boolean = false, reason: String = ""): TerminalEvent {
        val sequence = maxOf(lastSequence, pending.maxOfOrNull { it.sequence } ?: 0) + 1
        return TerminalEvent(UUID.randomUUID().toString(), sessionId, sequence, stop.id, kind,
            deliveries = ((stop.parcel?.deliveries ?: 0) - if (partial) 1 else 0).coerceAtLeast(0),
            pickups = ((stop.parcel?.pickups ?: 0) - if (partial) 1 else 0).coerceAtLeast(0),
            units = ((stop.freight?.units ?: 0) - if (partial) 1 else 0).coerceAtLeast(0), reason = reason)
    }
}

object DemoWorkflow {
    fun create(driver: DriverProfile, now: Long): DemoRun? = driver.dispatch?.let {
        DemoRun(driverId = driver.id, sessionId = UUID.randomUUID().toString(), expiresAt = now + 86_400_000,
            minute = if (driver.mode == ServiceMode.FREIGHT) 550 else it.referenceMinute,
            lastReceivedAt = now, sensorChangedAt = now)
    }

    fun advance(s: CompanionState, parked: Boolean): CompanionState {
        val r = s.run ?: return s
        if (!s.demo || !parked || r.resting || r.dayEnded || r.parcelAway) return s
        val next = when (r.stage) {
            RunStage.PREPARING -> if (!s.cargoOpen) r.copy(stage = RunStage.TRAVEL) else r
            RunStage.ARRIVED -> r.copy(stage = if (s.mode == ServiceMode.PARCEL) RunStage.WORKING else RunStage.WAITING)
            RunStage.REVIEW -> if (!r.ready || r.pending.isNotEmpty()) r else if (r.stop.freight != null && r.stop.operation != "하차")
                r.copy(stage = RunStage.DEPARTURE) else depart(r, s.cargoOpen)
            RunStage.DEPARTURE -> depart(r, s.cargoOpen)
            RunStage.RETURNED -> if (r.handedOver) r.copy(stage = RunStage.ENDED, endPending = !s.terminalConnected) else r
            else -> r
        }
        val changedVisit = next.navigationVisitId != r.navigationVisitId
        return s.copy(run = next, entranceIndex = if (changedVisit) 0 else s.entranceIndex,
            bayIndex = if (changedVisit) 0 else s.bayIndex,
            trafficDelayMinutes = if (changedVisit) 0 else s.trafficDelayMinutes)
    }

    private fun depart(r: DemoRun, doorOpen: Boolean): DemoRun {
        if (doorOpen) return r
        return if (r.index == r.plan.stops.lastIndex) r.copy(stage = RunStage.RETURN_TRAVEL)
        else r.copy(index = r.index + 1, stage = RunStage.TRAVEL)
    }

    fun arrive(s: CompanionState, parked: Boolean): CompanionState {
        val r = s.run ?: return s
        if (!s.demo || !parked || !r.travelling) return s
        val minutes = (workflowSite(r).seconds + 59) / 60 + s.trafficDelayMinutes
        return s.copy(run = r.copy(stage = if (r.returning) RunStage.RETURNED else RunStage.ARRIVED,
            minute = r.minute + minutes), trafficDelayMinutes = 0)
    }

    /** Arrival is one action; navigation need not have been started in this app. */
    fun atSite(s: CompanionState, parked: Boolean, advanceExampleClock: Boolean = true): CompanionState {
        val r = s.run ?: return s
        if (!s.demo || !parked || r.dayEnded || r.resting || r.pending.isNotEmpty() ||
            r.stage !in setOf(RunStage.PREPARING, RunStage.TRAVEL, RunStage.ARRIVED, RunStage.RETURN_TRAVEL)) return s
        val arrived = if (advanceExampleClock && r.travelling) arrive(s, parked) else s
        return arrived.copy(run = arrived.run!!.copy(stage = if (r.returning) RunStage.RETURNED
            else if (s.mode == ServiceMode.PARCEL) RunStage.WORKING else RunStage.WAITING))
    }

    fun bindNavigation(s: CompanionState, reading: NavReading?, parked: Boolean, now: Long): CompanionState {
        val r = s.run ?: return s
        if (!s.demo || !parked || !r.canConnectNavigation || reading == null ||
            !reading.fresh(now) || !reading.target.valid()) return s
        return s.copy(run = r.copy(navigationBinding = NavigationBinding(r.navigationVisitId, reading.target, now)))
    }

    fun navigationArrived(s: CompanionState, arrival: NavArrival?, parked: Boolean, now: Long): CompanionState {
        val r = s.run ?: return s
        val binding = r.activeNavigationBinding ?: return s
        if (arrival == null || arrival.receivedAt <= binding.boundAt ||
            !arrival.fresh(now) || !arrival.target.samePlace(binding.target)) return s
        // An arrival is never proof of a delivery, a dock assignment, or a receipt.
        return atSite(s, parked, advanceExampleClock = false)
    }

    /** Absolute counts, per-session sequence and stop guards prevent duplicate or stale updates. */
    fun terminal(s: CompanionState, event: TerminalEvent, parked: Boolean, now: Long): CompanionState {
        val r = s.run ?: return s
        if (!s.demo || !parked || r.dayEnded || r.resting || event.sessionId != r.sessionId || event.stopId != r.stop.id ||
            event.id in r.seen || r.pending.any { it.id == event.id } || event.sequence <= r.lastSequence || !accepts(r, event)) return s
        if (!s.terminalConnected) return s.copy(run = r.copy(pending = (r.pending + event).takeLast(64)))
        return s.copy(run = applyEvent(r, event, now))
    }

    private fun accepts(r: DemoRun, e: TerminalEvent): Boolean = when (e.kind) {
        TerminalKind.DOCK -> r.stage in setOf(RunStage.PREPARING, RunStage.TRAVEL, RunStage.ARRIVED, RunStage.WAITING) && r.stop.freight != null
        TerminalKind.RESULT -> (r.stage in setOf(RunStage.WORKING, RunStage.REVIEW) ||
            r.stop.parcel != null && r.stage in setOf(RunStage.PREPARING, RunStage.TRAVEL, RunStage.ARRIVED)) &&
            e.deliveries in 0..(r.stop.parcel?.deliveries ?: 0) && e.pickups in 0..(r.stop.parcel?.pickups ?: 0) &&
            e.units in 0..(r.stop.freight?.units ?: 0)
        TerminalKind.RECEIPT -> r.stage == RunStage.REVIEW && r.stop.operation == "하차" && r.result.units == r.stop.freight?.units
        TerminalKind.DEFER -> r.stage == RunStage.REVIEW && r.stop.parcel != null &&
            (r.missingDeliveries + r.missingPickups > 0) && e.reason.trim().length in 2..80
        TerminalKind.HANDOFF -> r.stage == RunStage.RETURNED
    }

    private fun applyEvent(r: DemoRun, e: TerminalEvent, now: Long): DemoRun {
        if (e.id in r.seen || e.sequence <= r.lastSequence || e.sessionId != r.sessionId || e.stopId != r.stop.id || !accepts(r, e)) return r
        val result = when (e.kind) {
            TerminalKind.DOCK -> r.result.copy(dock = when (r.index) { 0 -> "02번 도크"; 1 -> "03번 도크"; else -> "01번 도크" })
            TerminalKind.RESULT -> r.result.copy(deliveries = e.deliveries, pickups = e.pickups, units = e.units,
                reason = "", receipt = false)
            TerminalKind.RECEIPT -> r.result.copy(receipt = true)
            TerminalKind.DEFER -> r.result.copy(reason = e.reason.trim())
            TerminalKind.HANDOFF -> r.result
        }
        return r.copy(results = r.results + (r.stop.id to result),
            stage = when (e.kind) { TerminalKind.DOCK -> RunStage.WORKING; TerminalKind.RESULT -> RunStage.REVIEW; else -> r.stage },
            minute = if (e.kind == TerminalKind.DOCK) maxOf(r.minute + 10, r.stop.window.startMinute)
                else r.minute + if (e.kind == TerminalKind.RESULT && r.stage == RunStage.WORKING) 15 else 0,
            handedOver = r.handedOver || e.kind == TerminalKind.HANDOFF,
            lastSequence = e.sequence, seen = (r.seen + e.id).takeLast(256), lastReceivedAt = now)
    }

    fun connection(s: CompanionState, connected: Boolean, parked: Boolean, now: Long): CompanionState {
        if (!s.demo || !parked) return s
        var r = s.run ?: return s.copy(terminalConnected = connected)
        if (connected) {
            for (event in r.pending.sortedBy { it.sequence }) r = applyEvent(r, event, now)
            r = r.copy(pending = emptyList(), lastReceivedAt = now, endPending = false)
        }
        return s.copy(terminalConnected = connected, run = r)
    }

    fun rest(s: CompanionState, parked: Boolean): CompanionState {
        val r = s.run ?: return s
        if (!s.demo || !parked || r.dayEnded || r.stage !in setOf(RunStage.TRAVEL, RunStage.RETURN_TRAVEL)) return s
        return s.copy(run = if (r.resting) r.copy(resting = false, minute = r.minute + 20, restMinutes = r.restMinutes + 20)
            else r.copy(resting = true))
    }

    fun endDay(s: CompanionState, parked: Boolean): CompanionState {
        val r = s.run ?: return s
        if (!s.demo || !parked || s.mode != ServiceMode.FREIGHT || r.resting || r.stage == RunStage.ENDED || r.pending.isNotEmpty()) return s
        return s.copy(run = if (r.dayEnded) r.copy(dayEnded = false, minute = (r.minute / 1440 + 1) * 1440 + 480)
            else r.copy(dayEnded = true))
    }

    fun sensor(s: CompanionState, example: SensorExample, parked: Boolean, now: Long): CompanionState {
        val r = s.run ?: return s
        if (!s.demo || !parked || !s.refrigerated) return s
        return s.copy(run = r.copy(sensor = example, sensorChangedAt = now,
            sensorReading = when (example) { SensorExample.NORMAL -> -19.2f; SensorExample.DEVIATION -> -15.2f; SensorExample.OFFLINE -> r.sensorReading }))
    }
}

/** Only an approved fictional session is stored. QR challenges and external credentials never are. */
object DemoRunCodec {
    private val gson = Gson()
    fun encode(run: DemoRun): String = gson.toJson(run)
    fun decode(json: String?, now: Long): DemoRun? = runCatching {
        val r = gson.fromJson(json, DemoRun::class.java) ?: return null
        val profile = demoDrivers.firstOrNull { it.id == r.driverId } ?: return null
        val stops = profile.dispatch?.stops ?: return null
        require(r.schema == 1 && r.sessionId.isNotBlank() && r.expiresAt > now && r.index in stops.indices && r.minute >= 0)
        require(r.stage in RunStage.entries && r.sensor in SensorExample.entries && r.sensorReading.isFinite())
        require(r.lastSequence >= 0 && r.restMinutes >= 0 && r.seen.size <= 256 && r.pending.size <= 64)
        require(r.pending.all { it.sessionId == r.sessionId && it.stopId == r.stop.id && it.sequence > r.lastSequence && it.kind in TerminalKind.entries })
        require(r.results.all { (id, v) ->
            val stop = stops.firstOrNull { it.id == id } ?: return@all false
            v.deliveries in 0..(stop.parcel?.deliveries ?: 0) && v.pickups in 0..(stop.parcel?.pickups ?: 0) &&
                v.units in 0..(stop.freight?.units ?: 0) && v.reason.length <= 80
        })
        r.copy(visitGuidance = r.visitGuidance?.takeIf { leg ->
            runCatching { leg.id.isNotBlank() && leg.visitId == r.navigationVisitId && leg.mode in GuidanceMode.entries &&
                leg.status in GuidanceStatus.entries && leg.elapsedMs in 0..18_000 && leg.startedAt > 0 &&
                (leg.mode == GuidanceMode.DEMO || leg.target?.valid() == true) }.getOrDefault(false)
        }, navigationBinding = r.navigationBinding?.takeIf { binding ->
            runCatching { (binding.stopId in stops.map { it.id } || binding.stopId == "return:${r.driverId}") && binding.target.valid() &&
                binding.boundAt > 0 && binding.boundAt < r.expiresAt }.getOrDefault(false)
        })
    }.getOrNull()
}

fun workflowSite(r: DemoRun): VehicleSite {
    if (r.returning) return VehicleSite("${r.profile.carrier} 복귀센터", "반납·인계 구역", 1200, 12500,
        listOf(SiteEntrance("센터 정문", 450, "지상 화물 출입구")), listOf(SiteBay("반납 구역 R", "후면 하차", "회수품과 미배송품을 구분해 인계")), "센터 대기구역")
    if (r.profile.mode == ServiceMode.PARCEL && r.index == 0) return r.profile.assignment!!
    if (r.stop.id == "cold-delivery") return freightSite
    val freight = r.profile.mode == ServiceMode.FREIGHT
    val dock = r.result.dock.ifBlank { if (freight) "도크 배정 대기" else "지정 하역구역" }
    return VehicleSite(r.stop.name, r.stop.access, if (freight) 1200 else 600, if (freight) 9800 else 3200,
        listOf(SiteEntrance(if (freight) "화물 출입구" else "지상 출입구", if (freight) 420 else 330, r.stop.access)),
        listOf(SiteBay(dock, "후면 하차", r.stop.note)), if (freight) "W1 대기구역" else "관리실 옆 대기구역")
}
