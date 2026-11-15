package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class VehicleEnergyAlertsTest {
    private val now = 1_000_000L
    private fun fuel(value: Int, at: Long = now) = VehicleState(fuelPercent = value, fuelUpdatedAt = at)

    @Test fun missingInvalidAndStaleValuesNeverBecomeLowEnergyWarnings() {
        val alerts = VehicleEnergyAlerts()
        listOf(VehicleState(), fuel(-1), fuel(101), fuel(3, 0), fuel(3, now - 90_001), fuel(3, now + 1),
            VehicleState(rangeKm = Double.NaN, rangeUpdatedAt = now)).forEach { assertNull(alerts.takeNext(it, now)) }
    }
    @Test fun repeatedTelemetryDoesNotRepeatDismissedWarningsButCriticalEscalationDoes() {
        val alerts = VehicleEnergyAlerts()
        assertEquals(1, alerts.takeNext(fuel(15), now)!!.severity)
        assertNull(alerts.takeNext(fuel(14), now))
        assertNull(alerts.takeNext(fuel(6), now))
        assertEquals(2, alerts.takeNext(fuel(5), now, aboveSeverity = 1)!!.severity)
        assertNull(alerts.takeNext(fuel(4), now))
    }
    @Test fun staleGapsAndSmallFluctuationsDoNotRearmAnAlert() {
        val alerts = VehicleEnergyAlerts()
        assertNotNull(alerts.takeNext(fuel(15), now))
        assertNull(alerts.takeNext(VehicleState(), now))
        assertNull(alerts.takeNext(fuel(16), now))
        assertNull(alerts.takeNext(fuel(15), now))
        assertNull(alerts.takeNext(fuel(21), now))
        assertNotNull(alerts.takeNext(fuel(15), now))
    }
    @Test fun occupiedToastDoesNotConsumeQueuedWarnings() {
        val alerts = VehicleEnergyAlerts()
        val car = fuel(12).copy(batteryPercent = 10, batteryUpdatedAt = now)
        assertEquals("fuel", alerts.takeNext(car, now)!!.source)
        assertNull(alerts.takeNext(car, now, aboveSeverity = 1))
        assertEquals("battery", alerts.takeNext(car, now)!!.source)
        assertNull(alerts.takeNext(car, now))
    }
    @Test fun criticalEnergyTakesPriorityAndLowRangeDoesNotDuplicateIt() {
        val alerts = VehicleEnergyAlerts()
        val car = fuel(12).copy(batteryPercent = 4, batteryUpdatedAt = now, rangeKm = 10.0, rangeUpdatedAt = now)
        assertEquals("battery", alerts.takeNext(car, now)!!.source)
        assertEquals("fuel", alerts.takeNext(car, now)!!.source)
        assertNull(alerts.takeNext(car, now))
    }
    @Test fun rangeCanWarnWhenNoPercentageIsAvailableAndRearmsOnlyAfterRecovery() {
        val alerts = VehicleEnergyAlerts()
        fun range(km: Double) = VehicleState(rangeKm = km, rangeUpdatedAt = now)
        assertEquals("range", alerts.takeNext(range(40.0), now)!!.source)
        assertNull(alerts.takeNext(range(41.0), now))
        assertNull(alerts.takeNext(range(39.0), now))
        assertEquals(2, alerts.takeNext(range(15.0), now)!!.severity)
        assertNull(alerts.takeNext(range(51.0), now))
        assertNotNull(alerts.takeNext(range(40.0), now))
    }
}
