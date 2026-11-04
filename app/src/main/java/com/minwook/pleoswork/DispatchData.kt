package com.minwook.pleoswork

import java.util.Locale
import kotlin.math.ceil

data class LoadZone(val name: String, val units: Int)
enum class PackageKind(val label: String) { LARGE("큰 상자"), MEDIUM("보통 상자"), SMALL("작은 상자"), CONTAINER("회수 용기") }
data class PackageCount(val kind: PackageKind, val count: Int) { init { require(count > 0) } }
data class ParcelLoad(val deliveries: Int, val pickups: Int, val zones: List<LoadZone>,
    val packages: List<PackageCount> = emptyList()) {
    init { validatePackageCounts(packages, deliveries) }
}
private fun validatePackageCounts(packages: List<PackageCount>, total: Int) {
    require(packages.map { it.kind }.distinct().size == packages.size)
    require(packages.isEmpty() || packages.sumOf { it.count } == total)
}
data class TemperatureRequirement(val low: Float, val high: Float) {
    fun contains(measured: Float?) = measured?.let { it.isFinite() && it in low..high } == true
    val label: String get() = "${low.toInt()} ~ ${high.toInt()}°C"
}
data class FreightLoad(val name: String, val units: Int, val unit: String, val weightKg: Int,
    val location: String, val equipment: String, val temperature: TemperatureRequirement? = null,
    val packages: List<PackageCount> = emptyList()) {
    init { validatePackageCounts(packages, units) }
}
data class VisitWindow(val startMinute: Int, val endMinute: Int) {
    init { require(startMinute >= 0 && endMinute >= startMinute) }
    val label: String get() = "${dispatchTime(startMinute)}–${dispatchTime(endMinute)}"
}
data class DispatchStop(val id: String, val name: String, val operation: String,
    val window: VisitWindow, val parcel: ParcelLoad? = null, val freight: FreightLoad? = null,
    val access: String, val note: String, val navigationTarget: NavTarget? = null) {
    val quantity: String get() = parcel?.let { "배송 ${it.deliveries}개 · 회수 ${it.pickups}건" }
        ?: freight?.let { "${it.units}${it.unit} · ${it.weightKg} kg" } ?: "물량 미수신"
}
data class DriverDispatch(val id: String, val referenceMinute: Int, val currentIndex: Int, val stops: List<DispatchStop>,
    val returnTarget: NavTarget? = null) {
    init { require(currentIndex in stops.indices); require(stops.map { it.id }.distinct().size == stops.size) }
    val current: DispatchStop get() = stops[currentIndex]
    val remaining: List<DispatchStop> get() = stops.drop(currentIndex)
    fun preview(id: String?, parked: Boolean): DispatchStop = if (parked) stops.find { it.id == id } ?: current else current
}

enum class ArrivalKind { EARLY, WITHIN_WINDOW, LATE }
data class ArrivalComparison(val kind: ArrivalKind, val minutes: Int) {
    val label: String get() = when (kind) {
        ArrivalKind.EARLY -> "예약 시작 ${minutes}분 전"
        ArrivalKind.WITHIN_WINDOW -> "예약 시간대 안"
        ArrivalKind.LATE -> "예약 종료보다 ${minutes}분 늦음"
    }
}
fun compareArrival(arrivalMinute: Int, window: VisitWindow) = when {
    arrivalMinute < window.startMinute -> ArrivalComparison(ArrivalKind.EARLY, window.startMinute - arrivalMinute)
    arrivalMinute > window.endMinute -> ArrivalComparison(ArrivalKind.LATE, arrivalMinute - window.endMinute)
    else -> ArrivalComparison(ArrivalKind.WITHIN_WINDOW, 0)
}
fun dispatchTime(minute: Int): String {
    val day = minute / 1440
    val time = String.format(Locale.US, "%02d:%02d", (minute % 1440) / 60, minute % 60)
    return if (day == 0) time else "${day}일 뒤 $time"
}
fun CompanionState.arrivalMinute(): Int? = dispatch?.let { plan ->
    site?.let { plan.referenceMinute + ceil(it.seconds / 60.0).toInt() + trafficDelayMinutes }
}

// Fictional, driver-scoped terminal snapshots. No customer addresses or delivery records.
val parcelEastDispatch = DriverDispatch("P-1042", 620, 0, listOf(
    DispatchStop("east-hanbit", "한빛아파트 101동", "배송·회수", VisitWindow(620, 650),
        parcel = ParcelLoad(12, 2, listOf(LoadZone("A구역", 8), LoadZone("B구역", 4)), listOf(PackageCount(PackageKind.LARGE, 4), PackageCount(PackageKind.MEDIUM, 8))),
        access = "남문 · 지상 하역구역", note = "오늘은 남문으로 진입 · 회수품은 R구역에 분리", navigationTarget = nativeSampleTargets[0]),
    DispatchStop("east-shops", "한빛상가", "배송", VisitWindow(660, 680),
        parcel = ParcelLoad(5, 0, listOf(LoadZone("C구역", 5)), listOf(PackageCount(PackageKind.LARGE, 1), PackageCount(PackageKind.MEDIUM, 4))),
        access = "상가 후면 · 지정 하역구역", note = "후면 출입구 사용 · 카트 준비", navigationTarget = nativeSampleTargets[1]),
    DispatchStop("east-daon", "다온오피스", "배송·회수", VisitWindow(700, 730),
        parcel = ParcelLoad(8, 1, listOf(LoadZone("D구역", 8)), listOf(PackageCount(PackageKind.LARGE, 3), PackageCount(PackageKind.MEDIUM, 5))),
        access = "동문 · 관리실 옆", note = "회수품 1건 예정 · 물품 식별은 단말에서 확인", navigationTarget = nativeSampleTargets[0]),
), returnTarget = nativeSampleTargets[1])
val parcelWestDispatch = DriverDispatch("P-2071", 620, 0, listOf(
    DispatchStop("west-tower", "서린타워", "배송·회수", VisitWindow(620, 650),
        parcel = ParcelLoad(9, 1, listOf(LoadZone("B구역", 6), LoadZone("C구역", 3)), listOf(PackageCount(PackageKind.LARGE, 3), PackageCount(PackageKind.MEDIUM, 6))),
        access = "서문 · 지상 하역장", note = "지하 진입 제한 · 서문 하역장으로 진입", navigationTarget = nativeSampleTargets[0]),
    DispatchStop("west-park", "파크빌라", "배송·회수", VisitWindow(660, 690),
        parcel = ParcelLoad(6, 2, listOf(LoadZone("D구역", 6)), listOf(PackageCount(PackageKind.LARGE, 2), PackageCount(PackageKind.MEDIUM, 4))),
        access = "북측 지정 정차구역", note = "회수 물품용 빈 바구니 2개 준비", navigationTarget = nativeSampleTargets[1]),
), returnTarget = nativeSampleTargets[0])
val coldFreightDispatch = DriverDispatch("F-2208", 746, 1, listOf(
    DispatchStop("cold-pickup", "중앙저온센터", "상차", VisitWindow(570, 600),
        freight = FreightLoad("냉동식품", 24, "상자", 480, "냉동 구획 A", "하역 카트", TemperatureRequirement(-20f, -18f), listOf(PackageCount(PackageKind.LARGE, 8), PackageCount(PackageKind.MEDIUM, 16))),
        access = "02번 도크", note = "단말 수신 상태: 상차 완료 · 수량 24상자", navigationTarget = nativeSampleTargets[0]),
    DispatchStop("cold-delivery", "미래물류센터 B동", "하차", VisitWindow(780, 810),
        freight = FreightLoad("냉동식품", 24, "상자", 480, "냉동 구획 A", "하역 카트", TemperatureRequirement(-20f, -18f), listOf(PackageCount(PackageKind.LARGE, 8), PackageCount(PackageKind.MEDIUM, 16))),
        access = "화물 출입구 · 03번 도크", note = "경비실에서 배차 F-2208 확인 · 후면 접안", navigationTarget = nativeSampleTargets[1]),
    DispatchStop("cold-next", "중앙용기센터 C동", "빈 용기 회수", VisitWindow(870, 900),
        freight = FreightLoad("빈 운반 용기", 4, "개", 40, "회수 구획 R", "고정 벨트", packages = listOf(PackageCount(PackageKind.CONTAINER, 4))),
        access = "남측 화물 출입구 · 도크 배정 대기", note = "다음 구간 배정 · 회수 후 고정 위치 확인", navigationTarget = nativeSampleTargets[0]),
), returnTarget = nativeSampleTargets[1])
