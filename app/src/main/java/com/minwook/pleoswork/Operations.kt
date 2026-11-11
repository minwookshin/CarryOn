package com.minwook.pleoswork

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser

enum class TaskKind(val label: String) {
    DELIVERY("배송"), RETURN("반품 회수"), COLLECTION("거래처 집하"), HANDOFF("터미널 인계"),
    LOAD("상차"), UNLOAD("하역"), RECEIPT("인수"), CONTAINER("용기 회수")
}
enum class SyncMark(val label: String) { OPEN("미처리"), LOCAL("단말 처리"), PENDING("전송 대기"), CONFIRMED("서버 반영") }
enum class FacilityPhase(val label: String) { UNKNOWN("접수 미확인"), REGISTERED("접수 확인"), CALLED("호출됨"), WORKING("작업 중") }

data class LedgerTask(
    val id: String, val number: String, val visitId: String, val building: String, val entrance: String,
    val kind: TaskKind, val quantity: Int, val unit: String = "개", val completed: Int = 0,
    val sync: SyncMark = SyncMark.OPEN, val revision: Long = 0, val updatedAt: Long = 0,
    val location: String? = null,
) {
    val remaining: Int get() = (quantity - completed).coerceAtLeast(0)
    val awaitingSync: Boolean get() = sync in setOf(SyncMark.LOCAL, SyncMark.PENDING)
}

data class LedgerVisit(
    val id: String, val name: String, val round: Int, val operation: String, val access: String?,
    val parking: String?, val target: NavTarget? = null, val phase: FacilityPhase = FacilityPhase.UNKNOWN,
    val dock: String? = null, val registeredAt: Long? = null, val revision: Long = 0,
    val requiredLow: Float? = null, val requiredHigh: Float? = null,
    val timeWindow: String? = null, val cargoInstructions: String? = null,
    val receiptInstructions: String? = null, val plannedStop: String? = null,
)

data class CargoSample(
    val sensorId: String, val compartment: String, val measuredC: Float?, val setpointC: Float?,
    val doorOpen: Boolean?, val observedAt: Long, val maxAgeMs: Long, val available: Boolean,
    val revision: Long = 0,
) {
    fun fresh(now: Long) = available && measuredC?.isFinite() == true && now - observedAt in 0..maxAgeMs
    fun valid() = sensorId.isNotBlank() && compartment.isNotBlank() && observedAt > 0 &&
        maxAgeMs in 1_000..300_000 && (measuredC == null || measuredC.isFinite() && measuredC in -80f..80f) &&
        (setpointC == null || setpointC.isFinite() && setpointC in -80f..80f) && revision >= 0
}

/** A reading keeps the requirement that was in force when it was received. */
data class CargoRecord(val sample: CargoSample, val visitId: String?, val requiredLow: Float?, val requiredHigh: Float?) {
    val outside: Boolean get() = sample.available && sample.measuredC != null && requiredLow != null && requiredHigh != null &&
        sample.measuredC !in requiredLow..requiredHigh
    fun valid() = sample.valid() && (requiredLow == null && requiredHigh == null ||
        requiredLow?.isFinite() == true && requiredHigh?.isFinite() == true && requiredLow <= requiredHigh)
}

/** Work outlives authentication and calendar days. Arrival is deliberately not a work status. */
data class WorkLedger(
    val schema: Int = 2, val accountId: String, val streamId: String, val driverName: String,
    val mode: ServiceMode, val refrigerated: Boolean, val visits: List<LedgerVisit>, val tasks: List<LedgerTask>,
    val currentVisitId: String?, val cursor: Long = 0, val activeRevision: Long = 0,
    val dayClosed: Boolean = false, val dayRevision: Long = 0,
    val drivingEnded: Boolean = false, val drivingRevision: Long = 0,
    val sensor: CargoSample? = null, val lastSyncAt: Long = 0, val sourceLabel: String = "예시 업무",
    val seen: List<String> = emptyList(), val note: String? = null,
    val cargoHistory: List<CargoRecord> = emptyList(), val noteRevision: Long = 0,
) {
    val current: LedgerVisit? get() = visits.find { it.id == currentVisitId }
    val currentTasks: List<LedgerTask> get() = tasks.filter { it.visitId == currentVisitId }
    val pendingCount: Int get() = tasks.count { it.awaitingSync }
    // Labels from a carrier or an older app are not enum identifiers.
    val primaryKind: TaskKind? get() = listOf(TaskKind.DELIVERY, TaskKind.LOAD, TaskKind.UNLOAD,
        TaskKind.COLLECTION, TaskKind.CONTAINER, TaskKind.HANDOFF, TaskKind.RETURN, TaskKind.RECEIPT)
        .firstOrNull { kind -> currentTasks.any { it.kind == kind } }
    fun remaining(kind: TaskKind, visitId: String? = currentVisitId): Int = tasks
        .filter { it.kind == kind && (visitId == null || it.visitId == visitId) }.sumOf { it.remaining }
    fun valid(): Boolean = runCatching {
        require(schema == 2 && accountId.isNotBlank() && streamId.isNotBlank() && driverName.isNotBlank() && mode in ServiceMode.entries)
        require(visits.size <= 500 && tasks.size <= 10_000 && visits.map { it.id }.distinct().size == visits.size)
        require(tasks.map { it.id }.distinct().size == tasks.size && currentVisitId.let { it == null || visits.any { v -> v.id == it } })
        require(cursor >= 0 && activeRevision >= 0 && dayRevision >= 0 && drivingRevision >= 0 && lastSyncAt >= 0)
        require(visits.all { v -> v.id.isNotBlank() && v.name.isNotBlank() && v.round > 0 && v.revision >= 0 && v.phase in FacilityPhase.entries &&
            listOf(v.timeWindow, v.cargoInstructions, v.receiptInstructions, v.plannedStop).all { it == null || it.length <= 300 } &&
            (v.target == null || v.target.valid()) && (v.requiredLow == null && v.requiredHigh == null ||
                v.requiredLow?.isFinite() == true && v.requiredHigh?.isFinite() == true && v.requiredLow <= v.requiredHigh) })
        require(tasks.all { t -> t.id.isNotBlank() && t.number.isNotBlank() && visits.any { it.id == t.visitId } &&
            t.quantity in 1..100_000 && t.completed in 0..t.quantity && t.revision >= 0 && t.updatedAt >= 0 && t.kind in TaskKind.entries && t.sync in SyncMark.entries &&
            (t.sync != SyncMark.OPEN || t.completed == 0) })
        require(sensor == null || sensor.valid())
        require(cargoHistory.size <= 720 && cargoHistory.all { it.valid() } && noteRevision >= 0)
        require(seen.size <= 512 && sourceLabel.length <= 80 && (note?.length ?: 0) <= 300)
        true
    }.getOrDefault(false)
}

enum class WorkEventKind { TASK, CURRENT, FACILITY, SENSOR, DAY, DRIVING, NOTE }
data class WorkEvent(
    val id: String, val accountId: String, val streamId: String, val revision: Long,
    val kind: WorkEventKind, val entityId: String = "", val completed: Int? = null, val sync: SyncMark? = null,
    val phase: FacilityPhase? = null, val dock: String? = null, val occurredAt: Long,
    val sensor: CargoSample? = null, val closed: Boolean? = null, val note: String? = null,
)
data class WorkBatch(val accountId: String, val streamId: String, val cursor: Long,
    val events: List<WorkEvent> = emptyList(), val snapshot: WorkLedger? = null)

/** Independent entity revisions accept late results from another visit without changing the active job. */
object OperationsReducer {
    fun apply(s: WorkLedger, e: WorkEvent, now: Long): WorkLedger {
        if (e.accountId != s.accountId || e.streamId != s.streamId || e.id.isBlank() || e.id in s.seen ||
            e.revision <= 0 || e.occurredAt <= 0 || e.occurredAt > now + 60_000) return s
        val next = when (e.kind) {
            WorkEventKind.TASK -> {
                val t = s.tasks.find { it.id == e.entityId } ?: return s
                if (e.revision <= t.revision || e.completed == null || e.completed !in 0..t.quantity || e.sync == null ||
                    e.sync == SyncMark.OPEN && e.completed != 0) return s
                s.copy(tasks = s.tasks.map { if (it.id == t.id) it.copy(completed = e.completed, sync = e.sync,
                    revision = e.revision, updatedAt = e.occurredAt) else it })
            }
            WorkEventKind.CURRENT -> {
                if (e.revision <= s.activeRevision || s.visits.none { it.id == e.entityId }) return s
                s.copy(currentVisitId = e.entityId, activeRevision = e.revision)
            }
            WorkEventKind.FACILITY -> {
                val v = s.visits.find { it.id == e.entityId } ?: return s
                if (e.revision <= v.revision || e.phase == null || (e.dock?.length ?: 0) > 100) return s
                s.copy(visits = s.visits.map { if (it.id == v.id) it.copy(phase = e.phase, dock = e.dock,
                    registeredAt = if (e.phase == FacilityPhase.REGISTERED) e.occurredAt else it.registeredAt,
                    revision = e.revision) else it })
            }
            WorkEventKind.SENSOR -> {
                val sample = e.sensor ?: return s
                if (!s.refrigerated || !sample.valid() || sample.observedAt > now + 60_000 ||
                    e.revision <= (s.sensor?.revision ?: 0)) return s
                val received = sample.copy(revision = e.revision)
                s.copy(sensor = received, cargoHistory = (s.cargoHistory + CargoRecord(received,
                    s.currentVisitId, s.current?.requiredLow, s.current?.requiredHigh)).takeLast(720))
            }
            WorkEventKind.DAY -> if (e.revision > s.dayRevision && e.closed != null)
                s.copy(dayClosed = e.closed, dayRevision = e.revision) else return s
            WorkEventKind.DRIVING -> if (e.revision > s.drivingRevision && e.closed != null)
                s.copy(drivingEnded = e.closed, drivingRevision = e.revision) else return s
            WorkEventKind.NOTE -> if (e.revision > s.noteRevision && (e.note?.length ?: 0) <= 300)
                s.copy(note = e.note, noteRevision = e.revision) else return s
        }
        return next.copy(seen = (s.seen + e.id).takeLast(512))
    }

    /** Scope/cursor validation is all-or-nothing; no UI/parking condition can drop incoming work. */
    fun batch(s: WorkLedger, batch: WorkBatch, now: Long): WorkLedger {
        require(batch.accountId == s.accountId && batch.streamId == s.streamId && batch.cursor >= s.cursor)
        require(batch.events.size <= 500 && batch.snapshot == null)
        // An invalid payload must not be acknowledged by moving the cursor past it.
        require(batch.events.all { e -> e.accountId == s.accountId && e.streamId == s.streamId &&
            e.id.isNotBlank() && e.revision > 0 && e.occurredAt in 1..now + 60_000 && e.kind in WorkEventKind.entries && when(e.kind) {
                WorkEventKind.TASK -> s.tasks.find { it.id == e.entityId }?.let { t ->
                    e.completed != null && e.completed in 0..t.quantity && e.sync in SyncMark.entries &&
                        (e.sync != SyncMark.OPEN || e.completed == 0) } == true
                WorkEventKind.CURRENT -> s.visits.any { it.id == e.entityId }
                WorkEventKind.FACILITY -> s.visits.any { it.id == e.entityId } && e.phase in FacilityPhase.entries && (e.dock?.length ?: 0) <= 100
                WorkEventKind.SENSOR -> s.refrigerated && e.sensor?.valid() == true && e.sensor.observedAt <= now + 60_000
                WorkEventKind.DAY, WorkEventKind.DRIVING -> e.closed != null
                WorkEventKind.NOTE -> (e.note?.length ?: 0) <= 300
            }
        })
        return batch.events.fold(s) { state, event -> apply(state, event, now) }
            .copy(cursor = batch.cursor, lastSyncAt = now).also { require(it.valid()) }
    }
}

object OperationsCodec {
    val gson = Gson()
    fun encode(s: WorkLedger): String { require(s.valid()); return gson.toJson(s) }
    fun decode(raw: String?): WorkLedger? = runCatching {
        if (raw == null || raw.length > 4_000_000) null else {
            val json = JsonParser.parseString(raw).asJsonObject
            // Additive schema-2 migration preserves records written before the history field existed.
            if (!json.has("cargoHistory")) json.add("cargoHistory", JsonArray())
            if (!json.has("noteRevision")) json.addProperty("noteRevision", 0)
            gson.fromJson(json, WorkLedger::class.java)?.takeIf { it.valid() }
        }
    }.getOrNull()
}

data class BoardRoute(
    val visitId: String, val mode: GuidanceMode, val status: GuidanceStatus, val target: NavTarget?,
    val startedAt: Long, val issuedAt: Long = 0, val confirmedAt: Long = 0,
    val elapsedMs: Long = 0, val arrivedAt: Long = 0, val message: String = "",
) {
    val progress: Float get() = (elapsedMs / 18_000f).coerceIn(0f, 1f)
}

/** The same parked fixture may animate a route; it never changes real vehicle or task evidence. */
object BoardNavigation {
    fun start(visit: LedgerVisit, mode: GuidanceMode, now: Long) = BoardRoute(visit.id, mode,
        if (mode == GuidanceMode.DEMO) GuidanceStatus.ACTIVE else if (visit.target == null) GuidanceStatus.FAILED else GuidanceStatus.REQUESTED,
        visit.target, now, message = if (mode != GuidanceMode.DEMO && visit.target == null) "목적지 좌표 미수신" else "")

    fun tick(r: BoardRoute, car: VehicleState, now: Long, elapsed: Long): BoardRoute {
        if (r.mode == GuidanceMode.DEMO) {
            if (r.status != GuidanceStatus.ACTIVE || !car.canWork) return r
            val total = (r.elapsedMs + elapsed.coerceIn(0, 1500)).coerceAtMost(18_000)
            return r.copy(elapsedMs = total, status = if (total == 18_000L) GuidanceStatus.ARRIVED else r.status,
                arrivedAt = if (total == 18_000L) now else 0)
        }
        if (r.status == GuidanceStatus.ARRIVED || r.status == GuidanceStatus.FAILED) return r
        // Linking/settings may stay open for minutes before the first actual SDK request.
        if (r.status == GuidanceStatus.REQUESTED && r.issuedAt == 0L) return r
        val reading = car.navigation?.takeIf { it.fresh(now) && r.issuedAt > 0 && it.receivedAt >= r.issuedAt }
        val matches = reading != null && r.target?.let { requestedDestinationMatches(it, reading.target) } == true
        if (r.status == GuidanceStatus.REQUESTED) return when {
            matches -> r.copy(status = GuidanceStatus.ACTIVE, target = reading!!.target, confirmedAt = reading.receivedAt)
            car.routeStatus == "error" && car.routeEventAt >= r.startedAt -> r.copy(status = GuidanceStatus.FAILED, message = "내비 경로 응답 확인 필요")
            now - r.startedAt > 30_000 -> r.copy(status = GuidanceStatus.FAILED, message = "내비 응답 없음 · 다시 안내할 수 있습니다")
            else -> r
        }
        val arrival = car.navigationArrival
        return when {
            arrival != null && arrival.receivedAt > r.issuedAt && arrival.fresh(now) && r.target?.samePlace(arrival.target) == true ->
                r.copy(status = GuidanceStatus.ARRIVED, arrivedAt = arrival.receivedAt)
            reading != null && !matches -> r.copy(status = GuidanceStatus.FAILED, message = "내비 목적지가 바뀌었습니다")
            car.routeStatus in setOf("cancelled", "error") && car.routeEventAt >= maxOf(r.issuedAt, r.confirmedAt) ->
                r.copy(status = GuidanceStatus.FAILED, message = "내비 안내가 중단되었습니다")
            now - maxOf(r.confirmedAt, reading?.receivedAt ?: 0) > 90_000 -> r.copy(status = GuidanceStatus.FAILED, message = "내비 정보 미수신")
            else -> r
        }
    }
}

data class DrivingSegment(val start: Long, val end: Long, val moving: Boolean)
/** Only adjacent available speed samples count. No interpolation across a connection gap. */
fun recordDriving(segments: List<DrivingSegment>, priorAt: Long, priorMoving: Boolean?, at: Long, speed: Float?): List<DrivingSegment> {
    if (priorMoving == null || speed == null || !speed.isFinite() || at - priorAt !in 1..5_000) return segments
    val moving = kotlin.math.abs(speed) >= .5f
    if (priorMoving != moving) return segments
    val last = segments.lastOrNull()
    return if (last != null && last.moving == moving && last.end == priorAt)
        segments.dropLast(1) + last.copy(end = at)
    else (segments + DrivingSegment(priorAt, at, moving)).takeLast(500)
}
