package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

/** Contract tests use fake SDK events; they do not claim native emulator arrival verification. */
class NativeGuidanceCoordinatorTest {
    private class Session(driverId: String = "parcel-east") {
        var now = 1_800_000_000_000L
        var work = CompanionState(
            link = DriverLink(phase = LinkPhase.ACTIVE, activeId = driverId),
            run = DemoWorkflow.create(demoDrivers.first { it.id == driverId }, now),
            guidanceMode = GuidanceMode.NATIVE_SAMPLE,
        )
        var car = VehicleState(automotive = true, uxRestricted = false, gear = 4, speedKph = 0f,
            navigationStatus = "연결됨")
        val sent = mutableListOf<NavTarget>()
        var accepted = true
        val coordinator = NativeGuidanceCoordinator({ work }, { work = it(work) }, { car },
            { sent.add(it); accepted }, { now })
        fun sync() { now += 100; coordinator.synchronize() }
        fun start() { work = GuidanceFlow.start(work, car.canWork, now); sync() }
        fun acknowledge() {
            now += 100
            car = car.copy(navigation = NavReading(sent.last(), 60, 50, now),
                routeStatus = "ready", navigationArrival = null, routeEventAt = now)
            sync()
        }
        fun arrive() {
            now += 100
            car = car.copy(navigationArrival = NavArrival(sent.last(), now))
            sync()
        }
        fun terminal(kind: TerminalKind) {
            work = DemoWorkflow.terminal(work, work.run!!.event(kind), car.canWork, now)
            sync()
        }
        fun next() { work = GuidanceFlow.next(work, car.canWork, now); sync() }
    }

    @Test fun requestAndArrivalWorkWithoutAnyComposeScreenOrWindowSize() {
        val s = Session()
        s.start()
        repeat(5) { s.sync() }
        assertEquals(1, s.sent.size)
        assertEquals(s.work.run!!.stop.navigationTarget, s.sent.single())
        s.acknowledge()
        s.car = s.car.copy(gear = 8, speedKph = 20f)
        s.arrive()
        assertEquals(GuidanceStatus.ARRIVED, s.work.run!!.guidance!!.status)
        assertEquals(RunStage.TRAVEL, s.work.run!!.stage)
        s.now += 120_000 // The event is retained even if the driver parks much later.
        s.car = s.car.copy(gear = 4, speedKph = 0f)
        s.sync()
        assertEquals(RunStage.WORKING, s.work.run!!.stage)
        assertFalse(s.work.run!!.ready)
        assertEquals(0, s.work.run!!.result.deliveries)
    }

    @Test fun openingDetailsDoesNotLoseArrivalAndCompletionRequestsNextOnce() {
        val s = Session()
        s.start(); s.acknowledge(); s.arrive()
        s.terminal(TerminalKind.RESULT)
        s.next()
        assertEquals(1, s.work.run!!.index)
        assertEquals(2, s.sent.size)
        assertEquals(s.work.run!!.stop.navigationTarget, s.sent.last())
        repeat(10) { s.sync() }
        assertEquals(2, s.sent.size)
        // The first stop's still-present arrival must not arrive at the next stop.
        assertEquals(RunStage.TRAVEL, s.work.run!!.stage)
    }

    @Test fun completeFreightContractKeepsDockReceiptAndReturnHandoff() {
        val s = Session("freight-cold")
        s.start()
        repeat(3) { index ->
            s.acknowledge(); s.arrive()
            assertEquals(RunStage.WAITING, s.work.run!!.stage)
            s.next()
            assertEquals(index, s.work.run!!.index)
            s.terminal(TerminalKind.DOCK)
            s.terminal(TerminalKind.RESULT)
            if (s.work.run!!.stop.operation == "하차") {
                s.next()
                assertEquals(index, s.work.run!!.index)
                s.terminal(TerminalKind.RECEIPT)
            }
            s.next()
            if (s.work.run!!.stage == RunStage.DEPARTURE) s.next()
        }
        assertEquals(4, s.sent.size)
        assertEquals(s.work.run!!.plan.returnTarget, s.sent.last())
        s.acknowledge(); s.arrive()
        assertEquals(RunStage.RETURNED, s.work.run!!.stage)
        s.next()
        assertEquals(RunStage.RETURNED, s.work.run!!.stage)
        s.terminal(TerminalKind.HANDOFF); s.next()
        assertEquals(RunStage.ENDED, s.work.run!!.stage)
        assertEquals(4, s.sent.size)
    }

    @Test fun failedRequestNeverRetriesWithoutExplicitDriverAction() {
        val s = Session()
        s.accepted = false
        s.start()
        repeat(30) { s.sync() }
        assertEquals(GuidanceStatus.FAILED, s.work.run!!.guidance!!.status)
        assertEquals(1, s.sent.size)
        s.accepted = true
        s.start()
        assertEquals(2, s.sent.size)
    }

    @Test fun arrivalIsRetainedWhileTerminalResultsAreQueued() {
        val s = Session("freight-cold")
        s.start(); s.acknowledge()
        s.work = s.work.copy(terminalConnected = false)
        s.terminal(TerminalKind.DOCK)
        assertEquals(1, s.work.run!!.pending.size)
        s.arrive()
        assertEquals(GuidanceStatus.ARRIVED, s.work.run!!.guidance!!.status)
        assertEquals(RunStage.TRAVEL, s.work.run!!.stage)
        s.now += 120_000
        s.work = DemoWorkflow.connection(s.work, true, true, s.now)
        s.sync()
        assertEquals(RunStage.WORKING, s.work.run!!.stage)
        assertEquals(GuidanceStatus.ARRIVED, s.work.run!!.guidance!!.status)
    }

    @Test fun waitsForVehicleAndServiceAndDoesNotAdvanceLocalDemoInBackground() {
        val s = Session()
        s.car = s.car.copy(navigationStatus = "연결 대기")
        s.start()
        assertTrue(s.sent.isEmpty())
        s.car = s.car.copy(navigationStatus = "연결됨", gear = 8)
        s.sync()
        assertTrue(s.sent.isEmpty())
        s.car = s.car.copy(gear = 4)
        s.sync()
        assertEquals(1, s.sent.size)
        s.work = GuidanceFlow.mode(s.work, GuidanceMode.DEMO, true)
        s.work = GuidanceFlow.start(s.work, true, s.now)
        repeat(30) { s.sync() }
        assertEquals(0L, s.work.run!!.guidance!!.elapsedMs)
    }
}
