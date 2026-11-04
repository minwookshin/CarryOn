package com.minwook.pleoswork

import kotlin.math.abs
import kotlin.math.ceil

data class NavTarget(val name: String, val latitude: Double, val longitude: Double, val address: String = "", val poiId: String = "", val poiSubId: String? = null)
data class NavReading(val target: NavTarget, val seconds: Int, val meters: Int, val receivedAt: Long) {
    fun fresh(now: Long) = now >= receivedAt && now - receivedAt <= 90_000
    fun matches(other: NavTarget?) = other != null && abs(target.latitude-other.latitude) < .0001 && abs(target.longitude-other.longitude) < .0001 && target.name == other.name
}
data class JourneyEstimate(val seconds: Int?, val km: Double?, val source: String, val rangeKm: Double?, val fuelPercent: Int?, val batteryPercent: Int?) {
    val arrivalRange: Double? get() = if(rangeKm != null && km != null) rangeKm - km else null
    val lowRange: Boolean get() = arrivalRange?.let { it < 30 } ?: false
}

fun journeyEstimate(s: WorkState, car: VehicleState, now: Long): JourneyEstimate {
    if (s.demoMode) {
        val total = s.stop.driveSeconds + s.trafficDelay
        val remaining = if(s.phase in setOf("arrival", "field", "return", "finish")) 0 else (total-s.travelElapsed).coerceAtLeast(0)
        val km = if (total == 0) 0.0 else s.stop.driveKm * remaining.toDouble() / total
        return JourneyEstimate(remaining, km, "데모", s.demoRangeKm, if(s.demoEnergyType == "fuel") s.demoFuelPercent.toInt() else null, if(s.demoEnergyType == "ev") s.demoFuelPercent.toInt() else null)
    }
    val reading = car.navigation?.takeIf { it.fresh(now) && it.matches(s.bindings[s.stop.id]) }
    val arrived = s.phase in setOf("arrival", "field", "return", "finish")
    fun fresh(at: Long) = at > 0 && now-at in 0..90_000
    return JourneyEstimate(if(arrived) 0 else reading?.seconds, if(arrived) 0.0 else reading?.meters?.div(1000.0), if(arrived) "도착 확인" else if(reading != null) "내비 수신" else "내비 확인 필요", car.rangeKm.takeIf { fresh(car.rangeUpdatedAt) }, car.fuelPercent.takeIf { fresh(car.fuelUpdatedAt) }, car.batteryPercent.takeIf { fresh(car.batteryUpdatedAt) })
}
fun clockLabel(seconds: Int): String = "%02d:%02d".format((seconds/3600)%24, (seconds/60)%60)
fun durationLabel(seconds: Int?): String = seconds?.let { "${ceil(it / 60.0).toInt()}분" } ?: "—"
fun WorkState.serviceSeconds(): Int = if (scenario == "freight") (if(unloaded) 0 else 25*60) else (stop.serviceMinutes*60*stopParcels.count { it.status == "pending" }.toDouble()/stopParcels.size.coerceAtLeast(1)).toInt()
fun scheduleEstimates(s: WorkState, car: VehicleState, now: Long, localSeconds: Int): List<Int?> {
    val estimate = journeyEstimate(s,car,now)
    var at: Int? = estimate.seconds?.let { (if(s.demoMode) s.demoClock else localSeconds)+it }
    return s.routeStops.mapIndexed { i, stop ->
        when {
            i < s.stopIndex || stop.id in s.completedStops -> s.arrivedAt[stop.id]
            i == s.stopIndex -> s.arrivedAt[stop.id] ?: at
            !s.demoMode -> null
            else -> {
                val previousService = if(i == s.stopIndex+1) s.serviceSeconds() else s.routeStops[i-1].serviceMinutes*60
                at = at?.plus(previousService + stop.driveSeconds)
                at
            }
        }
    }
}

object JourneyReducer {
    fun depart(s: WorkState, allowed: Boolean, routeAvailable: Boolean): WorkState {
        if(!allowed || s.ended || s.phase != "prepare" || !routeAvailable || s.restStartedAt != null) return s
        return s.copy(phase="transit", events=(listOf("${s.stop.name} · 출발")+s.events).take(100))
    }
    fun advance(s: WorkState, seconds: Int, allowed: Boolean): WorkState {
        if(!s.demoMode || !allowed || s.ended || s.phase != "transit" || seconds <= 0) return s
        val total=s.stop.driveSeconds+s.trafficDelay
        val delta=seconds.coerceAtMost((total-s.travelElapsed).coerceAtLeast(0))
        val distance=s.stop.driveKm*delta.toDouble()/total.coerceAtLeast(1)
        val elapsed=s.travelElapsed+delta
        return s.copy(travelElapsed=elapsed, demoClock=s.demoClock+delta,
            demoRangeKm=(s.demoRangeKm-distance).coerceAtLeast(0.0), demoFuelPercent=(s.demoFuelPercent-distance/4.5).coerceAtLeast(0.0), travelledKm=s.travelledKm+distance,
            arrivedAt=if(elapsed>=total) s.arrivedAt+(s.stop.id to s.demoClock+delta) else s.arrivedAt,
            phase=if(elapsed>=total) "arrival" else "transit", events=if(elapsed>=total) (listOf("${s.stop.name} · 도착")+s.events).take(100) else s.events)
    }
    fun delay(s: WorkState, allowed: Boolean): WorkState {
        if(!s.demoMode || !allowed || s.ended || s.phase !in setOf("prepare","transit")) return s
        // Preserve already-travelled distance when adding a delay. Time is added at a standstill.
        return s.copy(demoClock=s.demoClock+300, events=(listOf("교통 정체 · 일정 5분 지연")+s.events).take(100))
    }
    fun arrive(s: WorkState, allowed: Boolean, clockSeconds: Int): WorkState = if(allowed && !s.ended && s.phase=="transit") s.copy(phase="arrival", arrivedAt=s.arrivedAt+(s.stop.id to clockSeconds), events=(listOf("${s.stop.name} · 도착·주차 확인")+s.events).take(100)) else s
    fun enterField(s: WorkState, allowed: Boolean): WorkState = if(allowed && !s.ended && s.phase in setOf("arrival","return") && s.restStartedAt==null) s.copy(phase="field") else s
    fun review(s: WorkState, allowed: Boolean): WorkState = if(allowed && !s.ended && s.phase=="field") s.copy(phase="return") else s
    fun completeDemoStop(s: WorkState, allowed: Boolean): WorkState {
        if(!s.demoMode || !allowed || s.ended || s.restStartedAt != null || s.stopResolved || s.scenario!="parcel" || s.phase !in setOf("arrival","field","return")) return s
        return s.copy(parcels=s.parcels.map { if(it.stop==s.stop.id && it.status=="pending") it.copy(status="done",proof=true) else it },
            phase="return", demoClock=s.demoClock+s.serviceSeconds(), events=(listOf("${s.stop.name} · 남은 예시 물품 일괄 완료 (데모)")+s.events).take(100))
    }
    fun bind(s: WorkState, target: NavTarget, allowed: Boolean): WorkState = if(allowed && !s.demoMode && !s.ended && s.phase=="prepare") s.copy(bindings=s.bindings+(s.stop.id to target)) else s
}
