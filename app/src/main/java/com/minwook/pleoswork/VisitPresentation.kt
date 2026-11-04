package com.minwook.pleoswork

/** Selecting the active visit returns to live work, including restored old preview state. */
fun DemoRun.previewVisit(selected: String?): String? = selected?.takeIf { id ->
    id != navigationVisitId && plan.stops.any { it.id == id }
}

fun DemoRun.liveVisitVisible(panel: String, selected: String?, sideNavigation: Boolean = false): Boolean =
    sideNavigation || (panel == "current" && previewVisit(selected) == null)

data class SiteHint(val label: String, val text: String)

/** Show approach guidance only near the destination, then the actual stop/dock while working. */
fun CompanionState.siteHint(car: VehicleState, now: Long): SiteHint? {
    val r = run ?: return null
    if (r.resting || r.dayEnded || r.parcelAway) return null
    if (r.stage in setOf(RunStage.TRAVEL, RunStage.RETURN_TRAVEL)) {
        val leg = r.guidance ?: return null
        if (leg.status != GuidanceStatus.ACTIVE) return null
        val approaching = if (leg.mode == GuidanceMode.DEMO) leg.status == GuidanceStatus.ACTIVE && leg.progress >= .55f
            else linkedNavigation(r, car.navigation, now)?.meters?.let { it <= 1200 } == true
        if (!approaching) return null
        return SiteHint("진입·정차", listOfNotNull(entrance?.name, stoppingPlace()).distinct().joinToString(" · "))
    }
    return when (r.stage) {
        RunStage.ARRIVED, RunStage.WAITING -> SiteHint(if (mode == ServiceMode.FREIGHT) "대기 위치" else "정차 위치", stoppingPlace())
        RunStage.WORKING -> SiteHint(if (mode == ServiceMode.FREIGHT) "배정 도크" else "정차·하차", listOfNotNull(stoppingPlace(), bay?.side).distinct().joinToString(" · "))
        RunStage.RETURNED -> SiteHint("반납·인계 위치", stoppingPlace())
        else -> null
    }
}

/** Presentation follows the work stage; physical vehicle restrictions still gate every action. */
val DemoRun.atFacility: Boolean get() = !dayEnded && !resting && stage in setOf(
    RunStage.ARRIVED, RunStage.WAITING, RunStage.WORKING, RunStage.REVIEW, RunStage.RETURNED)

val DemoRun.showNavigation: Boolean get() = !dayEnded && !resting && stage in setOf(
    RunStage.PREPARING, RunStage.TRAVEL, RunStage.RETURN_TRAVEL)

fun CompanionState.needsColdAttention(): Boolean {
    if (!refrigerated) return false
    val r = run ?: return false
    // Empty-container and return legs do not have a cold-load requirement.
    val requirement = r.stop.freight?.temperature?.takeUnless { r.returning } ?: return false
    return r.sensor != SensorExample.NORMAL || !coolingOn ||
        !requirement.contains(airTemperature) || !requirement.contains(targetTemperature.toFloat())
}

/** A released parking brake while driving is normal, not an app warning. */
fun CompanionState.showParkingCheck(car: VehicleState): Boolean =
    run?.atFacility == true && car.canWork && car.parkingBrake != true

data class VehicleNotice(val id: String, val message: String)

/** Read-only exceptions, visible above every panel. Normal states do not consume space. */
fun CompanionState.vehicleNotices(car: VehicleState): List<VehicleNotice> = buildList {
    val r = run ?: return@buildList
    if (showParkingCheck(car)) add(VehicleNotice("parking-brake", if (car.parkingBrake == false)
        "주차 브레이크 해제 · 차량에서 확인하세요" else "주차 브레이크 미수신 · 차량에서 확인하세요"))
    if (cargoOpen && r.stage in setOf(RunStage.PREPARING, RunStage.TRAVEL, RunStage.REVIEW, RunStage.DEPARTURE, RunStage.RETURN_TRAVEL))
        add(VehicleNotice("cargo-door", "적재함 문 열림 · 닫은 뒤 출발하세요 · 예시"))
    if (needsColdAttention()) {
        val reason = when {
            r.sensor == SensorExample.OFFLINE -> "센서 미수신 · 마지막 ${r.sensorReading}°C"
            !coolingOn -> "냉동기 꺼짐"
            r.stop.freight?.temperature?.contains(targetTemperature.toFloat()) == false -> "설정 ${targetTemperature}°C · 요구 범위 밖"
            else -> "공기 ${airTemperature}°C · 요구 범위 이탈"
        }
        add(VehicleNotice("cold-chain", "$reason · 냉동 상태 확인 · 예시"))
    }
}

/** One map presentation per visit; no reopening loop when the user returns from the map. */
fun CompanionState.navigationPresentationKey(car: VehicleState): String? {
    val r = run ?: return null
    if (!demo || !r.showNavigation || r.pending.isNotEmpty() || cargoOpen || !car.canWork || car.navigationStatus != "연결됨") return null
    return "${r.sessionId}:${r.navigationVisitId}"
}

fun CompanionState.stoppingPlace(): String {
    val facility = site ?: return "위치 미수신"
    return if (mode == ServiceMode.FREIGHT && run?.returning != true)
        run?.result?.dock.orEmpty().ifBlank { facility.waitingPlace }
    else bay?.name ?: facility.waitingPlace
}
