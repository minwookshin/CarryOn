package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class VisitPresentationTest {
    private val now = 1_800_000_000_000L
    private val parked = VehicleState(automotive = true, uxRestricted = false, speedKph = 0f, gear = 4, parkingBrake = false)
    private fun state(id: String = "parcel-east", stage: RunStage = RunStage.TRAVEL): CompanionState {
        val driver = demoDrivers.first { it.id == id }
        return CompanionState(link = DriverLink(phase = LinkPhase.ACTIVE, activeId = id), targetTemperature = -18,
            run = DemoWorkflow.create(driver, now)!!.copy(stage = stage))
    }

    @Test fun selectingCurrentVisitResumesVisibleTimeButOtherPreviewsDoNotAdvance() {
        var s = GuidanceFlow.start(state(), true, now)
        val currentId = s.run!!.stop.id
        val futureId = s.run!!.plan.stops[1].id
        assertNull(s.run!!.previewVisit(currentId))
        assertFalse(s.run!!.liveVisitVisible("itinerary", null))
        assertFalse(s.run!!.liveVisitVisible("current", futureId))
        repeat(3) {
            val elapsed = if (s.run!!.liveVisitVisible("current", currentId)) 1000L else 0L
            s = GuidanceFlow.tick(s, parked, now + (it + 1) * 1000, elapsed)
        }
        assertEquals(3000L, s.run!!.guidance!!.elapsedMs)
        val returning = s.run!!.copy(stage = RunStage.RETURN_TRAVEL, index = s.run!!.plan.stops.lastIndex)
        assertEquals(returning.stop.id, returning.previewVisit(returning.stop.id))
    }

    @Test fun persistentMapContinuesWhileTheRightPanelChangesWithoutChangingItsDestination() {
        var s = GuidanceFlow.start(state(), true, now)
        val active = s.run!!.navigationVisitId
        val preview = s.run!!.plan.stops[2].id
        val panels = listOf("itinerary", "current", "site", "settings", "navigation", "api", "link", "handheld")
        panels.forEachIndexed { i, panel ->
            val elapsed = if (s.run!!.liveVisitVisible(panel, preview, sideNavigation = true)) 1000L else 0L
            s = GuidanceFlow.tick(s, parked, now + (i + 1) * 1000, elapsed)
            assertEquals(active, s.run!!.navigationVisitId)
        }
        assertEquals(8000L, s.run!!.guidance!!.elapsedMs)
        s = GuidanceFlow.pause(s, true)
        s = GuidanceFlow.tick(s, parked, now + 9000, 1000)
        assertEquals(8000L, s.run!!.guidance!!.elapsedMs)
        assertTrue(s.run!!.guidance!!.paused)
    }

    @Test fun siteHintAppearsOnApproachAndAtStopButNotDuringResultsOrPhoneWork() {
        val travel = GuidanceFlow.start(state(), true, now)
        assertNull(travel.siteHint(parked, now))
        var approaching = travel
        repeat(10) { approaching = GuidanceFlow.tick(approaching, parked, now + (it + 1) * 1000, 1000) }
        val hint = approaching.siteHint(parked, now + 10_000)!!
        assertTrue(hint.text.contains("남문"))
        assertTrue(hint.text.contains("101동 동측"))
        assertNotNull(state(stage = RunStage.WORKING).siteHint(parked, now))
        val outside = state(stage = RunStage.WORKING).let { it.copy(run = it.run!!.copy(parcelAway = true)) }
        assertNull(outside.siteHint(parked, now))
        assertNull(state(stage = RunStage.REVIEW).siteHint(parked, now))
        assertNull(state(stage = RunStage.ENDED).siteHint(parked, now))
        val waiting = state("freight-cold", RunStage.WAITING)
        assertEquals(waiting.site!!.waitingPlace, waiting.siteHint(parked, now)!!.text)
    }

    @Test fun parkingReminderBelongsToWorkAtTheFacilityNotEveryTravelScreen() {
        assertFalse(state().showParkingCheck(parked))
        assertFalse(state(stage = RunStage.RETURN_TRAVEL).showParkingCheck(parked))
        val working = state(stage = RunStage.WORKING)
        assertTrue(working.showParkingCheck(parked))
        assertTrue(working.showParkingCheck(parked.copy(parkingBrake = null)))
        assertFalse(working.showParkingCheck(parked.copy(parkingBrake = true)))
        assertFalse(working.showParkingCheck(parked.copy(gear = 8)))
        assertFalse(working.showParkingCheck(VehicleState()))
    }

    @Test fun freightWaitsForTheAssignedDockInsteadOfShowingAnUnassignedBay() {
        val waiting = state("freight-cold", RunStage.WAITING)
        assertEquals(waiting.site!!.waitingPlace, waiting.stoppingPlace())
        val dock = DemoWorkflow.terminal(waiting, waiting.run!!.event(TerminalKind.DOCK), true, now)
        assertEquals("02번 도크", dock.stoppingPlace())
        val returning = dock.copy(run = dock.run!!.copy(stage = RunStage.RETURN_TRAVEL))
        assertEquals("반납 구역 R", returning.stoppingPlace())
    }

    @Test fun coldExceptionsStayVisibleEvenWhenNormalDetailsAreCollapsed() {
        val normal = state("freight-cold")
        assertFalse(normal.needsColdAttention())
        assertTrue(normal.copy(coolingOn = false).needsColdAttention())
        assertTrue(normal.copy(targetTemperature = 4).needsColdAttention())
        assertTrue(DemoWorkflow.sensor(normal, SensorExample.DEVIATION, true, now).needsColdAttention())
        assertTrue(DemoWorkflow.sensor(normal, SensorExample.OFFLINE, true, now).needsColdAttention())
        assertFalse(state().copy(coolingOn = false).needsColdAttention())
        val emptyContainers = normal.copy(coolingOn = false, run = normal.run!!.copy(index = 2, sensor = SensorExample.OFFLINE))
        assertFalse(emptyContainers.needsColdAttention())
        assertFalse(normal.copy(coolingOn = false, run = normal.run!!.copy(stage = RunStage.RETURN_TRAVEL)).needsColdAttention())
    }

    @Test fun pausedOrFinishedRunsDoNotKeepApproachAndParkingPrompts() {
        val travel = state().run!!
        assertTrue(travel.showNavigation)
        assertFalse(travel.copy(resting = true).showNavigation)
        assertFalse(travel.copy(dayEnded = true).showNavigation)
        assertFalse(travel.copy(stage = RunStage.RETURNED).showNavigation)
        assertFalse(travel.copy(stage = RunStage.ENDED).atFacility)
    }

    @Test fun returningResetsFacilityChoicesFromTheLastDelivery() {
        val original = state()
        val r = original.run!!.copy(index = original.run!!.plan.stops.lastIndex, stage = RunStage.REVIEW)
        val ready = r.copy(results = mapOf(r.stop.id to StopResult(deliveries = r.stop.parcel!!.deliveries, pickups = r.stop.parcel!!.pickups)))
        val returned = DemoWorkflow.advance(original.copy(run = ready, entranceIndex = 1, bayIndex = 1), true)
        assertEquals(RunStage.RETURN_TRAVEL, returned.run!!.stage)
        assertEquals("센터 정문", returned.entrance!!.name)
        assertEquals("반납 구역 R", returned.stoppingPlace())
    }

    @Test fun mapPresentationWaitsForSafeReadyTravelAndStopsForWork() {
        val connected = parked.copy(navigationStatus = "연결됨")
        val s = state()
        assertNotNull(s.navigationPresentationKey(connected))
        assertNull(s.navigationPresentationKey(parked))
        assertNull(s.navigationPresentationKey(connected.copy(gear = 8)))
        assertNull(s.copy(cargoOpen = true).navigationPresentationKey(connected))
        assertNull(s.copy(run = s.run!!.copy(resting = true)).navigationPresentationKey(connected))
        assertNull(s.copy(run = s.run!!.copy(dayEnded = true)).navigationPresentationKey(connected))
        assertNull(s.copy(run = s.run!!.copy(pending = listOf(s.run!!.event(TerminalKind.RESULT)))).navigationPresentationKey(connected))
        listOf(RunStage.WORKING, RunStage.REVIEW, RunStage.RETURNED, RunStage.ENDED).forEach {
            assertNull(s.copy(run = s.run!!.copy(stage = it)).navigationPresentationKey(connected))
        }
    }

    @Test fun mapKeyIsStableAcrossPreparationButChangesForNextVisitAndReturn() {
        val connected = parked.copy(navigationStatus = "연결됨")
        val s = state()
        val first = s.navigationPresentationKey(connected)
        assertEquals(first, s.copy(run = s.run!!.copy(stage = RunStage.PREPARING)).navigationPresentationKey(connected))
        assertNotEquals(first, s.copy(run = s.run!!.copy(index = 1)).navigationPresentationKey(connected))
        assertNotEquals(first, s.copy(run = s.run!!.copy(stage = RunStage.RETURN_TRAVEL)).navigationPresentationKey(connected))
    }

    @Test fun readOnlyNoticesExcludeNormalDrivingAndRetainActionableExceptions() {
        assertTrue(state().vehicleNotices(parked).isEmpty())
        assertEquals(listOf("parking-brake"), state(stage = RunStage.WORKING).vehicleNotices(parked).map { it.id })
        assertTrue(state(stage = RunStage.WORKING).vehicleNotices(parked.copy(parkingBrake = true)).isEmpty())
        val cold = state("freight-cold", RunStage.REVIEW).copy(cargoOpen = true, coolingOn = false)
        assertEquals(listOf("parking-brake", "cargo-door", "cold-chain"), cold.vehicleNotices(parked).map { it.id })
        val recovered = CompanionReducer.door(cold, false, true)
        assertFalse(recovered.vehicleNotices(parked).any { it.id == "cargo-door" })
        assertEquals(cold, CompanionReducer.door(cold, false, false))
    }
}
