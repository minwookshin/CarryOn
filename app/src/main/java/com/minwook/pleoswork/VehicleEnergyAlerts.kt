package com.minwook.pleoswork

data class VehicleEnergyAlert(val source: String, val severity: Int, val title: String, val detail: String) {
    val id: String get() = "$source:$severity"
}

/** App-level reminders, not manufacturer warning signals. Never interpret stale/missing values as low. */
class VehicleEnergyAlerts {
    private val notified = mutableMapOf<String, Int>()

    fun takeNext(car: VehicleState, now: Long, aboveSeverity: Int = 0): VehicleEnergyAlert? {
        fun fresh(at: Long) = at > 0 && now - at in 0..90_000
        val fuel = car.fuelPercent?.takeIf { it in 0..100 && fresh(car.fuelUpdatedAt) }
        val battery = car.batteryPercent?.takeIf { it in 0..100 && fresh(car.batteryUpdatedAt) }
        val range = car.rangeKm?.takeIf { it.isFinite() && it >= 0 && fresh(car.rangeUpdatedAt) }
        // Rearm only after a confirmed recovery, with a buffer to avoid threshold chatter.
        if(fuel != null && fuel > 20) notified.remove("fuel")
        if(battery != null && battery > 20) notified.remove("battery")
        if(range != null && range > 50) notified.remove("range")
        val alerts = buildList {
            if(fuel != null && fuel <= 15) add(VehicleEnergyAlert("fuel", if(fuel <= 5) 2 else 1,
                "주유가 필요합니다", "연료 $fuel% · 가까운 주유소를 확인하세요"))
            if(battery != null && battery <= 15) add(VehicleEnergyAlert("battery", if(battery <= 5) 2 else 1,
                "충전이 필요합니다", "배터리 $battery% · 가까운 충전소를 확인하세요"))
            // An energy reminder already covers the same low-range condition.
            if(isEmpty() && range != null && range <= 40) add(VehicleEnergyAlert("range", if(range <= 15) 2 else 1,
                "주행 가능 거리를 확인하세요", "남은 거리 약 ${range.toInt()} km · 주유·충전을 준비하세요"))
        }
        return alerts.sortedByDescending { it.severity }.firstOrNull {
            it.severity > aboveSeverity && it.severity > (notified[it.source] ?: 0)
        }?.also { notified[it.source] = it.severity }
    }
}
