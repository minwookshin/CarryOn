package com.minwook.pleoswork

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BoardState(
    val ledger: WorkLedger? = null, val route: BoardRoute? = null,
    val navigationMode: GuidanceMode = GuidanceMode.NATIVE_SAMPLE, val connected: Boolean = false,
    val connecting: Boolean = false, val grant: DeviceGrant? = null, val connectionMessage: String = "",
    val segments: List<DrivingSegment> = emptyList(), val lastSpeedAt: Long = 0,
    val lastMoving: Boolean? = null, val expanded: Boolean = false, val largeText: Boolean = false,
)

class OperationsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("operations-v2", Context.MODE_PRIVATE)
    private val restoredLedger = OperationsCodec.decode(prefs.getString("ledger", null)) ?: migrateLegacy()
    private val restoredRoute = runCatching {
        OperationsCodec.gson.fromJson(prefs.getString("route", null), BoardRoute::class.java)?.takeIf {
            it.visitId == restoredLedger?.currentVisitId && prefs.getString("routeStream", null) == restoredLedger.streamId &&
                it.mode in GuidanceMode.entries && it.status in GuidanceStatus.entries && it.elapsedMs in 0..18_000
        }
    }.getOrNull()
    private val mutable = MutableStateFlow(BoardState(
        ledger = restoredLedger, route = restoredRoute,
        navigationMode = GuidanceMode.entries.firstOrNull { it.name == prefs.getString("navigationMode", "NATIVE_SAMPLE") } ?: GuidanceMode.NATIVE_SAMPLE,
        expanded = prefs.getBoolean("expanded", false), largeText = prefs.getBoolean("largeText", false),
        segments = runCatching { OperationsCodec.gson.fromJson(prefs.getString("segments", "[]"), Array<DrivingSegment>::class.java).toList() }.getOrDefault(emptyList()),
    ))
    val state = mutable.asStateFlow()

    private fun archive(s: WorkLedger) { prefs.edit().putString("archive:${s.accountId}:${s.streamId}", OperationsCodec.encode(s)).apply() }
    @Synchronized fun ledger(next: WorkLedger, now: Long = System.currentTimeMillis(), autoRoute: Boolean = true) {
        require(next.valid())
        val old = mutable.value
        val changed = old.ledger?.currentVisitId != next.currentVisitId || old.ledger?.streamId != next.streamId
        if (old.ledger != null && old.ledger.streamId != next.streamId) archive(old.ledger)
        val route = if (changed && autoRoute && !next.dayClosed && !next.drivingEnded) next.current?.let { BoardNavigation.start(it, old.navigationMode, now) }
            else if (changed) null else old.route
        prefs.edit().putString("ledger", OperationsCodec.encode(next))
            .putString("route", route?.let { OperationsCodec.gson.toJson(it) })
            .putString("routeStream", next.streamId).apply()
        mutable.value = old.copy(ledger = next, route = route)
    }
    @Synchronized fun update(change: (BoardState) -> BoardState) {
        val old = mutable.value
        val next = change(old)
        if (next.route != old.route) prefs.edit().putString("route", next.route?.let { OperationsCodec.gson.toJson(it) })
            .putString("routeStream", next.ledger?.streamId).apply()
        mutable.value = next
    }
    fun selectMode(mode: ServiceMode, cold: Boolean) {
        val old = state.value.ledger
        if (old != null) archive(old)
        val key = "chosen:${mode.name}:$cold"
        val existing = OperationsCodec.decode(prefs.getString(key, null))
        val raw = context.assets.open("work/${if (mode == ServiceMode.PARCEL) "parcel" else "freight"}.json").bufferedReader().use { it.readText() }
        val fixture = OperationsCodec.decode(raw) ?: error("Invalid bundled workflow")
        val next = existing ?: fixture.copy(refrigerated = cold, streamId = "local-${mode.name.lowercase()}-$cold",
            visits = fixture.visits.map { it.copy(requiredLow = if (cold) -20f else null, requiredHigh = if (cold) -18f else null) })
        update { it.copy(connected = false, connectionMessage = "", grant = null, connecting = false) }
        ledger(next)
        prefs.edit().putString(key, OperationsCodec.encode(next)).apply()
    }
    fun persistChoice() { state.value.ledger?.takeIf { it.streamId.startsWith("local-") }?.let {
        prefs.edit().putString("chosen:${it.mode.name}:${it.refrigerated}", OperationsCodec.encode(it)).apply()
    } }
    fun navigationMode(mode: GuidanceMode) {
        if (mode == state.value.navigationMode) return
        prefs.edit().putString("navigationMode", mode.name).apply()
        update { it.copy(navigationMode = mode, route = it.ledger?.current?.let { v -> BoardNavigation.start(v, mode, System.currentTimeMillis()) }) }
    }
    fun navigate() { update { s -> s.copy(route = s.ledger?.current?.let { BoardNavigation.start(it, s.navigationMode, System.currentTimeMillis()) }) } }
    fun expand() { update { it.copy(expanded = !it.expanded) }; prefs.edit().putBoolean("expanded", state.value.expanded).apply() }
    fun font() { update { it.copy(largeText = !it.largeText) }; prefs.edit().putBoolean("largeText", state.value.largeText).apply() }
    fun tick(car: VehicleState, now: Long, elapsed: Long, foreground: Boolean) {
        update { s ->
            val route = s.route?.let { BoardNavigation.tick(it, car, now, if (foreground && s.ledger?.drivingEnded != true && s.ledger?.dayClosed != true) elapsed else 0) }
            s.copy(route = route)
        }
    }
    fun vehicle(car: VehicleState) {
        if (car.speedObservedAt <= state.value.lastSpeedAt) return
        update { s -> s.copy(segments = recordDriving(s.segments, s.lastSpeedAt, s.lastMoving, car.speedObservedAt, car.speedKph),
            lastSpeedAt = car.speedObservedAt, lastMoving = car.speedKph?.let { kotlin.math.abs(it) >= .5f }) }
        prefs.edit().putString("segments", OperationsCodec.gson.toJson(state.value.segments)).apply()
    }

    /** The legacy preference remains untouched. Aggregate counts are retained, not re-invented as entrance data. */
    private fun migrateLegacy(): WorkLedger? = runCatching {
        if (prefs.getBoolean("migrationChecked", false)) return null
        prefs.edit().putBoolean("migrationChecked", true).commit()
        val raw = context.getSharedPreferences("vehicle-site-v2", Context.MODE_PRIVATE).getString("workflow-v1", null) ?: return null
        prefs.edit().putString("legacyBackup", raw).commit()
        val r = OperationsCodec.gson.fromJson(raw, DemoRun::class.java) ?: return null
        val profile = demoDrivers.firstOrNull { it.id == r.driverId } ?: return null
        val plan = profile.dispatch ?: return null
        val visits = plan.stops.map { LedgerVisit(it.id, it.name, 1, it.operation, it.access, null, it.navigationTarget,
            requiredLow = it.freight?.temperature?.low, requiredHigh = it.freight?.temperature?.high) } +
            LedgerVisit("legacy-center", "${profile.carrier} 복귀센터", 1, "인계", null, null, plan.returnTarget)
        val tasks = plan.stops.flatMap { stop ->
            val result = r.results[stop.id] ?: StopResult()
            fun batch(kind: TaskKind, count: Int, done: Int) = if (count <= 0) emptyList() else listOf(
                LedgerTask("legacy:${stop.id}:${kind.name}", stop.id, stop.id, stop.name, "출입구 미수신", kind, count,
                    completed = done.coerceIn(0,count), sync = if (done > 0) SyncMark.CONFIRMED else SyncMark.OPEN))
            stop.parcel?.let { batch(TaskKind.DELIVERY, it.deliveries, result.deliveries) + batch(TaskKind.RETURN, it.pickups, result.pickups) }
                ?: batch(if (stop.operation == "하차") TaskKind.UNLOAD else if (stop.operation == "상차") TaskKind.LOAD else TaskKind.CONTAINER,
                    stop.freight?.units ?: 0, result.units) + if (stop.operation == "하차") batch(TaskKind.RECEIPT,1,if (result.receipt) 1 else 0) else emptyList()
        }
        WorkLedger(accountId = "legacy:${profile.id}", streamId = "legacy:${r.sessionId}", driverName = profile.name,
            mode = profile.mode, refrigerated = profile.refrigerated, visits = visits, tasks = tasks,
            currentVisitId = if (r.returning) "legacy-center" else plan.stops.getOrNull(r.index)?.id,
            dayClosed = r.stage == RunStage.ENDED, drivingEnded = r.dayEnded, lastSyncAt = r.lastReceivedAt,
            sourceLabel = "이전 예시 기록", note = "이전 버전의 작업을 보존했습니다. 연결 설정에서 새 시험 업무를 선택할 수 있습니다.")
            .takeIf { it.valid() }?.also { prefs.edit().putString("ledger", OperationsCodec.encode(it)).commit() }
    }.getOrNull()
}
