package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class DispatchDataTest {
    private fun connected(id: String = "parcel-east") = CompanionState(link = DriverLink(phase = LinkPhase.ACTIVE, activeId = id))

    @Test fun manifestRequiresCompletedPairingAndConnectedDemoSource() {
        for (phase in LinkPhase.entries.filter { it != LinkPhase.ACTIVE }) {
            assertNull(CompanionState(link = DriverLink(phase = phase, activeId = "parcel-east")).dispatch)
        }
        assertNull(connected().copy(demo = false).dispatch)
        assertNull(connected().copy(terminalConnected = false).dispatch)
        assertNull(connected("no-dispatch").dispatch)
        assertNull(connected().copy(link = DriverLinkReducer.renew(LinkChallenge("new", 999))).dispatch)
    }
    @Test fun eachDriversManifestMatchesTheirAssignedSiteAndParcelStorageCounts() {
        for (driver in demoDrivers) {
            val manifest = driver.dispatch ?: continue
            assertEquals(driver.assignment!!.name, manifest.current.name)
            manifest.stops.mapNotNull { it.parcel }.forEach { parcel ->
                assertEquals(parcel.deliveries, parcel.zones.sumOf { it.units })
            }
        }
        assertNotEquals(connected().dispatch?.id, connected("parcel-west").dispatch?.id)
        assertEquals(12, connected().dispatch!!.current.parcel!!.deliveries)
        assertEquals(9, connected("parcel-west").dispatch!!.current.parcel!!.deliveries)
    }
    @Test fun previewingAnotherStopCannotChangeTheCurrentDestinationOrCargo() {
        val state = connected()
        val manifest = state.dispatch!!
        val preview = manifest.preview("east-shops", true)
        assertEquals("한빛상가", preview.name)
        assertEquals(5, preview.parcel!!.deliveries)
        assertEquals("한빛아파트 101동", state.site!!.name)
        assertEquals("east-hanbit", manifest.current.id)
        assertEquals(12, state.dispatch!!.current.parcel!!.deliveries)
    }
    @Test fun foreignStopIdsAndDrivingReturnToCurrentVisit() {
        val manifest = connected().dispatch!!
        assertEquals(manifest.current, manifest.preview("west-tower", true))
        assertEquals(manifest.current, manifest.preview("east-shops", false))
        assertEquals(manifest.current, manifest.preview(null, true))
    }
    @Test fun arrivalComparisonsRespectWindowBoundariesAndMidnight() {
        val window = VisitWindow(1435, 1450)
        assertEquals(ArrivalComparison(ArrivalKind.EARLY, 5), compareArrival(1430, window))
        assertEquals(ArrivalKind.WITHIN_WINDOW, compareArrival(1435, window).kind)
        assertEquals(ArrivalKind.WITHIN_WINDOW, compareArrival(1450, window).kind)
        assertEquals(ArrivalComparison(ArrivalKind.LATE, 1), compareArrival(1451, window))
        assertEquals("1일 뒤 00:10", dispatchTime(1450))
    }
    @Test fun delayChangesArrivalWithoutChangingReservationsOrAmounts() {
        val initial = connected()
        assertEquals(628, initial.arrivalMinute())
        val delayed = CompanionReducer.traffic(initial, true, true)
        assertEquals(653, delayed.arrivalMinute())
        assertEquals(ArrivalComparison(ArrivalKind.LATE, 3), compareArrival(delayed.arrivalMinute()!!, delayed.dispatch!!.current.window))
        assertEquals(initial.dispatch, delayed.dispatch)
        assertEquals(initial.site, delayed.site)
        assertEquals(initial, CompanionReducer.traffic(initial, true, false))
        val api = initial.copy(demo = false)
        assertEquals(api, CompanionReducer.traffic(api, true, true))
        assertNull(api.arrivalMinute())
    }
    @Test fun freightRetainsQuantityWeightTemperatureAndCurrentStage() {
        val freight = connected("freight-cold").dispatch!!
        assertEquals(1, freight.currentIndex)
        assertEquals("하차", freight.current.operation)
        assertEquals(24, freight.current.freight!!.units)
        assertEquals(480, freight.current.freight!!.weightKg)
        assertEquals("03번 도크", connected("freight-cold").bay!!.name)
        assertEquals(2, freight.remaining.size)
        assertEquals("빈 용기 회수", freight.preview("cold-next", true).operation)
    }
    @Test fun temperatureMeasurementAndRequirementAreIndependentFromControlSetpoint() {
        val initial = connected("freight-cold")
        val changed = CompanionReducer.temperature(initial, 8, true)
        assertEquals(initial.airTemperature, changed.airTemperature)
        val requirement = initial.dispatch!!.current.freight!!.temperature!!
        assertTrue(requirement.contains(initial.airTemperature))
        assertFalse(requirement.contains(changed.targetTemperature.toFloat()))
        assertFalse(requirement.contains(null))
        assertFalse(requirement.contains(Float.NaN))
        assertNull(initial.copy(demo = false).airTemperature)
    }
}
