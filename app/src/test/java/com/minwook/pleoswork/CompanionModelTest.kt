package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class CompanionModelTest {
    private fun connected(id: String = "parcel-east") = CompanionState(link = DriverLink(phase = LinkPhase.ACTIVE, activeId = id))
    @Test fun actualModeNeverExposesDemoFacilityOrChangesCargo() {
        val s = connected("freight-cold").copy(demo = false)
        assertNull(s.site)
        assertNull(s.entrance)
        assertNull(s.bay)
        assertEquals(s, CompanionReducer.lock(s, false, true))
        assertEquals(s, CompanionReducer.light(s, true, true))
        assertEquals(s, CompanionReducer.door(s, true, true))
        assertEquals(s, CompanionReducer.cooling(s, false, true))
        assertEquals(s, CompanionReducer.temperature(s, -18, true))
        assertEquals(s, CompanionReducer.entrance(s, 0, true))
        assertEquals(s, CompanionReducer.bay(s, 1, true))
    }
    @Test fun drivingOrUnknownStateCannotChangeControlsOrSiteChoices() {
        val s = connected("freight-cold")
        assertEquals(s, CompanionReducer.lock(s, false, false))
        assertEquals(s, CompanionReducer.light(s, true, false))
        assertEquals(s, CompanionReducer.door(s, true, false))
        assertEquals(s, CompanionReducer.cooling(s, false, false))
        assertEquals(s, CompanionReducer.temperature(s, -18, false))
        assertEquals(s, CompanionReducer.bay(s, 1, false))
    }
    @Test fun unpairedAppCannotActuateDemoControls() {
        val s = CompanionState()
        assertEquals(s, CompanionReducer.lock(s, false, true))
        assertEquals(s, CompanionReducer.light(s, true, true))
        assertEquals(s, CompanionReducer.door(s, true, true))
    }
    @Test fun openDoorCannotBeReportedLocked() {
        val opened = CompanionReducer.door(connected(), true, true)
        assertFalse(opened.cargoLocked)
        assertEquals(opened, CompanionReducer.lock(opened, true, true))
        val closed = CompanionReducer.door(opened, false, true)
        assertFalse(closed.cargoLocked)
        assertTrue(CompanionReducer.lock(closed, true, true).cargoLocked)
    }
    @Test fun disconnectHidesFacilityChoicesAndCannotRepopulateThem() {
        val s = connected().copy(terminalConnected = false)
        assertNull(s.site)
        assertNull(s.bay)
        assertEquals(s, CompanionReducer.entrance(s, 0, true))
        assertEquals(s, CompanionReducer.bay(s, 1, true))
    }
    @Test fun heightRestrictedEntranceCannotReplaceGroundEntrance() {
        for (id in listOf("parcel-east", "freight-cold")) {
            val s = connected(id)
            assertEquals(s, CompanionReducer.entrance(s, 1, true))
            assertEquals(s, CompanionReducer.entrance(s, -1, true))
            assertEquals(s, CompanionReducer.entrance(s, 99, true))
            assertEquals(s.entrance, CompanionReducer.entrance(s, 0, true).entrance)
        }
        val noClearance = connected().copy(vehicleHeightCm = 330)
        assertEquals(noClearance, CompanionReducer.entrance(noClearance, 0, true))
    }
    @Test fun baySelectionOnlyChangesParkingLocation() {
        val s = connected().copy(cargoLocked = false, cargoLight = true)
        val changed = CompanionReducer.bay(s, 1, true)
        assertEquals(s.copy(bayIndex = 1), changed)
        assertEquals("관리동 하역구역", changed.bay?.name)
        assertEquals(changed, CompanionReducer.bay(changed, 99, true))
    }
    @Test fun sameServiceTypeCanHaveDifferentAssignedSites() {
        val east = connected()
        val west = connected("parcel-west")
        assertEquals(east.mode, west.mode)
        assertNotEquals(east.site?.name, west.site?.name)
        assertEquals("서린타워", west.site?.name)
        assertNull(connected("no-dispatch").site)
        assertNull(CompanionState().site)
    }
    @Test fun coolingRequiresEquippedDemoAndEnabledCooling() {
        val parcel = connected()
        assertEquals(parcel, CompanionReducer.temperature(parcel, -18, true))
        val freight = connected("freight-cold")
        assertEquals(-18, CompanionReducer.temperature(freight, -18, true).targetTemperature)
        assertEquals(freight, CompanionReducer.temperature(freight, 100, true))
        val off = CompanionReducer.cooling(freight, false, true)
        assertFalse(off.coolingOn)
        assertEquals(off, CompanionReducer.temperature(off, 8, true))
    }
}
