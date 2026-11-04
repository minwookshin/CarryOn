package com.minwook.pleoswork

enum class ServiceMode(val label: String) { PARCEL("택배"), FREIGHT("화물") }

data class SiteEntrance(val name: String, val heightCm: Int, val note: String)
data class SiteBay(val name: String, val side: String, val note: String)
data class VehicleSite(
    val name: String,
    val location: String,
    val seconds: Int,
    val meters: Int,
    val entrances: List<SiteEntrance>,
    val bays: List<SiteBay>,
    val waitingPlace: String,
)

/** Fictional facility information, not a truck-routing engine or live terminal feed. */
val parcelSite = VehicleSite(
    "한빛아파트 101동", "남측 진입로 · 지상 하역구역", 480, 2300,
    listOf(SiteEntrance("남문 · 지상", 330, "남문에서 우회전 후 지상 하역구역 방향"),
        SiteEntrance("북문 · 지하", 210, "지하주차장 출입구")),
    listOf(SiteBay("101동 동측", "우측 하차", "보행 출입구 앞 공간을 비워두세요"),
        SiteBay("관리동 하역구역", "후면 하차", "관리동 옆 지정 구역")),
    "관리동 앞 대기구역",
)
val freightSite = VehicleSite(
    "미래물류센터 B동", "화물 출입구 · B동 하역장", 840, 6200,
    listOf(SiteEntrance("화물 출입구", 420, "경비실 통과 후 B동 방향"),
        SiteEntrance("직원 주차장", 230, "승용차 전용 출입구")),
    listOf(SiteBay("03번 도크", "후면 접안", "현장 유도자의 안내를 따라 접안하세요"),
        SiteBay("04번 도크", "후면 접안", "현장 유도자의 안내를 따라 접안하세요")),
    "W2 대기구역",
)

enum class ParkingExample(val label: String, val pitch: Float, val roll: Float) {
    FLAT("평지", 0.2f, -0.1f),
    RAMP("오르막", 4.8f, -1.2f),
    SIDE("옆 경사", 0.6f, 3.5f),
}

data class CompanionState(
    val demo: Boolean = true,
    val terminalConnected: Boolean = true,
    val link: DriverLink = DriverLink(),
    val entranceIndex: Int = 0,
    val bayIndex: Int = 0,
    val vehicleHeightCm: Int = 260,
    val parking: ParkingExample = ParkingExample.RAMP,
    val cargoLocked: Boolean = true,
    val cargoLight: Boolean = false,
    val cargoOpen: Boolean = false,
    val coolingOn: Boolean = true,
    val targetTemperature: Int = 4,
    val trafficDelayMinutes: Int = 0,
    val run: DemoRun? = null,
    val guidanceMode: GuidanceMode = GuidanceMode.DEMO,
) {
    val driver: DriverProfile? get() = link.driver
    val mode: ServiceMode get() = driver?.mode ?: ServiceMode.PARCEL
    // Actual P/speed/UX restrictions are checked by each reducer. A demo stage is not vehicle motion.
    val canControl: Boolean get() = demo && driver != null && run?.dayEnded != true && run?.stage != RunStage.ENDED
    val refrigerated: Boolean get() = driver?.refrigerated == true
    val site: VehicleSite? get() = if (!demo) null else run?.let(::workflowSite) ?: if (terminalConnected) driver?.assignment else null
    val dispatch: DriverDispatch? get() = if (!demo) null else run?.let { it.plan.copy(currentIndex = it.index, referenceMinute = it.minute) }
        ?: if (terminalConnected) driver?.dispatch else null
    val airTemperature: Float? get() = if (demo && refrigerated) run?.let { if (it.sensor == SensorExample.OFFLINE) null else it.sensorReading } ?: if (run == null) -19.2f else null else null
    val entrance: SiteEntrance? get() = site?.entrances?.getOrNull(entranceIndex)
    val bay: SiteBay? get() = site?.bays?.getOrNull(bayIndex)
}

/** No state change here writes to a vehicle, dispatch system or delivery record. */
object CompanionReducer {
    fun traffic(s: CompanionState, delayed: Boolean, parked: Boolean): CompanionState =
        if (!parked || s.dispatch == null) s else s.copy(trafficDelayMinutes = if (delayed) 25 else 0)
    fun entrance(s: CompanionState, index: Int, parked: Boolean): CompanionState {
        val candidate = s.site?.entrances?.getOrNull(index) ?: return s
        return if (!parked || candidate.heightCm <= s.vehicleHeightCm) s else s.copy(entranceIndex = index)
    }
    fun bay(s: CompanionState, index: Int, parked: Boolean): CompanionState =
        if (!parked || s.site?.bays?.getOrNull(index) == null) s else s.copy(bayIndex = index)
    fun lock(s: CompanionState, locked: Boolean, parked: Boolean): CompanionState =
        if (!s.canControl || !parked || (locked && s.cargoOpen)) s else s.copy(cargoLocked = locked)
    fun light(s: CompanionState, on: Boolean, parked: Boolean): CompanionState =
        if (!s.canControl || !parked) s else s.copy(cargoLight = on)
    fun door(s: CompanionState, open: Boolean, parked: Boolean): CompanionState =
        if (!s.canControl || !parked) s else s.copy(cargoOpen = open, cargoLocked = if (open) false else s.cargoLocked)
    fun cooling(s: CompanionState, on: Boolean, parked: Boolean): CompanionState =
        if (!s.canControl || !parked || !s.refrigerated) s else s.copy(coolingOn = on)
    fun temperature(s: CompanionState, value: Int, parked: Boolean): CompanionState =
        if (!s.canControl || !parked || !s.refrigerated || !s.coolingOn || value !in setOf(-18, 4, 8)) s else s.copy(targetTemperature = value)
}
