package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test

class FlowPreviewTest {
    private val now = 1_800_000_000_000L

    @Test fun bothStoriesFinishOnlyAfterCenterHandoff() {
        ServiceMode.entries.forEach { mode ->
            val scenes = FlowPreview.scenes(mode, now)
            val returned = scenes.first { it.label == "센터 도착" }.state.run!!
            assertEquals(RunStage.RETURNED, returned.stage)
            assertFalse(returned.handedOver)
            val ended = scenes.last().state.run!!
            assertEquals(RunStage.ENDED, ended.stage)
            assertTrue(ended.handedOver)
            assertEquals(ended.plan.stops.size, ended.results.size)
        }
    }

    @Test fun movementIsAnimatedTimeAndNeverCompletesTheJob() {
        ServiceMode.entries.forEach { mode ->
            val scene = FlowPreview.scenes(mode, now).first { it.moving }
            val halfway = FlowPreview.stateAt(scene, 9_000).run!!
            assertEquals(.5f, halfway.guidance!!.progress, .001f)
            assertEquals(GuidanceStatus.ACTIVE, halfway.guidance!!.status)
            val arrived = FlowPreview.stateAt(scene, 18_000).run!!
            assertEquals(GuidanceStatus.ARRIVED, arrived.guidance!!.status)
            assertEquals(if (mode == ServiceMode.FREIGHT) RunStage.WAITING else RunStage.WORKING, arrived.stage)
            assertEquals(StopResult(), arrived.result)
            assertFalse(arrived.ready)
            assertEquals(0L, scene.state.run!!.guidance!!.elapsedMs)
        }
    }

    @Test fun freightUnloadingRequiresASeparateReceipt() {
        val scenes = FlowPreview.scenes(ServiceMode.FREIGHT, now)
        val unloaded = scenes.first { it.label == "단말 결과 수신" && it.state.run!!.stop.operation == "하차" }.state.run!!
        assertEquals(unloaded.stop.freight!!.units, unloaded.result.units)
        assertFalse(unloaded.result.receipt)
        assertFalse(unloaded.ready)
        val receipt = scenes.first { it.label == "인수 확인" }.state.run!!
        assertTrue(receipt.result.receipt)
        assertTrue(receipt.ready)
    }

    @Test fun everyStopAndReturnHasItsOwnUnstartedDemoLeg() {
        ServiceMode.entries.forEach { mode ->
            val scenes = FlowPreview.scenes(mode, now)
            val moves = scenes.filter { it.moving }
            assertEquals(scenes.first().state.run!!.plan.stops.size + 1, moves.size)
            assertEquals(moves.size, moves.map { it.state.run!!.navigationVisitId }.toSet().size)
            moves.forEach {
                val run = it.state.run!!
                assertEquals(GuidanceMode.DEMO, run.guidance!!.mode)
                assertEquals(0L, run.guidance!!.elapsedMs)
                assertNull(run.guidance!!.target)
                assertNull(run.navigationBinding)
            }
            assertTrue(moves.last().state.run!!.returning)
        }
    }

    @Test fun manualSeekingDoesNotAdvanceTheStoredSnapshot() {
        val scene = FlowPreview.scenes(ServiceMode.PARCEL, now).first { it.moving }
        val end = FlowPreview.stateAt(scene, 30_000)
        val start = FlowPreview.stateAt(scene, -1)
        assertEquals(GuidanceStatus.ARRIVED, end.run!!.guidance!!.status)
        assertEquals(0f, start.run!!.guidance!!.progress, .001f)
        assertEquals(RunStage.TRAVEL, scene.state.run!!.stage)
    }
}
