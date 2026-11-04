package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class DemoWorkflowTest {
    private val now = 1_800_000_000_000L
    private fun start(id: String = "parcel-east"): CompanionState {
        val driver = demoDrivers.first { it.id == id }
        return CompanionState(link = DriverLink(phase = LinkPhase.ACTIVE, activeId = id),
            run = DemoWorkflow.create(driver, now), targetTemperature = if (driver.refrigerated) -18 else 4)
    }
    private fun advance(s: CompanionState) = DemoWorkflow.advance(s, true)
    private fun arrive(s: CompanionState) = DemoWorkflow.arrive(s, true)
    private fun event(s: CompanionState, kind: TerminalKind, partial: Boolean = false, reason: String = "") =
        DemoWorkflow.terminal(s, s.run!!.event(kind, partial, reason), true, now + 1000)
    private fun beginWork(s: CompanionState): CompanionState {
        val arrived = advance(arrive(advance(s)))
        return if (s.mode == ServiceMode.FREIGHT) event(arrived, TerminalKind.DOCK) else arrived
    }

    @Test fun parcelEndToEndRequiresResultsAndHandoff() {
        var s = start()
        assertEquals(RunStage.PREPARING, s.run!!.stage)
        s = beginWork(s)
        assertEquals(RunStage.WORKING, s.run!!.stage)
        assertEquals(s, advance(s))
        s = event(s, TerminalKind.RESULT, true)
        assertEquals(1, s.run!!.missingDeliveries)
        assertEquals(1, s.run!!.missingPickups)
        assertEquals(s, advance(s))
        s = event(s, TerminalKind.DEFER, reason = "고객 부재 · 반납 및 재방문 인계")
        assertTrue(s.run!!.ready)
        s = advance(s)
        assertEquals(1, s.run!!.index)
        assertEquals("한빛상가", s.site!!.name)
        for (i in 1..2) {
            s = advance(arrive(s))
            s = event(s, TerminalKind.RESULT)
            s = advance(s)
        }
        assertEquals(RunStage.RETURN_TRAVEL, s.run!!.stage)
        s = arrive(s)
        assertEquals(1, s.run!!.deferredDeliveries)
        assertEquals(1, s.run!!.uncollected)
        assertEquals(2, s.run!!.collected)
        assertEquals(s, advance(s))
        s = event(s, TerminalKind.HANDOFF)
        s = advance(s)
        assertEquals(RunStage.ENDED, s.run!!.stage)
    }

    @Test fun freightStartsAtPickupAndRequiresDockQuantityAndReceipt() {
        var s = start("freight-cold")
        assertEquals(0, s.run!!.index)
        s = advance(arrive(advance(s)))
        assertEquals(RunStage.WAITING, s.run!!.stage)
        assertEquals(s, event(s, TerminalKind.RESULT))
        s = event(s, TerminalKind.DOCK)
        assertEquals("02번 도크", s.run!!.result.dock)
        s = event(s, TerminalKind.RESULT, true)
        assertEquals(s, advance(s))
        s = event(s, TerminalKind.RESULT)
        s = advance(s)
        assertEquals(RunStage.DEPARTURE, s.run!!.stage)
        s = advance(s)
        assertEquals(1, s.run!!.index)
        s = advance(arrive(s))
        s = event(s, TerminalKind.DOCK)
        s = event(s, TerminalKind.RESULT)
        assertFalse(s.run!!.ready)
        assertEquals(s, advance(s))
        s = event(s, TerminalKind.RECEIPT)
        assertTrue(s.run!!.ready)
        s = advance(s)
        assertEquals(2, s.run!!.index)
        s = advance(arrive(s))
        s = event(s, TerminalKind.DOCK)
        s = event(s, TerminalKind.RESULT)
        s = advance(advance(s))
        assertEquals(RunStage.RETURN_TRAVEL, s.run!!.stage)
        s = advance(event(arrive(s), TerminalKind.HANDOFF))
        assertEquals(RunStage.ENDED, s.run!!.stage)
    }

    @Test fun offlineQueuePersistsAndReplaysOnce() {
        var s = beginWork(start())
        val e = s.run!!.event(TerminalKind.RESULT)
        s = DemoWorkflow.connection(s, false, true, now)
        val originalSite = s.site
        s = DemoWorkflow.terminal(s, e, true, now)
        assertEquals(1, s.run!!.pending.size)
        assertEquals(RunStage.WORKING, s.run!!.stage)
        assertEquals(originalSite, s.site)
        assertNotNull(s.dispatch)
        assertEquals(s, DemoWorkflow.terminal(s, e, true, now))
        s = s.copy(run = DemoRunCodec.decode(DemoRunCodec.encode(s.run!!), now + 5000))
        s = DemoWorkflow.connection(s, true, true, now + 6000)
        assertEquals(12, s.run!!.result.deliveries)
        assertTrue(s.run!!.pending.isEmpty())
        assertEquals(RunStage.REVIEW, s.run!!.stage)
        val recordedMinute = s.run!!.minute
        s = DemoWorkflow.terminal(s, e, true, now + 7000)
        assertEquals(recordedMinute, s.run!!.minute)
        assertEquals(12, s.run!!.result.deliveries)
    }

    @Test fun oldSessionsStopsAndOutOfOrderResultsCannotOverwrite() {
        val s = beginWork(start())
        val e = s.run!!.event(TerminalKind.RESULT)
        assertEquals(s, DemoWorkflow.terminal(s, e.copy(sessionId = "old"), true, now))
        assertEquals(s, DemoWorkflow.terminal(s, e.copy(stopId = "east-shops"), true, now))
        assertEquals(s, DemoWorkflow.terminal(s, e.copy(deliveries = 999), true, now))
        val next = DemoWorkflow.terminal(s, e.copy(sequence = 2), true, now)
        assertEquals(next, DemoWorkflow.terminal(next, e.copy(id = "late", sequence = 1, deliveries = 0), true, now))
    }

    @Test fun vehicleUnknownOrMovingBlocksActionsButDemoStageDoesNotBlockParkedCargo() {
        val s = start()
        assertEquals(s, DemoWorkflow.advance(s, false))
        val work = beginWork(s)
        assertEquals(work, DemoWorkflow.terminal(work, work.run!!.event(TerminalKind.RESULT), false, now))
        val driving = advance(s)
        assertTrue(CompanionReducer.door(driving, true, true).cargoOpen)
        assertEquals(driving, CompanionReducer.door(driving, true, false))
        assertEquals(driving, DemoWorkflow.arrive(driving, false))
        assertEquals(s, advance(s.copy(cargoOpen = true)).copy(cargoOpen = false))
    }

    @Test fun apiModeNeverAdvancesFictionalDispatch() {
        val s = start().copy(demo = false)
        assertNull(s.dispatch)
        assertNull(s.site)
        assertEquals(s, advance(s))
        assertEquals(s, DemoWorkflow.connection(s, false, true, now))
    }

    @Test fun resumeRestAndDayEndKeepCargoAndCurrentLeg() {
        var s = advance(start("freight-cold"))
        val minute = s.run!!.minute
        s = DemoWorkflow.rest(s, true)
        assertTrue(s.run!!.resting)
        assertEquals(s, arrive(s))
        s = DemoWorkflow.rest(s, true)
        assertEquals(minute + 20, s.run!!.minute)
        assertEquals(20, s.run!!.restMinutes)
        s = DemoWorkflow.endDay(s, true)
        assertTrue(s.run!!.dayEnded)
        assertTrue(s.coolingOn)
        assertEquals(s, arrive(s))
        val decoded = DemoRunCodec.decode(DemoRunCodec.encode(s.run!!), now + 1000)
        assertEquals(s.run, decoded)
        s = DemoWorkflow.endDay(s.copy(run = decoded), true)
        assertFalse(s.run!!.dayEnded)
        assertEquals(1920, s.run!!.minute)
        assertEquals(0, s.run!!.index)
    }

    @Test fun staleSensorDoesNotClaimHealthyOrChangeRequirement() {
        var s = start("freight-cold")
        val requirement = s.run!!.stop.freight!!.temperature
        s = DemoWorkflow.sensor(s, SensorExample.DEVIATION, true, now)
        assertEquals(-15.2f, s.airTemperature)
        assertFalse(requirement!!.contains(s.airTemperature))
        s = DemoWorkflow.sensor(s, SensorExample.OFFLINE, true, now)
        assertNull(s.airTemperature)
        assertEquals(-15.2f, s.run!!.sensorReading)
        assertEquals(requirement, s.run!!.stop.freight!!.temperature)
        assertEquals(-18, s.targetTemperature)
    }

    @Test fun malformedExpiredCrossDriverAndInvalidCountsAreDiscarded() {
        val r = start().run!!
        assertNull(DemoRunCodec.decode("{", now))
        assertNull(DemoRunCodec.decode("{}", now))
        assertNull(DemoRunCodec.decode(DemoRunCodec.encode(r), r.expiresAt))
        assertNull(DemoRunCodec.decode(DemoRunCodec.encode(r.copy(driverId = "nobody")), now))
        assertNull(DemoRunCodec.decode(DemoRunCodec.encode(r.copy(index = 100)), now))
        assertNull(DemoRunCodec.decode(DemoRunCodec.encode(r.copy(results = mapOf("east-hanbit" to StopResult(deliveries = 99)))), now))
        assertNull(DemoRunCodec.decode(DemoRunCodec.encode(r.copy(results = mapOf("cold-pickup" to StopResult()))), now))
    }

    @Test fun completedOfflineEndAcknowledgesWithoutRestartingWork() {
        var s = start()
        s = s.copy(run = s.run!!.copy(stage = RunStage.RETURNED, handedOver = true), terminalConnected = false)
        s = advance(s)
        assertTrue(s.run!!.endPending)
        s = DemoWorkflow.connection(s, true, true, now)
        assertEquals(RunStage.ENDED, s.run!!.stage)
        assertFalse(s.run!!.endPending)
    }

    @Test fun earlyDockAssignmentAdvancesScenarioClockWithoutCountingRest() {
        var s = start("freight-cold")
        s = s.copy(run = s.run!!.copy(index = 1, stage = RunStage.WAITING, minute = 620))
        s = event(s, TerminalKind.DOCK)
        assertEquals(780, s.run!!.minute)
        assertEquals(0, s.run!!.restMinutes)
    }
}
