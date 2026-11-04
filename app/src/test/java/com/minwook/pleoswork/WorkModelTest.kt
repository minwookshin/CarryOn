package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class WorkModelTest {
    @Test fun shipmentTotals() {
        val s = WorkState(phase="field")
        assertEquals(25, s.parcels.count { !it.pickup })
        assertEquals(3, s.parcels.count { it.pickup })
        assertEquals(14, s.stopParcels.size)
    }
    @Test fun noCompletionWithoutProof() {
        val s = WorkState(phase="field")
        assertEquals(s, WorkReducer.record(s, s.parcels[0].id, "done", false, "", true))
        assertEquals(s, WorkReducer.record(s, s.parcels[0].id, "deferred", false, "", true))
    }
    @Test fun noWorkWhenDrivingOrUnknown() {
        assertFalse(VehicleState().canWork)
        assertFalse(VehicleState(true, false, 0f, 8).canWork)
        assertFalse(VehicleState(true, true, 0f, 4).canWork)
        assertFalse(VehicleState(true, false, null, 4).canWork)
        assertTrue(VehicleState(true, false, 0f, 4).canWork)
        val s = WorkState(phase="field")
        assertEquals(s, WorkReducer.record(s, s.parcels[0].id, "done", true, "", false))
    }
    @Test fun deliveryAndPickupIndependent() {
        var s = WorkState(phase="field")
        s = WorkReducer.record(s, s.parcels[0].id, "done", true, "", true)
        assertEquals(1, s.deliveryCount)
        assertEquals(0, s.pickupCount)
        assertFalse(s.stopResolved)
    }
    @Test fun undoAndDeferPreserveMeaning() {
        var s = WorkState(phase="field")
        val id = s.parcels[0].id
        s = WorkReducer.record(s, id, "deferred", false, "수취인 부재", true)
        assertEquals(0, s.deliveryCount)
        assertEquals("수취인 부재", s.parcels[0].reason)
        s = WorkReducer.record(s, id, "pending", false, "", true)
        assertEquals("", s.parcels[0].reason)
    }
    @Test fun bundleCannotContainOtherStops() {
        val s = WorkState(phase="field")
        val next = WorkReducer.saveBundle(s, s.parcels.map { it.id }.toSet(), true, true)
        assertEquals(12, next.bundle.size)
        assertTrue(next.descending)
    }
    @Test fun nextStopRequiresEveryItemResolved() {
        var s = WorkState(phase="field")
        assertEquals(s, WorkReducer.nextStop(s, true))
        for (i in s.stopParcels) s = WorkReducer.record(s, i.id, "deferred", false, "현장 확인 필요", true)
        val next = WorkReducer.nextStop(s.copy(phase="return"), true)
        assertEquals(1, next.stopIndex)
        assertEquals(14, next.deferredCount)
    }
    @Test fun unloadingDoesNotSign() {
        val s = WorkState(scenario = "freight", phase="arrival", dock = "07")
        val unloaded = WorkReducer.freight(s, "unload", true, true)
        assertFalse(unloaded.signed)
        assertEquals(unloaded, WorkReducer.freight(unloaded, "finish", true, true))
        val signed = WorkReducer.freight(unloaded, "sign", true, true)
        assertTrue(WorkReducer.freight(signed, "finish", true, true).ended)
    }
    @Test fun endedIsReadOnly() {
        val s = WorkState(ended = true)
        assertEquals(s, WorkReducer.record(s, s.parcels[0].id, "done", true, "", true))
        assertEquals(s, WorkReducer.saveBundle(s, emptySet(), false, true))
    }
    @Test fun restAccumulatesExplicitly() {
        val s = WorkReducer.toggleRest(WorkState(), 1000L, true)
        assertEquals(1000L, s.restStartedAt)
        assertEquals(5000L, WorkReducer.toggleRest(s, 6000L, true).restMs)
    }
}
