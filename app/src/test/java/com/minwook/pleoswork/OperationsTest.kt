package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OperationsTest {
    private val now=1_000_000L
    private fun fixture(name:String="parcel"): WorkLedger {
        val path=listOf("src/main/assets/work/$name.json","app/src/main/assets/work/$name.json").map(::File).first { it.exists() }
        return OperationsCodec.decode(path.readText())!!
    }
    private fun event(s:WorkLedger,kind:WorkEventKind,id:String="",revision:Long=1,completed:Int?=null,sync:SyncMark?=null,closed:Boolean?=null)=
        WorkEvent("$kind:$id:$revision",s.accountId,s.streamId,revision,kind,id,completed,sync,occurredAt=now,closed=closed)
    private fun finish(s:WorkLedger,t:LedgerTask,rev:Long=1)=OperationsReducer.apply(s,event(s,WorkEventKind.TASK,t.id,rev,t.quantity,SyncMark.CONFIRMED),now)
    private fun current(s:WorkLedger,id:String,rev:Long=1)=OperationsReducer.apply(s,event(s,WorkEventKind.CURRENT,id,rev),now)

    @Test fun fixturesHaveDistinctTaskIdsAndOptionalCargoRequirements() {
        assertTrue(fixture().valid()); assertTrue(fixture("freight").valid())
        assertEquals(12,fixture().remaining(TaskKind.DELIVERY))
        assertEquals(3,fixture().currentTasks.map { it.entrance }.distinct().size)
    }
    @Test fun eightDeliveriesLeaveFourAcrossEntrancesWithoutReboardAction() {
        var s=fixture(); s.currentTasks.filter { it.kind==TaskKind.DELIVERY }.take(8).forEach { s=finish(s,it) }
        assertEquals(4,s.remaining(TaskKind.DELIVERY)); assertEquals(2,s.remaining(TaskKind.RETURN)); assertEquals("p-zone-1",s.currentVisitId)
    }
    @Test fun collectionAndTerminalDoNotLoseInterruptedDeliveryOrRenumberIt() {
        val initial=fixture(); val ids=initial.tasks.map { it.id to it.number }
        var s=current(initial,"p-collect"); s.currentTasks.forEach { s=finish(s,it) }
        s=current(s,"p-handoff",2); s.currentTasks.forEach { s=finish(s,it) }; s=current(s,"p-zone-1",3)
        assertEquals(12,s.remaining(TaskKind.DELIVERY)); assertEquals(ids,s.tasks.map { it.id to it.number }); assertFalse(s.dayClosed)
        assertEquals(68,s.tasks.filter { t -> t.kind==TaskKind.DELIVERY && s.visits.first { it.id==t.visitId }.round==1 }.sumOf { it.remaining })
    }
    @Test fun latePreviousVisitResultDoesNotChangeActiveCollection() {
        var s=current(fixture(),"p-collect")
        s=finish(s,s.tasks.first { it.id=="p-1-001" })
        assertEquals("p-collect",s.currentVisitId); assertEquals(11,s.remaining(TaskKind.DELIVERY,"p-zone-1"))
    }
    @Test fun perEntityRevisionAllowsLateIndependentItemAndRejectsStaleOverwrite() {
        val initial=fixture(); val a=initial.tasks[0]; val b=initial.tasks[1]
        val s=finish(finish(initial,a,100),b,2)
        assertEquals(10,s.remaining(TaskKind.DELIVERY))
        assertEquals(s,OperationsReducer.apply(s,event(s,WorkEventKind.TASK,a.id,99,0,SyncMark.OPEN),now))
    }
    @Test fun duplicateEventIsIdempotent() {
        val s=fixture();val e=event(s,WorkEventKind.TASK,s.tasks[0].id,1,1,SyncMark.CONFIRMED)
        val next=OperationsReducer.apply(s,e,now);assertEquals(next,OperationsReducer.apply(next,e,now))
    }
    @Test fun secondRoundAtSameAddressIsIndependent() {
        var s=fixture();s.currentTasks.forEach { s=finish(s,it) };s=current(s,"p-round-2")
        assertEquals(2,s.remaining(TaskKind.DELIVERY));assertEquals(0,s.remaining(TaskKind.DELIVERY,"p-zone-1"))
    }
    @Test fun pendingResultIsDistinctFromServerConfirmation() {
        val s=fixture();val e=event(s,WorkEventKind.TASK,s.tasks[0].id,1,1,SyncMark.PENDING)
        val pending=OperationsReducer.apply(s,e,now)
        assertEquals(11,pending.remaining(TaskKind.DELIVERY));assertEquals(1,pending.pendingCount)
        val confirmed=OperationsReducer.apply(pending,e.copy(id="confirmed",revision=2,sync=SyncMark.CONFIRMED),now)
        assertEquals(11,confirmed.remaining(TaskKind.DELIVERY));assertEquals(0,confirmed.pendingCount)
    }
    @Test fun zeroDeliveriesDoesNotEndDay() {
        var s=fixture();s.tasks.filter { it.kind==TaskKind.DELIVERY }.forEach { s=finish(s,it) }
        assertEquals(0,s.remaining(TaskKind.DELIVERY,null));assertFalse(s.dayClosed);assertTrue(s.remaining(TaskKind.RETURN,null)>0)
    }
    @Test fun dayAndDrivingClosureNeverClearWork() {
        val s=fixture("freight")
        val ended=OperationsReducer.apply(s,event(s,WorkEventKind.DRIVING,closed=true),now)
        assertTrue(ended.drivingEnded);assertFalse(ended.dayClosed);assertEquals(s.tasks,ended.tasks)
        val closed=OperationsReducer.apply(ended,event(s,WorkEventKind.DAY,closed=true),now)
        assertTrue(closed.dayClosed);assertEquals(s.tasks,closed.tasks)
    }
    @Test fun workSurvivesOldDatesAndSerializationWithoutAuthExpiry() {
        val s=fixture("freight").copy(lastSyncAt=1,drivingEnded=true)
        assertEquals(s,OperationsCodec.decode(OperationsCodec.encode(s)))
    }
    @Test fun unloadingDoesNotConfirmReceipt() {
        var s=current(fixture("freight"),"f-unload")
        s=finish(s,s.currentTasks.first { it.kind==TaskKind.UNLOAD })
        assertEquals(0,s.remaining(TaskKind.UNLOAD));assertEquals(1,s.remaining(TaskKind.RECEIPT))
    }
    @Test fun arrivalDoesNotRegisterSiteOrCompleteTasks() {
        val s=fixture("freight");val parked=VehicleState(automotive=true,uxRestricted=false,gear=4,speedKph=0f)
        var route=BoardNavigation.start(s.current!!,GuidanceMode.DEMO,now)
        repeat(18) { route=BoardNavigation.tick(route,parked,now+(it+1)*1000,1000) }
        assertEquals(GuidanceStatus.ARRIVED,route.status);assertEquals(FacilityPhase.UNKNOWN,s.current!!.phase);assertEquals(24,s.remaining(TaskKind.LOAD))
    }
    @Test fun movingCarCannotAdvanceFixtureRouteButCanReceiveWork() {
        val s=fixture();val r=BoardNavigation.start(s.current!!,GuidanceMode.DEMO,now)
        assertEquals(r,BoardNavigation.tick(r,VehicleState(automotive=true,gear=8,speedKph=0f,uxRestricted=false),now+1000,1000))
        assertEquals(11,finish(s,s.tasks.first()).remaining(TaskKind.DELIVERY))
    }
    @Test fun nativeArrivalMustMatchRequestedDestinationAndBeFresh() {
        val s=fixture();val target=s.current!!.target!!
        val r=BoardRoute(s.currentVisitId!!,GuidanceMode.NATIVE_SAMPLE,GuidanceStatus.ACTIVE,target,now-1000,now-1000,now-500)
        val bad=VehicleState(navigationArrival=NavArrival(nativeSampleTargets[1],now),navigation=NavReading(target,10,20,now))
        assertNotEquals(GuidanceStatus.ARRIVED,BoardNavigation.tick(r,bad,now,1000).status)
    }
    @Test fun sensorExpiryAndDoorOpeningCannotBecomeNormalEvidence() {
        val sample=CargoSample("s","single",-15f,-19f,true,now,15000,true)
        assertTrue(sample.fresh(now+14999));assertFalse(sample.fresh(now+15001));assertFalse(sample.copy(available=false).fresh(now))
        assertFalse(TemperatureRequirement(-20f,-18f).contains(sample.measuredC))
    }
    @Test fun malformedAndCrossAccountUpdatesCannotAdvanceCursor() {
        val s=fixture();val bad=event(s,WorkEventKind.TASK,s.tasks[0].id,1,2,SyncMark.CONFIRMED)
        assertEquals(s,OperationsReducer.apply(s,bad,now))
        assertEquals(s,OperationsReducer.apply(s,bad.copy(accountId="other"),now))
        try { OperationsReducer.batch(s,WorkBatch("other",s.streamId,100),now);fail() } catch(_:IllegalArgumentException) {}
        assertEquals(0,s.cursor)
    }
    @Test fun taskResultDuringDrivingEndedStillPersists() {
        val s=fixture().copy(drivingEnded=true)
        assertEquals(11,finish(s,s.tasks[0]).remaining(TaskKind.DELIVERY))
    }
    @Test fun recordingGapsAndUnknownStopsAreNotRest() {
        assertTrue(recordDriving(emptyList(),1000,false,10000,0f).isEmpty())
        assertTrue(recordDriving(emptyList(),1000,false,2000,null).isEmpty())
        val segments=recordDriving(emptyList(),1000,false,2000,0f)
        assertFalse(segments.single().moving);assertEquals(1000,segments.single().end-segments.single().start)
    }
    @Test fun invalidSavedPayloadDoesNotPassValidation() {
        val raw=OperationsCodec.encode(fixture()).replace("\"PARCEL\"","\"UNKNOWN\"")
        assertNull(OperationsCodec.decode(raw));assertNull(OperationsCodec.decode("{}"))
    }
    @Test fun productionEndpointRequiresHttpsAndNoEmbeddedCredential() {
        assertTrue(WorkServiceClient.allowedBase("https://carrier.example/api",false))
        assertFalse(WorkServiceClient.allowedBase("http://carrier.example",true))
        assertTrue(WorkServiceClient.allowedBase("http://127.0.0.1:8787",true))
        assertFalse(WorkServiceClient.allowedBase("http://127.0.0.1:8787",false))
        assertFalse(WorkServiceClient.allowedBase("https://name:secret@carrier.example",false))
    }
    @Test fun combinedLegacyOperationLabelDoesNotMislabelDeliveryCount() {
        val s=fixture().let { it.copy(visits=it.visits.map { v -> v.copy(operation="배송·회수") }) }
        assertEquals(TaskKind.DELIVERY,s.primaryKind)
        assertEquals(TaskKind.HANDOFF,current(fixture(),"p-handoff").primaryKind)
        assertNull(s.copy(tasks=emptyList()).primaryKind)
    }
    @Test fun sensorHistoryKeepsRequirementAndDoorDeviationAfterVisitChanges() {
        val s=fixture("freight")
        val sample=CargoSample("s","single",-15f,-19f,true,now,15000,true)
        val e=event(s,WorkEventKind.SENSOR).copy(sensor=sample)
        val next=current(OperationsReducer.apply(s,e,now),"f-container")
        assertTrue(next.cargoHistory.single().outside)
        assertEquals("f-load",next.cargoHistory.single().visitId)
        assertEquals(-20f,next.cargoHistory.single().requiredLow)
        assertEquals(next,OperationsCodec.decode(OperationsCodec.encode(next)))
    }
    @Test fun olderSchemaTwoWithoutHistoryRetainsEveryTask() {
        val s=fixture()
        val raw=OperationsCodec.encode(s).replace("\"cargoHistory\":[],", "").replace(",\"noteRevision\":0", "")
        assertEquals(s.tasks,OperationsCodec.decode(raw)!!.tasks)
    }
    @Test fun malformedEventBatchCannotPartiallyApplyOrSkipCursor() {
        val s=fixture()
        val valid=event(s,WorkEventKind.TASK,s.tasks[0].id,1,1,SyncMark.CONFIRMED)
        val bad=event(s,WorkEventKind.TASK,s.tasks[1].id,2,999,SyncMark.CONFIRMED)
        try { OperationsReducer.batch(s,WorkBatch(s.accountId,s.streamId,2,listOf(valid,bad)),now);fail() }
        catch(_:IllegalArgumentException) {}
        assertEquals(12,s.remaining(TaskKind.DELIVERY));assertEquals(0,s.cursor)
    }
    @Test fun staleNoteCannotReplaceNewInstruction() {
        val s=fixture()
        val latest=event(s,WorkEventKind.NOTE,revision=7).copy(note="남문")
        val next=OperationsReducer.apply(s,latest,now)
        assertEquals("남문",OperationsReducer.apply(next,latest.copy(id="old",revision=6,note="북문"),now).note)
    }
}
