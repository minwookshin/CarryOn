package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class InteractiveJourneyTest {
    private val now = 1_800_000_000_000L
    private val parked = VehicleState(automotive = true, uxRestricted = false, speedKph = 0f, gear = 4)
    private fun state(id: String = "parcel-east"): CompanionState {
        val driver = demoDrivers.first { it.id == id }
        return CompanionState(link = DriverLink(phase = LinkPhase.ACTIVE, activeId = id),
            guidanceMode = GuidanceMode.DEMO, run = DemoWorkflow.create(driver, now))
    }
    private fun arrive(s: CompanionState): CompanionState {
        var next = s
        repeat(18) { next = GuidanceFlow.tick(next, parked, now + (it + 1) * 1000, 1000) }
        return next
    }
    private fun click(s: CompanionState, time: Long = now + 30_000): CompanionState =
        InteractiveJourney.perform(s, InteractiveJourney.action(s)!!, true, time)

    private fun receiveParcel(s: CompanionState, partial: Boolean = false): CompanionState {
        val away = click(s)
        return DemoWorkflow.terminal(away, away.run!!.event(TerminalKind.RESULT, partial), true, now + 31_000)
    }

    private fun finishParcel(s: CompanionState): CompanionState = click(click(receiveParcel(s)))

    @Test fun parcelRequiresTerminalResultReboardingAndReviewBeforeNextGuidance() {
        val arrived = arrive(GuidanceFlow.start(state(), true, now))
        assertEquals(RunStage.WORKING, arrived.run!!.stage)
        assertEquals("목적지에 도착했습니다", journeyNotice(arrived)!!.title)
        assertEquals(arrived, GuidanceFlow.tick(arrived, parked, now + 600_000, 1500))
        assertFalse(arrived.run!!.ready)
        val received = receiveParcel(arrived, partial = true)
        assertEquals(0, received.run!!.index)
        assertTrue(received.run!!.parcelAway)
        assertEquals(received, GuidanceFlow.next(received, true, now + 32_000))
        assertEquals(JourneyActionKind.REBOARD, InteractiveJourney.action(received)!!.kind)
        val reviewed = click(received)
        assertFalse(reviewed.run!!.parcelAway)
        assertEquals(1, reviewed.run!!.missingDeliveries)
        assertEquals(1, reviewed.run!!.missingPickups)
        assertEquals(JourneyActionKind.REVIEW_RESULTS, InteractiveJourney.action(reviewed)!!.kind)
        assertEquals(reviewed, click(reviewed)) // Opens the terminal; never fabricates completion.
        val updated = DemoWorkflow.terminal(reviewed, reviewed.run!!.event(TerminalKind.RESULT), true, now + 33_000)
        assertEquals(0, updated.run!!.index)
        assertEquals(RunStage.REVIEW, updated.run!!.stage)
        val next = click(updated)
        assertEquals(1, next.run!!.index)
        assertEquals(12, next.run!!.results[arrived.run!!.stop.id]!!.deliveries)
        assertEquals(2, next.run!!.results[arrived.run!!.stop.id]!!.pickups)
        assertEquals(GuidanceStatus.ACTIVE, next.run!!.guidance!!.status)
        assertEquals(0L, next.run!!.guidance!!.elapsedMs)
        assertTrue(journeyNotice(next)!!.title.contains("다음 방문지"))
    }

    @Test fun parcelFullRouteEndsOnlyAfterUserHandoff() {
        var s = GuidanceFlow.start(state(), true, now)
        repeat(3) {
            s = arrive(s)
            assertFalse(s.run!!.ready)
            s = finishParcel(s)
        }
        assertEquals(RunStage.RETURN_TRAVEL, s.run!!.stage)
        s = arrive(s)
        assertEquals(RunStage.RETURNED, s.run!!.stage)
        assertFalse(s.run!!.handedOver)
        assertEquals(s, GuidanceFlow.tick(s, parked, now + 800_000, 1500))
        val end = click(s)
        assertEquals(RunStage.ENDED, end.run!!.stage)
        assertTrue(end.run!!.handedOver)
        assertEquals("오늘 운행을 마쳤습니다", journeyNotice(end)!!.title)
    }

    @Test fun freightRetainsDockDepartureAndSeparateReceiptThroughCenter() {
        var s = GuidanceFlow.start(state("freight-cold"), true, now)
        repeat(3) { index ->
            s = arrive(s)
            assertEquals(JourneyActionKind.DOCK, InteractiveJourney.action(s)!!.kind)
            s = click(s)
            assertEquals(RunStage.WORKING, s.run!!.stage)
            assertEquals(0, s.run!!.result.units)
            assertTrue(journeyNotice(s)!!.title.contains("도크"))
            val completion = InteractiveJourney.action(s)!!
            s = click(s)
            assertEquals(index, s.run!!.index)
            assertEquals(s, InteractiveJourney.perform(s, completion, true, now + 31_000))
            if (index == 1) {
                assertEquals(RunStage.REVIEW, s.run!!.stage)
                assertFalse(s.run!!.result.receipt)
                assertFalse(s.run!!.ready)
                assertEquals(JourneyActionKind.RECEIPT, InteractiveJourney.action(s)!!.kind)
                assertEquals(s, GuidanceFlow.tick(s, parked, now + 600_000, 1500))
            } else {
                assertEquals(RunStage.DEPARTURE, s.run!!.stage)
                assertEquals(JourneyActionKind.DEPART, InteractiveJourney.action(s)!!.kind)
            }
            s = click(s, now + 40_000 + index)
            assertEquals(GuidanceStatus.ACTIVE, s.run!!.guidance!!.status)
        }
        assertEquals(RunStage.RETURN_TRAVEL, s.run!!.stage)
        s = arrive(s)
        assertEquals(4, s.run!!.result.units)
        assertEquals(RunStage.ENDED, click(s).run!!.stage)
    }

    @Test fun doubleTapStaleSessionAndStaleStageCannotCompleteAnotherStep() {
        val arrived = arrive(GuidanceFlow.start(state(), true, now))
        val action = InteractiveJourney.action(arrived)!!
        val next = click(arrived)
        assertEquals(next, InteractiveJourney.perform(next, action, true, now + 31_000))
        val newSession = arrived.copy(run = arrived.run!!.copy(sessionId = "another-session"))
        assertEquals(newSession, InteractiveJourney.perform(newSession, action, true, now + 31_000))
        val nextArrived = arrive(finishParcel(arrived))
        assertEquals(nextArrived, InteractiveJourney.perform(nextArrived, action, true, now + 32_000))
    }

    @Test fun disconnectedCompletionQueuesAndDoesNotDepartOrAutoCompleteAfterReconnect() {
        val arrived = click(arrive(GuidanceFlow.start(state(), true, now))).copy(terminalConnected = false)
        val queued = DemoWorkflow.terminal(arrived, arrived.run!!.event(TerminalKind.RESULT), true, now)
        assertEquals(RunStage.WORKING, queued.run!!.stage)
        assertEquals(1, queued.run!!.pending.size)
        assertNull(InteractiveJourney.action(queued))
        assertNull(journeyNotice(queued))
        val connected = DemoWorkflow.connection(queued, true, true, now + 40_000)
        assertEquals(RunStage.REVIEW, connected.run!!.stage)
        assertEquals(0, connected.run!!.index)
        assertEquals(0, click(connected).run!!.index)
        assertEquals(1, click(click(connected)).run!!.index)
    }

    @Test fun noNativeAutomationAndVehicleDoorRestrictionsRemain() {
        val arrived = arrive(GuidanceFlow.start(state(), true, now))
        val action = InteractiveJourney.action(arrived)!!
        assertEquals(arrived, InteractiveJourney.perform(arrived, action, false, now))
        val native = arrived.copy(guidanceMode = GuidanceMode.NATIVE_SAMPLE)
        assertNull(InteractiveJourney.action(native))
        assertNull(journeyNotice(native))
        assertEquals(native, InteractiveJourney.perform(native, action, true, now))
        val openDoor = click(receiveParcel(arrived.copy(cargoOpen = true)))
        assertEquals(RunStage.REVIEW, openDoor.run!!.stage)
        assertEquals(0, openDoor.run!!.index)
        assertEquals(openDoor, click(openDoor))
        assertEquals(1, click(openDoor.copy(cargoOpen = false)).run!!.index)
    }

    @Test fun travelHasNoCompletionActionAndOldDockResponseCannotChangeMode() {
        val moving = GuidanceFlow.start(state(), true, now)
        assertNull(InteractiveJourney.action(moving))
        val dock = arrive(GuidanceFlow.start(state("freight-cold"), true, now))
        val response = InteractiveJourney.action(dock)!!
        val native = GuidanceFlow.mode(dock, GuidanceMode.NATIVE_SAMPLE, true)
        assertEquals(native, InteractiveJourney.perform(native, response, true, now))
    }

    @Test fun progressAndResultsSurviveRestoreWithoutFinishingNextWork() {
        val next = finishParcel(arrive(GuidanceFlow.start(state(), true, now)))
        val restored = next.copy(run = DemoRunCodec.decode(DemoRunCodec.encode(next.run!!), now + 50_000))
        assertEquals(next.run, restored.run)
        assertEquals(0, restored.run!!.result.deliveries)
        assertEquals(restored, GuidanceFlow.tick(restored, parked, now + 100_000, 0))
        var moved = restored
        repeat(5) { moved = GuidanceFlow.tick(moved, parked, now + 101_000 + it * 1000, 1000) }
        assertNull(journeyNotice(moved))
    }

    @Test fun fullResultsCannotDepartUntilReboardingEvenAfterProcessRestore() {
        val received = receiveParcel(arrive(GuidanceFlow.start(state(), true, now)))
        val restored = received.copy(run = DemoRunCodec.decode(DemoRunCodec.encode(received.run!!), now + 40_000))
        assertEquals(received.run, restored.run)
        assertTrue(restored.run!!.ready)
        assertEquals(restored, GuidanceFlow.next(restored, true, now + 41_000))
        assertEquals(JourneyActionKind.REBOARD, InteractiveJourney.action(restored)!!.kind)
        val reviewed = click(restored)
        assertEquals(RunStage.REVIEW, reviewed.run!!.stage)
        assertEquals(1, click(reviewed).run!!.index)
    }

    @Test fun aReasonCanResolveOutstandingWorkWithoutTurningItIntoDeliveredCounts() {
        val reviewed = click(receiveParcel(arrive(GuidanceFlow.start(state(), true, now)), partial = true))
        val deferred = DemoWorkflow.terminal(reviewed,
            reviewed.run!!.event(TerminalKind.DEFER, reason = "고객 부재 · 센터 인계"), true, now + 40_000)
        assertTrue(deferred.run!!.ready)
        assertEquals(11, deferred.run!!.result.deliveries)
        assertEquals(1, deferred.run!!.result.pickups)
        val next = click(deferred)
        assertEquals(1, next.run!!.index)
        assertEquals("고객 부재 · 센터 인계", next.run!!.results[reviewed.run!!.stop.id]!!.reason)
    }

    @Test fun oldSavedRunWithoutHandoffFlagStillRestores() {
        val run = state().run!!
        val oldJson = DemoRunCodec.encode(run).replace(",\"parcelAway\":false", "")
        assertFalse(DemoRunCodec.decode(oldJson, now + 1000)!!.parcelAway)
    }
}
