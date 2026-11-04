package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class NavigationLinkTest {
    private val now = 1_800_000_000_000L
    private val target = NavTarget("42dot", 37.48544722, 127.03636666, "서울시 강남구 남부순환로 2621", "official-poi")
    private val reading = NavReading(target, 600, 2300, now)
    private fun start(id: String = "parcel-east"): CompanionState {
        val driver = demoDrivers.first { it.id == id }
        return CompanionState(link = DriverLink(phase = LinkPhase.ACTIVE, activeId = id), run = DemoWorkflow.create(driver, now))
    }

    @Test fun canWorkAtSiteWithoutStartingNavigationOrDemoTravel() {
        val s = start()
        val arrived = DemoWorkflow.atSite(s, true)
        assertEquals(RunStage.WORKING, arrived.run!!.stage)
        assertEquals(s.run!!.minute, arrived.run!!.minute)
        assertEquals(s, DemoWorkflow.atSite(s, false))
        assertEquals(arrived, DemoWorkflow.atSite(arrived, true))
        assertEquals(RunStage.WAITING, DemoWorkflow.atSite(start("freight-cold"), true).run!!.stage)
    }

    @Test fun terminalResultsDoNotRequireArrivalButtonAndStillReplayOnce() {
        var s = start().copy(terminalConnected = false)
        val e = s.run!!.event(TerminalKind.RESULT)
        s = DemoWorkflow.terminal(s, e, true, now + 1000)
        assertEquals(RunStage.PREPARING, s.run!!.stage)
        assertEquals(1, s.run!!.pending.size)
        assertEquals(s, DemoWorkflow.atSite(s, true))
        s = s.copy(run = DemoRunCodec.decode(DemoRunCodec.encode(s.run!!), now + 2000))
        s = DemoWorkflow.connection(s, true, true, now + 3000)
        assertEquals(RunStage.REVIEW, s.run!!.stage)
        assertEquals(12, s.run!!.result.deliveries)
        assertEquals(s, DemoWorkflow.terminal(s, e, true, now + 4000))
    }

    @Test fun freightDockCanArriveFirstButCannotSkipQuantityOrReceipt() {
        var s = start("freight-cold")
        val invalid = s.run!!.event(TerminalKind.RESULT)
        assertEquals(s, DemoWorkflow.terminal(s, invalid, true, now))
        s = DemoWorkflow.terminal(s, s.run!!.event(TerminalKind.DOCK), true, now)
        assertEquals(RunStage.WORKING, s.run!!.stage)
        assertEquals("02번 도크", s.run!!.result.dock)
        assertEquals(s, DemoWorkflow.advance(s, true))
    }

    @Test fun nativeArrivalRequiresBoundDestinationFreshEventAndActualParking() {
        val original = start()
        val arrival = NavArrival(target, now + 1000)
        assertEquals(original, DemoWorkflow.navigationArrived(original, arrival, true, now + 1000))
        val s = DemoWorkflow.bindNavigation(original, reading, true, now)
        assertEquals(s, DemoWorkflow.navigationArrived(s, arrival, false, now + 1000))
        assertEquals(s, DemoWorkflow.navigationArrived(s, arrival.copy(target = target.copy(latitude = 38.0)), true, now + 1000))
        assertEquals(s, DemoWorkflow.navigationArrived(s, arrival.copy(receivedAt = now - 1), true, now + 1000))
        assertEquals(s, DemoWorkflow.navigationArrived(s, arrival, true, now + 92_000))
        val arrived = DemoWorkflow.navigationArrived(s, arrival, true, now + 1000)
        assertEquals(RunStage.WORKING, arrived.run!!.stage)
        assertFalse(arrived.run!!.ready)
        assertEquals(0, arrived.run!!.result.deliveries)
        assertEquals(arrived, DemoWorkflow.navigationArrived(arrived, arrival, true, now + 2000))
    }

    @Test fun missingOrWrongAddressCannotBecomeArrivalEvent() {
        assertNotNull(navigationArrival(reading, target.name, target.address, now + 1000))
        assertNull(navigationArrival(reading, target.name, "", now + 1000))
        assertNull(navigationArrival(reading, "다른 곳", target.address, now + 1000))
        assertNull(navigationArrival(reading, target.name, "다른 주소", now + 1000))
        assertNull(navigationArrival(reading, target.name, target.address, now + 91_000))
        assertNull(navigationArrival(null, target.name, target.address, now))
    }

    @Test fun etaNeverLeaksToNextStopOrStaleOrDifferentRoute() {
        val s = DemoWorkflow.bindNavigation(start(), reading, true, now)
        val r = s.run!!
        assertEquals(reading, linkedNavigation(r, reading, now + 1000))
        assertNull(linkedNavigation(r.copy(index = 1), reading, now + 1000))
        assertNull(linkedNavigation(r, reading, now + 91_000))
        assertNull(linkedNavigation(r, reading.copy(target = target.copy(poiId = "another")), now))
        assertNull(linkedNavigation(r.copy(stage = RunStage.RETURN_TRAVEL), reading, now))
    }

    @Test fun bindingSurvivesRestartAndRejectsStaleOrInvalidCoordinates() {
        val s = start()
        assertEquals(s, DemoWorkflow.bindNavigation(s, reading, false, now))
        assertEquals(s, DemoWorkflow.bindNavigation(s, reading, true, now + 91_000))
        assertEquals(s, DemoWorkflow.bindNavigation(s, reading.copy(target = target.copy(latitude = Double.NaN)), true, now))
        val bound = DemoWorkflow.bindNavigation(s, reading, true, now)
        val restored = DemoRunCodec.decode(DemoRunCodec.encode(bound.run!!), now + 1000)!!
        assertEquals(bound.run!!.navigationBinding, restored.navigationBinding)
        assertNull(DemoRunCodec.decode(DemoRunCodec.encode(s.run!!), now + 1000)!!.navigationBinding)
    }

    @Test fun lastDeliveryArrivalNeverBecomesReturnCenterArrival() {
        val bound = DemoWorkflow.bindNavigation(start(), reading, true, now)
        val returning = bound.copy(run = bound.run!!.copy(stage = RunStage.RETURN_TRAVEL))
        assertNull(returning.run!!.activeNavigationBinding)
        assertEquals(returning, DemoWorkflow.navigationArrived(returning, NavArrival(target, now + 1000), true, now + 1000))
    }

    @Test fun returnCenterHasItsOwnBindingAndArrivalDoesNotConfirmHandoff() {
        val original = start().let { it.copy(run = it.run!!.copy(stage = RunStage.RETURN_TRAVEL)) }
        val bound = DemoWorkflow.bindNavigation(original, reading, true, now)
        val restored = bound.copy(run = DemoRunCodec.decode(DemoRunCodec.encode(bound.run!!), now + 1000))
        assertEquals("return:parcel-east", restored.run!!.activeNavigationBinding!!.stopId)
        assertEquals(reading, linkedNavigation(restored.run!!, reading, now + 1000))
        assertNull(linkedNavigation(restored.run!!.copy(stage = RunStage.TRAVEL), reading, now + 1000))
        assertEquals(restored, DemoWorkflow.navigationArrived(restored, NavArrival(target, now + 1000), false, now + 1000))
        val arrived = DemoWorkflow.navigationArrived(restored, NavArrival(target, now + 1000), true, now + 1000)
        assertEquals(RunStage.RETURNED, arrived.run!!.stage)
        assertFalse(arrived.run!!.handedOver)
    }
}
