package com.minwook.pleoswork

data class Stop(val id: String, val name: String, val detail: String, val time: String, val entrance: String, val quantity: Int, val pickups: Int, val driveSeconds: Int = 720, val driveKm: Double = 6.4, val serviceMinutes: Int = 24)

val Stops = listOf(
    Stop("hanbit", "한빛아파트", "104·105동", "09:00", "정문 · 배송차 진입", 12, 2),
    Stop("shops", "한빛상가", "1층 · 상가동", "09:40", "상가 뒤편 하역 구역", 5, 0, 480, 3.2, 10),
    Stop("office", "서현 오피스", "2·3층", "10:10", "지하 1층 배송 접수처", 8, 1, 660, 4.8, 20),
)
val FreightStop = Stop("freight", "B물류센터", "냉동 화물 · 12팔레트", "13:00", "동측 화물 출입구", 12, 0, 1920, 20.4, 25)

data class Parcel(
    val id: String, val stop: String, val pickup: Boolean, val building: String,
    val unit: String, val zone: String, val note: String,
    val status: String = "pending", val proof: Boolean = false, val reason: String = "",
)

// A short, stable demo reference distinguishes several parcels at the same address.
val Parcel.reference: String get() = "${if(pickup) "회수" else "배송"} ${id.substringAfterLast('-').drop(1).padStart(2,'0')}"

fun seedParcels(): List<Parcel> = Stops.flatMapIndexed { si, stop ->
    (0 until stop.quantity).map { i ->
        val building = when (si) { 0 -> if (i < 8) "104동" else "105동"; 1 -> "상가동"; else -> if (i < 4) "2층" else "3층" }
        val unit = if (si == 0) "${(if (i < 8) i else i - 8) / 2 + (if (i < 8) 4 else 3)}0${i % 2 + 1}호" else "${if (si == 1) 1 else if (i < 4) 2 else 3}0${i % 4 + 1}호"
        Parcel("${stop.id}-d${i+1}", stop.id, false, building, unit,
            if (si == 0) { if (i < 8) "A" else "B" } else if (si == 1) "C" else "D",
            listOf("문 앞에 놓아 주세요.", "배송 후 알림을 남겨 주세요.", "직접 전달해 주세요.")[i % 3])
    } + (0 until stop.pickups).map { i ->
        Parcel("${stop.id}-p${i+1}", stop.id, true, if (si == 0) "105동" else "3층", "${i+5}01호", "회수", "문 앞 반품 상자 · 송장 확인")
    }
}

data class WorkState(
    val version: Int = 2,
    val scenario: String = "parcel",
    val stopIndex: Int = 0,
    val phase: String = "prepare",
    val demoMode: Boolean = true,
    val demoClock: Int = 8 * 3600 + 48 * 60,
    val completedStops: Set<String> = emptySet(),
    val completedAt: Map<String, Int> = emptyMap(),
    val arrivedAt: Map<String, Int> = emptyMap(),
    val travelElapsed: Int = 0,
    val trafficDelay: Int = 0,
    val demoRangeKm: Double = 280.0,
    val demoFuelPercent: Double = 62.0,
    val demoEnergyType: String = "fuel",
    val travelledKm: Double = 0.0,
    val bindings: Map<String, NavTarget> = emptyMap(),
    val parcels: List<Parcel> = seedParcels(),
    val bundle: Set<String> = seedParcels().filter { it.stop == "hanbit" && !it.pickup }.map { it.id }.toSet(),
    val descending: Boolean = false,
    val dock: String = "",
    val unloaded: Boolean = false,
    val signed: Boolean = false,
    val ended: Boolean = false,
    val restStartedAt: Long? = null,
    val restMs: Long = 0,
    val events: List<String> = emptyList(),
) {
    val routeStops: List<Stop> get() = if (scenario == "freight") listOf(FreightStop) else Stops
    val stop: Stop get() = routeStops[stopIndex.coerceIn(0, routeStops.lastIndex)]
    val stopParcels: List<Parcel> get() = parcels.filter { it.stop == stop.id }
    val fieldItems: List<Parcel> get() = stopParcels.filter { it.pickup || it.id in bundle || it.status != "pending" }
        .sortedBy { it.building + it.unit }.let { if(descending) it.reversed() else it }
    val deliveryCount: Int get() = parcels.count { !it.pickup && it.status == "done" }
    val pickupCount: Int get() = parcels.count { it.pickup && it.status == "done" }
    val deferredCount: Int get() = parcels.count { it.status == "deferred" }
    val stopResolved: Boolean get() = stopParcels.all { it.status != "pending" }
}

object WorkReducer {
    fun record(state: WorkState, id: String, status: String, proof: Boolean, reason: String, canWork: Boolean): WorkState {
        if (!canWork || state.ended || state.restStartedAt != null || state.phase !in setOf("field", "return") || status !in setOf("done", "deferred", "pending")) return state
        val item = state.parcels.find { it.id == id } ?: return state
        if (item.stop != state.stop.id || item.status == status) return state
        if (status == "done" && !proof || status == "deferred" && reason.isBlank()) return state
        return state.copy(parcels = state.parcels.map {
            if (it.id == id) it.copy(status = status, proof = status == "done" && proof, reason = if (status == "deferred") reason else "") else it
        }, demoClock = state.demoClock + if (state.demoMode && status != "pending") 30 else 0,
            events = (listOf("${item.building} ${item.unit} · ${when(status) { "done" -> if(item.pickup) "회수 확인" else "배송 확인"; "deferred" -> "보류: $reason"; else -> "처리 취소" }}") + state.events).take(100))
    }

    fun chooseStop(state: WorkState, index: Int, canWork: Boolean): WorkState {
        if (!canWork || state.ended || state.scenario != "parcel" || state.restStartedAt != null || index != state.stopIndex + 1 || index !in Stops.indices || !state.stopResolved || state.phase != "return") return state
        return state.copy(stopIndex = index, phase = "prepare", travelElapsed = 0, trafficDelay = 0,
            completedStops = state.completedStops + state.stop.id, completedAt = state.completedAt + (state.stop.id to state.demoClock),
            bundle = state.parcels.filter { it.stop == Stops[index].id && !it.pickup && it.status == "pending" }.map { it.id }.toSet())
    }

    fun nextStop(state: WorkState, canWork: Boolean): WorkState {
        if (!canWork || !state.stopResolved || state.ended || state.scenario != "parcel" || state.restStartedAt != null || state.phase != "return") return state
        return if (state.stopIndex < Stops.lastIndex) chooseStop(state, state.stopIndex + 1, true) else state.copy(phase = "finish", completedStops = state.completedStops + state.stop.id, completedAt = state.completedAt + (state.stop.id to state.demoClock))
    }

    fun saveBundle(state: WorkState, ids: Set<String>, descending: Boolean, canWork: Boolean): WorkState {
        if (!canWork || state.ended || state.phase !in setOf("prepare", "arrival", "return", "field")) return state
        val valid = state.stopParcels.filter { !it.pickup && it.status == "pending" }.map { it.id }.toSet()
        return state.copy(bundle = ids.intersect(valid), descending = descending)
    }

    fun freight(state: WorkState, action: String, checked: Boolean, canWork: Boolean): WorkState {
        if (!canWork || state.ended || state.scenario != "freight" || state.restStartedAt != null) return state
        return when (action) {
            "unload" -> if (state.phase == "arrival" && !state.unloaded && state.dock.isNotBlank() && checked) state.copy(unloaded = true, demoClock = state.demoClock + if(state.demoMode) 20 * 60 else 0, events = (listOf("12팔레트 · 하차 확인") + state.events).take(100)) else state
            "sign" -> if (state.phase == "arrival" && state.unloaded && !state.signed && checked) state.copy(signed = true, events = (listOf("화물 · 인수 확인") + state.events).take(100)) else state
            "finish" -> if (state.phase == "arrival" && state.unloaded && state.signed) state.copy(ended = true, phase = "finish", completedStops = setOf(FreightStop.id), events = (listOf("화물 운송 · 업무 종료") + state.events).take(100)) else state
            else -> state
        }
    }

    fun toggleRest(state: WorkState, now: Long, canWork: Boolean): WorkState {
        if (!canWork || state.ended || state.phase == "transit") return state
        return if (state.restStartedAt == null) state.copy(restStartedAt = now)
        else stopRest(state, now)
    }

    fun stopRest(state: WorkState, now: Long): WorkState {
        val start = state.restStartedAt ?: return state
        val elapsed = (now - start).coerceAtLeast(0)
        return state.copy(restStartedAt = null, restMs = state.restMs + elapsed,
            demoClock = state.demoClock + if (state.demoMode) (elapsed / 1000).toInt() else 0)
    }

    fun end(state: WorkState, canWork: Boolean): WorkState {
        if (!canWork || state.restStartedAt != null || state.phase != "finish" || state.parcels.any { it.status == "pending" }) return state
        return state.copy(ended = true, phase = "finish")
    }
}

data class VehicleState(
    val automotive: Boolean = false,
    val uxRestricted: Boolean? = null,
    val speedKph: Float? = null,
    val gear: Int? = null,
    val parkingBrake: Boolean? = null,
    val doorPosition: Int? = null,
    val vehicleStatus: String = "연결 대기",
    val navigationStatus: String = "연결 대기",
    val routeStatus: String = "idle",
    val routeMessage: String = "",
    val routeEventAt: Long = 0,
    val navigation: NavReading? = null,
    val navigationObservedAt: Long = 0,
    val navigationArrival: NavArrival? = null,
    val navigationScreenMessage: String = "",
    val fuelPercent: Int? = null,
    val batteryPercent: Int? = null,
    val rangeKm: Double? = null,
    val energyStatus: String = "연결 대기",
    val fuelUpdatedAt: Long = 0,
    val batteryUpdatedAt: Long = 0,
    val rangeUpdatedAt: Long = 0,
    val errors: List<String> = emptyList(),
) {
    // Unknown states and all non-P gears fail closed, including D at 0 km/h.
    val canWork: Boolean get() = automotive && uxRestricted == false && gear == 4 && speedKph != null && kotlin.math.abs(speedKph) < 0.5f
}
