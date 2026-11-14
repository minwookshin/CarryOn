package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OperationsPresentationTest {
    private val now = 1_000_000L
    private fun fixture(name: String = "parcel") = OperationsCodec.decode(listOf(
        "src/main/assets/work/$name.json", "app/src/main/assets/work/$name.json").map(::File).first { it.exists() }.readText())!!
    private fun finish(l: WorkLedger, kind: TaskKind? = null, sync: SyncMark = SyncMark.CONFIRMED) = l.copy(tasks = l.tasks.map {
        if(it.visitId == l.currentVisitId && (kind == null || it.kind == kind)) it.copy(completed=it.quantity,sync=sync,updatedAt=now) else it
    })
    @Test fun firstUseDoesNotLoadExampleOrStartNavigation() {
        val s=applyWorkPreferences(BoardState(),ServiceMode.FREIGHT,true)
        assertNull(s.ledger!!.current); assertTrue(s.ledger!!.tasks.isEmpty()); assertNull(s.route)
        assertFalse(s.canPresentNative); assertEquals(ServiceMode.FREIGHT,s.preferredMode); assertTrue(s.preferredCold)
    }
    @Test fun samePreferencesNeverDisconnectOrResetCompletedCompanyWork() {
        val l=finish(fixture("freight").copy(streamId="company",currentVisitId="f-handoff",dayClosed=true))
        val s=BoardState(ledger=l,connected=true,route=BoardNavigation.start(l.current!!,GuidanceMode.NATIVE_SAMPLE,now))
        val result=applyWorkPreferences(s,l.mode,l.refrigerated)
        assertSame(l,result.ledger); assertSame(s.route,result.route); assertTrue(result.connected)
        assertTrue(result.ledger!!.dayClosed)
    }
    @Test fun DifferentDefaultsCannotChangeDispatchConditionsOrAuth() {
        val l=fixture("freight").copy(streamId="company",drivingEnded=true)
        val s=BoardState(ledger=l,connected=true)
        val result=applyWorkPreferences(s,ServiceMode.PARCEL,false)
        assertSame(l,result.ledger); assertTrue(result.connected); assertTrue(result.ledger!!.refrigerated)
        assertEquals(-20f,result.ledger!!.current!!.requiredLow)
    }
    @Test fun explicitNativeHandoffNeedsCurrentRouteAndUnfinishedDriving() {
        val l=fixture()
        val s=BoardState(ledger=l,route=BoardNavigation.start(l.current!!,GuidanceMode.NATIVE_SAMPLE,now))
        assertTrue(s.canPresentNative)
        assertFalse(s.copy(ledger=l.copy(dayClosed=true)).canPresentNative)
        assertFalse(s.copy(ledger=l.copy(drivingEnded=true)).canPresentNative)
        assertFalse(s.copy(route=null).canPresentNative)
        assertFalse(s.copy(navigationMode=GuidanceMode.DEMO).canPresentNative)
    }
    @Test fun setupAndLinkingCannotTimeOutANavigationRequestThatWasNotSent() {
        val r=BoardNavigation.start(fixture().current!!,GuidanceMode.NATIVE_SAMPLE,now)
        assertEquals(r,BoardNavigation.tick(r,VehicleState(),now+600_000,1000))
    }
    @Test fun zeroDeliveriesWithOutstandingReturnsIsNotCompletion() {
        val l=finish(fixture(),TaskKind.DELIVERY)
        assertEquals(WorkHeadline.ACTIVE,workSummary(l).state)
        assertEquals(2,l.remaining(TaskKind.RETURN)); assertFalse(l.dayClosed)
        assertEquals(TaskKind.RETURN,l.attentionKind)
    }
    @Test fun zeroPendingDeliveryIsVisibleBeforePositiveCompletion() {
        val l=finish(fixture(),TaskKind.DELIVERY,SyncMark.PENDING)
        assertEquals(0,l.remaining(TaskKind.DELIVERY)); assertEquals(WorkHeadline.PENDING,workSummary(l).state)
        assertTrue(l.currentTasks.first().needsAttention); assertFalse(l.currentTasks.confirmedComplete)
    }
    @Test fun completedCurrentVisitWaitsForRealAssignmentWithoutEndingDay() {
        val l=finish(fixture())
        assertEquals(WorkHeadline.COMPLETE,workSummary(l).state)
        assertEquals("다음 배정 대기",workSummary(l).detail)
        assertEquals("p-zone-1",l.currentVisitId); assertFalse(l.dayClosed)
    }
    @Test fun receiptWarningOnlyBecomesPrimaryAfterConfirmedUnloading() {
        val before=fixture("freight").copy(currentVisitId="f-unload")
        assertEquals(WorkHeadline.ACTIVE,workSummary(before).state)
        val unloaded=finish(before,TaskKind.UNLOAD)
        assertEquals(WorkHeadline.RECEIPT,workSummary(unloaded).state)
        assertEquals(WorkHeadline.COMPLETE,workSummary(finish(unloaded,TaskKind.RECEIPT)).state)
    }
    @Test fun localUnloadResultStillAwaitsConfirmation() {
        val l=finish(fixture("freight").copy(currentVisitId="f-unload"),TaskKind.UNLOAD,SyncMark.LOCAL)
        assertEquals(WorkHeadline.PENDING,workSummary(l).state)
    }
    @Test fun endingDrivingAndWorkRemainDifferentAndKeepOutstandingRecords() {
        val l=fixture("freight").copy(currentVisitId="f-unload")
        assertEquals(WorkHeadline.DRIVING_ENDED,workSummary(l.copy(drivingEnded=true)).state)
        assertEquals(WorkHeadline.DAY_CLOSED,workSummary(l.copy(dayClosed=true,drivingEnded=true)).state)
        assertTrue(workSummary(l.copy(drivingEnded=true)).detail.contains("보존"))
    }
    @Test fun offlineMapDoesNotClaimItIsReceivingWork() {
        val l=fixture().copy(streamId="company")
        val s=BoardState(ledger=l,route=BoardNavigation.start(l.current!!,GuidanceMode.DEMO,now).copy(status=GuidanceStatus.ARRIVED))
        assertEquals("도착 · 마지막 업무 기록 표시",mapStatus(s))
        assertEquals("도착 · 현재 작업 유지",mapStatus(s.copy(connected=true)))
    }
    @Test fun staleSensorRemainsUnknownAndOpenDoorCannotClearDeviation() {
        val l=fixture("freight").copy(sensor=CargoSample("s","single",-15f,-19f,true,now,15_000,true))
        assertTrue(cargoStatus(l,now).warning)
        assertTrue(cargoStatus(l,now+16_000).exceptional)
        assertEquals("현재 온도 확인 불가",cargoStatus(l,now+16_000).title)
    }
    @Test fun outOfRangeSetpointIsRelevantEvenWhenAirIsInRange() {
        val l=fixture("freight").copy(sensor=CargoSample("s","single",-19f,-12f,false,now,15_000,true))
        assertEquals("설정 온도 확인 필요",cargoStatus(l,now).title)
    }
    @Test fun completedItemsCanBeCollapsedWithoutRenumberingRemainingItems() {
        val l=fixture(); val done=l.tasks.take(8).map { it.id }.toSet()
        val updated=l.copy(tasks=l.tasks.map { if(it.id in done) it.copy(completed=it.quantity,sync=SyncMark.CONFIRMED) else it })
        val remaining=updated.currentTasks.filter { it.needsAttention }
        assertEquals(l.currentTasks.drop(8).map { it.id to it.number },remaining.map { it.id to it.number })
    }
    @Test fun optionalConfirmedConditionsRoundTripAndRejectExcessiveText() {
        val l=fixture("freight")
        val conditions=l.copy(visits=l.visits.map { it.copy(timeWindow="09:00–10:00",receiptInstructions="현장 인수 서명",plannedStop="예정된 중간 정차") })
        assertEquals(conditions,OperationsCodec.decode(OperationsCodec.encode(conditions)))
        assertFalse(l.copy(visits=l.visits.map { it.copy(cargoInstructions="x".repeat(301)) }).valid())
    }
    @Test fun freshnessTimeIncludesDateForOvernightWork() {
        assertTrue(recordTime(now).contains("/")); assertEquals("미수신",recordTime(0))
    }

    @Test fun nextVisitsFollowDispatchOrderAndNeverReplaceCurrentWork() {
        val l = fixture()
        assertEquals(listOf("p-zone-2", "p-collect"), upcomingVisits(l).take(2).map { it.id })
        assertEquals("p-zone-1", l.currentVisitId)
        assertTrue(upcomingVisits(l.copy(dayClosed = true)).isEmpty())
        assertTrue(upcomingVisits(l.copy(currentVisitId = null)).isEmpty())
        assertTrue(upcomingVisits(l.copy(currentVisitId = "p-final")).isEmpty())
    }

    @Test fun nextVisitPreviewRetainsPendingResultsAndSkipsConfirmedCompletion() {
        val l = fixture()
        val tasks = l.tasks.map { if(it.visitId == "p-zone-2") it.copy(completed = it.quantity, sync = SyncMark.PENDING) else it }
        val pending = l.copy(tasks = tasks)
        assertEquals("p-zone-2", upcomingVisits(pending).first().id)
        assertTrue(remainingQuantityText(pending.tasks.filter { it.visitId == "p-zone-2" }).startsWith("전송 대기"))
        val confirmed = pending.copy(tasks = pending.tasks.map { if(it.visitId == "p-zone-2") it.copy(sync = SyncMark.CONFIRMED) else it })
        assertEquals("p-collect", upcomingVisits(confirmed).first().id)
    }

    @Test fun quantitiesUseActualRemainingCountsAndKeepWorkKindsSeparate() {
        val parcel = fixture().tasks.first()
        val two = parcel.copy(id = "two", quantity = 2)
        val six = parcel.copy(id = "six", quantity = 6)
        assertEquals("배송 8개", remainingQuantityText(listOf(two, six)))
        assertEquals("배송 58개", remainingQuantityText(listOf(two, six.copy(quantity = 56))))
        assertEquals("배송 4개", remainingQuantityText(listOf(two, six.copy(completed = 4, sync = SyncMark.CONFIRMED))))
        assertEquals("배송 2개 · 인수 1건", remainingQuantityText(listOf(two, six.copy(kind = TaskKind.RECEIPT, quantity = 1, unit = "건"))))
        assertEquals("배송 2개 · 배송 6상자", remainingQuantityText(listOf(two, six.copy(unit = "상자"))))
        assertEquals("수량 미수신", remainingQuantityText(emptyList()))
    }

    @Test fun commercialDeliveryKeepsItsAssignedParcelService() {
        val l = fixture().copy(currentVisitId = "p-zone-2")
        assertEquals("한빛상가", l.current!!.name)
        assertEquals("택배", l.serviceLabel)
        assertEquals(TaskKind.DELIVERY, l.primaryKind)
        assertEquals("장거리 화물", fixture("freight").serviceLabel)
    }

    @Test fun mapRemainsVisibleWhenAppIsResizedOrAViewChanges() {
        assertEquals(WorkMapLayout(true, false), workMapLayout(1680f, GuidanceMode.DEMO))
        assertEquals(WorkMapLayout(false, true), workMapLayout(840f, GuidanceMode.DEMO))
        assertEquals(WorkMapLayout(false, true), workMapLayout(420f, GuidanceMode.DEMO))
        assertEquals(WorkMapLayout(false, false), workMapLayout(1680f, GuidanceMode.NATIVE_SAMPLE))
    }
}
