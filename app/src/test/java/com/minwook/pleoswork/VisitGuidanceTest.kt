package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class VisitGuidanceTest {
    private val now = 1_800_000_000_000L
    private val car = VehicleState(automotive = true, uxRestricted = false, speedKph = 0f, gear = 4)
    private fun state(id: String = "parcel-east"): CompanionState {
        val driver = demoDrivers.first { it.id == id }
        return CompanionState(link = DriverLink(phase = LinkPhase.ACTIVE, activeId = id), run = DemoWorkflow.create(driver, now))
    }
    private fun arrive(s: CompanionState): CompanionState {
        var current = s
        repeat(18) { current = GuidanceFlow.tick(current, car, now + (it+1)*1000, 1000) }
        return current
    }
    private fun native(): CompanionState {
        val s = GuidanceFlow.start(state().copy(guidanceMode = GuidanceMode.NATIVE_SAMPLE), true, now)
        return GuidanceFlow.issued(s, s.run!!.guidance!!.id, true, now + 1)
    }

    @Test fun autoArrivesWithoutArrivalButtonButDoesNotCompleteDelivery() {
        val s = GuidanceFlow.start(state(), true, now)
        val arrived = arrive(s)
        assertEquals(RunStage.WORKING, arrived.run!!.stage)
        assertEquals(GuidanceStatus.ARRIVED, arrived.run!!.guidance!!.status)
        assertEquals(0, arrived.run!!.result.deliveries)
        assertFalse(arrived.run!!.ready)
        assertEquals(arrived, GuidanceFlow.tick(arrived, car, now+40_000, 1000))
    }

    @Test fun zeroElapsedAndPauseCannotAdvanceDemo() {
        val s = GuidanceFlow.start(state(), true, now)
        assertEquals(s, GuidanceFlow.tick(s, car, now + 100_000, 0))
        val paused = GuidanceFlow.pause(s, true)
        assertEquals(paused, GuidanceFlow.tick(paused, car, now+2000, 1000))
        assertEquals(1000L, GuidanceFlow.tick(GuidanceFlow.pause(paused, true), car, now+3000, 1000).run!!.guidance!!.elapsedMs)
    }

    @Test fun unknownVehicleDriveDoorAndPendingDataCannotAdvanceDemo() {
        val s = GuidanceFlow.start(state(), true, now)
        assertEquals(s, GuidanceFlow.tick(s, VehicleState(), now+1000, 1000))
        assertEquals(s, GuidanceFlow.tick(s, car.copy(gear = 8), now+1000, 1000))
        assertEquals(0L, GuidanceFlow.tick(s.copy(cargoOpen=true), car, now+1000, 1000).run!!.guidance!!.elapsedMs)
        val unstarted = state()
        assertEquals(unstarted, GuidanceFlow.start(unstarted, false, now))
        assertNull(GuidanceFlow.start(state().copy(cargoOpen=true), true, now).run!!.guidance)
        val queued = DemoWorkflow.terminal(s.copy(terminalConnected=false), s.run!!.event(TerminalKind.RESULT), true, now+1000)
        assertEquals(queued, GuidanceFlow.tick(queued, car, now+2000, 1000))
    }

    @Test fun parcelWholeDayStartsNextDestinationAndReturnsToCenter() {
        var s = GuidanceFlow.start(state(), true, now)
        repeat(3) { index ->
            val priorId = s.run!!.guidance!!.id
            s = arrive(s)
            assertEquals(index, s.run!!.index)
            s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.RESULT), true, now+20_000)
            s = GuidanceFlow.next(s, true, now+21_000+index)
            assertNotEquals(priorId, s.run!!.guidance!!.id)
            assertEquals(0L, s.run!!.guidance!!.elapsedMs)
        }
        assertEquals(RunStage.RETURN_TRAVEL, s.run!!.stage)
        assertTrue(s.run!!.guidance!!.visitId.startsWith("return:"))
        s = arrive(s)
        assertEquals(RunStage.RETURNED, s.run!!.stage)
        s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.HANDOFF), true, now+50_000)
        s = GuidanceFlow.next(s, true, now+51_000)
        assertEquals(RunStage.ENDED, s.run!!.stage)
    }

    @Test fun freightArrivalKeepsDockAndReceiptSteps() {
        var s = arrive(GuidanceFlow.start(state("freight-cold"), true, now))
        assertEquals(RunStage.WAITING, s.run!!.stage)
        assertFalse(s.run!!.ready)
        assertEquals(s, GuidanceFlow.next(s, true, now+20_000))
        s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.DOCK), true, now+21_000)
        s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.RESULT), true, now+22_000)
        s = GuidanceFlow.next(s, true, now+23_000)
        assertEquals(RunStage.DEPARTURE, s.run!!.stage)
        s = GuidanceFlow.next(s, true, now+24_000)
        assertEquals(1, s.run!!.index)
        assertEquals(GuidanceStatus.ACTIVE, s.run!!.guidance!!.status)
        s = arrive(s)
        s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.DOCK), true, now+45_000)
        s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.RESULT), true, now+46_000)
        assertFalse(s.run!!.ready)
        assertEquals(s, GuidanceFlow.next(s, true, now+47_000))
        s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.RECEIPT), true, now+48_000)
        assertEquals(2, GuidanceFlow.next(s, true, now+49_000).run!!.index)
    }

    @Test fun nativeDispatchIsNotRouteSuccessAndTimeoutCanRetry() {
        var s = native()
        assertEquals(GuidanceStatus.REQUESTED, s.run!!.guidance!!.status)
        s = GuidanceFlow.tick(s, car, now+31_000, 1000)
        assertEquals(GuidanceStatus.FAILED, s.run!!.guidance!!.status)
        val retry = GuidanceFlow.start(s, true, now+32_000)
        assertEquals(GuidanceStatus.REQUESTED, retry.run!!.guidance!!.status)
        assertNotEquals(s.run!!.guidance!!.id, retry.run!!.guidance!!.id)
        assertEquals(0L, retry.run!!.guidance!!.issuedAt)
    }

    @Test fun freightWholeDayReturnsOnlyAfterQuantityAndReceipt() {
        var s = GuidanceFlow.start(state("freight-cold"), true, now)
        repeat(3) { index ->
            s = arrive(s)
            s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.DOCK), true, now+20_000)
            s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.RESULT), true, now+21_000)
            if (s.run!!.stop.operation == "하차") s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.RECEIPT), true, now+22_000)
            s = GuidanceFlow.next(s, true, now+23_000+index)
            if (s.run!!.stage == RunStage.DEPARTURE) s = GuidanceFlow.next(s, true, now+24_000+index)
        }
        assertEquals(RunStage.RETURN_TRAVEL, s.run!!.stage)
        s = arrive(s)
        assertEquals(RunStage.RETURNED, s.run!!.stage)
        assertFalse(s.run!!.handedOver)
        s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.HANDOFF), true, now+60_000)
        assertEquals(RunStage.ENDED, GuidanceFlow.next(s, true, now+61_000).run!!.stage)
    }

    @Test fun matchingFreshDestinationAcknowledgesNativeRequest() {
        val s = native()
        val target = s.run!!.guidance!!.target!!
        val reading = NavReading(target, 600, 2000, now+2000)
        assertEquals(GuidanceStatus.REQUESTED, GuidanceFlow.tick(s, car.copy(navigation=reading.copy(receivedAt=now-1)), now+2000, 0).run!!.guidance!!.status)
        val active = GuidanceFlow.tick(s, car.copy(navigation=reading), now+2000, 0)
        assertEquals(GuidanceStatus.ACTIVE, active.run!!.guidance!!.status)
        assertEquals(target, active.run!!.activeNavigationBinding!!.target)
    }

    @Test fun replacingOldRouteMayCancelBeforeNewDestinationArrives() {
        val s = native()
        val target = s.run!!.guidance!!.target!!
        val cancelled = car.copy(routeStatus="cancelled", routeEventAt=now+1000)
        assertEquals(GuidanceStatus.REQUESTED, GuidanceFlow.tick(s, cancelled, now+1000, 0).run!!.guidance!!.status)
        val confirmed = GuidanceFlow.tick(s, cancelled.copy(navigation=NavReading(target, 60, 50, now+2000)), now+2000, 0)
        assertEquals(GuidanceStatus.ACTIVE, confirmed.run!!.guidance!!.status)
        assertEquals(confirmed, GuidanceFlow.tick(confirmed, cancelled, now+2500, 0))
        val userCancelled = GuidanceFlow.tick(confirmed, cancelled.copy(routeEventAt=now+3000), now+3000, 0)
        assertEquals(GuidanceStatus.FAILED, userCancelled.run!!.guidance!!.status)
    }

    @Test fun restoredNativeLegWithoutDestinationStopsWaitingAndCanRetry() {
        val requested = native()
        val target = requested.run!!.guidance!!.target!!
        val active = GuidanceFlow.tick(requested, car.copy(navigation=NavReading(target,60,50,now+2000)), now+2000, 0)
        // The navigation process lost its route during an emulator restart.
        val expired = GuidanceFlow.tick(active, car.copy(navigationStatus="연결됨", navigationObservedAt=now+93_000), now+93_000, 0)
        assertEquals(GuidanceStatus.FAILED, expired.run!!.guidance!!.status)
        assertEquals(RunStage.TRAVEL, expired.run!!.stage)
        assertEquals(0L, expired.run!!.guidance!!.arrivedAt)
        val retry = GuidanceFlow.start(expired, true, now+94_000)
        assertEquals(GuidanceStatus.REQUESTED, retry.run!!.guidance!!.status)
        assertEquals(0L, retry.run!!.guidance!!.issuedAt)
    }

    @Test fun restoredNativeLegWaitsForFirstSdkSnapshotBeforeDecidingRouteIsGone() {
        val requested = native()
        val target = requested.run!!.guidance!!.target!!
        val active = GuidanceFlow.tick(requested, car.copy(navigation=NavReading(target,60,50,now+2000)), now+2000, 0)
        val reconnecting = GuidanceFlow.tick(active, car.copy(navigationStatus="연결됨"), now+180_000, 0)
        assertEquals(GuidanceStatus.ACTIVE, reconnecting.run!!.guidance!!.status)
        val restored = GuidanceFlow.tick(reconnecting, car.copy(navigationObservedAt=now+181_000,
            navigation=NavReading(target,60,50,now+181_000)), now+181_000, 0)
        assertEquals(GuidanceStatus.ACTIVE, restored.run!!.guidance!!.status)
        assertEquals(0L, restored.run!!.guidance!!.arrivedAt)
    }

    @Test fun freshNativeReadingsKeepGuidanceActiveWithoutInventingMovementOrArrival() {
        val requested = native()
        val target = requested.run!!.guidance!!.target!!
        val active = GuidanceFlow.tick(requested, car.copy(navigation=NavReading(target,60,50,now+2000)), now+2000, 0)
        val unchanged = GuidanceFlow.tick(active, car.copy(navigation=NavReading(target,60,50,now+180_000)), now+180_000, 1000)
        assertEquals(GuidanceStatus.ACTIVE, unchanged.run!!.guidance!!.status)
        assertEquals(RunStage.TRAVEL, unchanged.run!!.stage)
        assertEquals(0L, unchanged.run!!.guidance!!.arrivedAt)
        assertEquals(0L, unchanged.run!!.guidance!!.elapsedMs)
    }

    @Test fun actualArrivalWaitsForParkAndNeverCompletesPackage() {
        var s = native()
        val target = s.run!!.guidance!!.target!!
        val reading = NavReading(target, 0, 0, now+2000)
        s = GuidanceFlow.tick(s, car.copy(navigation=reading, gear=8, navigationArrival=NavArrival(target, now+2000)), now+2000, 0)
        assertEquals(GuidanceStatus.ARRIVED, s.run!!.guidance!!.status)
        assertEquals(RunStage.TRAVEL, s.run!!.stage)
        s = GuidanceFlow.tick(s, car, now+100_000, 0)
        assertEquals(RunStage.WORKING, s.run!!.stage)
        assertEquals(0, s.run!!.result.deliveries)
    }

    @Test fun canonicalCoordinatesFromRequestedPoiBindAndArrive() {
        val s = native()
        // Observed in Pleos 2.1.0: the official 42dot POI resolves to another coordinate.
        val canonical = s.run!!.guidance!!.target!!.copy(latitude=37.41289678, longitude=127.09383464, address="내비에서 받은 주소")
        var active = GuidanceFlow.tick(s, car.copy(navigation=NavReading(canonical,60,50,now+2000)), now+2000, 0)
        assertEquals(GuidanceStatus.ACTIVE, active.run!!.guidance!!.status)
        assertEquals(canonical, active.run!!.activeNavigationBinding!!.target)
        active = GuidanceFlow.tick(active, car.copy(navigationArrival=NavArrival(canonical,now+3000)), now+3000, 0)
        assertEquals(RunStage.WORKING, active.run!!.stage)
    }

    @Test fun sameNameWithoutMatchingPoiCannotReplaceCoordinates() {
        val target = nativeSampleTargets.first()
        assertFalse(requestedDestinationMatches(target, target.copy(latitude=38.0,poiId="another-poi")))
        assertFalse(requestedDestinationMatches(target.copy(poiId=""),target.copy(latitude=38.0,poiId="")))
        assertFalse(requestedDestinationMatches(target, target.copy(latitude=38.0,poiSubId="other-entrance")))
        assertFalse(requestedDestinationMatches(target, target.copy(latitude=38.0,name="다른 지점")))
    }

    @Test fun zeroDistanceAloneAndOldOrWrongArrivalDoNotArrive() {
        var s = native()
        val target = s.run!!.guidance!!.target!!
        val reading = NavReading(target, 0, 0, now+2000)
        s = GuidanceFlow.tick(s, car.copy(navigation=reading), now+2000, 0)
        for (arrival in listOf(NavArrival(target, now-1), NavArrival(nativeSampleTargets.last(), now+2000))) {
            assertEquals(RunStage.TRAVEL, GuidanceFlow.tick(s, car.copy(navigation=reading, navigationArrival=arrival), now+2000, 0).run!!.stage)
        }
    }

    @Test fun externalRouteChangeStopsAutomaticGuidance() {
        var s = native()
        val target = s.run!!.guidance!!.target!!
        s = GuidanceFlow.tick(s, car.copy(navigation=NavReading(target, 50, 100, now+2000)), now+2000, 0)
        val stopped = GuidanceFlow.tick(s, car.copy(navigation=NavReading(nativeSampleTargets.last(), 50, 100, now+3000)), now+3000, 0)
        assertEquals(GuidanceStatus.FAILED, stopped.run!!.guidance!!.status)
        assertEquals(RunStage.TRAVEL, stopped.run!!.stage)
    }

    @Test fun duplicateIssueAndWrongVisitAreIgnored() {
        val s = native()
        assertEquals(s, GuidanceFlow.issued(s, s.run!!.guidance!!.id, true, now+100))
        assertEquals(s, GuidanceFlow.issued(s, "other-visit", false, now+100))
        val changed = s.copy(run=s.run!!.copy(index=1))
        assertNull(changed.run!!.guidance)
        assertEquals(changed, GuidanceFlow.tick(changed, car, now+2000, 1000))
    }

    @Test fun waitingForTwoAppLayoutDoesNotTimeOutBeforeIssuing() {
        val s = GuidanceFlow.start(state().copy(guidanceMode=GuidanceMode.NATIVE_SAMPLE), true, now)
        assertEquals(s, GuidanceFlow.tick(s, car.copy(navigationStatus="연결됨"), now+60_000, 0))
        val issued = GuidanceFlow.issued(s, s.run!!.guidance!!.id, true, now+61_000)
        assertEquals(GuidanceStatus.REQUESTED, GuidanceFlow.tick(issued, car, now+62_000, 0).run!!.guidance!!.status)
        assertEquals(GuidanceStatus.FAILED, GuidanceFlow.tick(issued, car, now+92_000, 0).run!!.guidance!!.status)
    }

    @Test fun restoringPreservesProgressWithoutCountingBackgroundTime() {
        var s = GuidanceFlow.start(state(), true, now)
        s = GuidanceFlow.tick(s, car, now+1000, 1000)
        val restored = s.copy(run = DemoRunCodec.decode(DemoRunCodec.encode(s.run!!), now+60_000))
        assertEquals(s.run!!.guidance, restored.run!!.guidance)
        assertEquals(1000L, GuidanceFlow.tick(restored, car, now+60_000, 0).run!!.guidance!!.elapsedMs)
        val legacy = DemoRunCodec.decode(DemoRunCodec.encode(state().run!!), now+1000)
        assertNotNull(legacy)
        assertNull(legacy!!.guidance)
    }

    @Test fun switchingToDemoClearsNativeBindingAndDoesNotClaimNativeArrival() {
        var s = native()
        val target = s.run!!.guidance!!.target!!
        s = GuidanceFlow.tick(s, car.copy(navigation=NavReading(target, 50, 100, now+2000)), now+2000, 0)
        s = GuidanceFlow.mode(s, GuidanceMode.DEMO, true)
        assertNull(s.run!!.guidance)
        assertNull(s.run!!.navigationBinding)
        s = arrive(GuidanceFlow.start(s, true, now+3000))
        assertEquals(GuidanceMode.DEMO, s.run!!.guidance!!.mode)
    }
}
