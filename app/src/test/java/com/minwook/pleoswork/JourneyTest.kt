package com.minwook.pleoswork

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson

class JourneyTest {
    @Test fun fullParcelShift() {
        var s=WorkState()
        repeat(3) { i ->
            assertEquals(i,s.stopIndex)
            s=JourneyReducer.depart(s,true,true);assertEquals("transit",s.phase)
            s=JourneyReducer.advance(s,Int.MAX_VALUE,true);assertEquals("arrival",s.phase)
            s=JourneyReducer.enterField(s,true);assertEquals("field",s.phase)
            s=JourneyReducer.completeDemoStop(s,true);assertEquals("return",s.phase)
            s=WorkReducer.nextStop(s,true)
        }
        assertEquals("finish",s.phase)
        s=WorkReducer.end(s,true)
        assertTrue(s.ended);assertEquals(25,s.deliveryCount);assertEquals(3,s.pickupCount);assertEquals(3,s.completedStops.size)
        assertEquals(14.4,s.travelledKm,.0001)
        assertEquals(s,JourneyReducer.advance(s,60,true))
    }
    @Test fun departureNeedsValidRouteAndParkingButNoChecklist() {
        listOf("parcel","freight").forEach { scenario ->
            val ready=WorkState(scenario=scenario)
            assertEquals("transit",JourneyReducer.depart(ready,true,true).phase)
            assertEquals(ready,JourneyReducer.depart(ready,false,true))
            assertEquals(ready,JourneyReducer.depart(ready,true,false))
            val resting=ready.copy(restStartedAt=100)
            assertEquals(resting,JourneyReducer.depart(resting,true,true))
            val ended=ready.copy(ended=true)
            assertEquals(ended,JourneyReducer.depart(ended,true,true))
        }
    }
    @Test fun cannotSkipOrEditOtherStops() {
        val s=WorkState(phase="field")
        assertEquals(s,WorkReducer.chooseStop(s,2,true))
        val other=s.parcels.first {it.stop!="hanbit"}
        assertEquals(s,WorkReducer.record(s,other.id,"done",true,"",true))
        val moving=s.copy(phase="transit")
        assertEquals(moving,WorkReducer.record(moving,moving.parcels.first().id,"done",true,"",true))
    }
    @Test fun trafficDelayShiftsAllDemoEtasWithoutInventingDistance() {
        val s=JourneyReducer.depart(WorkState(),true,true)
        val before=scheduleEstimates(s,VehicleState(),0,0)
        val delayed=JourneyReducer.delay(s,true)
        val after=scheduleEstimates(delayed,VehicleState(),0,0)
        before.indices.forEach {assertEquals(before[it]!!+300,after[it])}
        assertEquals(s.demoRangeKm,delayed.demoRangeKm,0.0)
    }
    @Test fun advancingPreservesEtaAndConsumesEnergyExactlyOnce() {
        val s=JourneyReducer.depart(WorkState(),true,true)
        val before=scheduleEstimates(s,VehicleState(),0,0)
        val stepped=JourneyReducer.advance(s,60,true)
        assertEquals(before,scheduleEstimates(stepped,VehicleState(),0,0))
        assertTrue(stepped.demoRangeKm<s.demoRangeKm)
        val arrived=JourneyReducer.advance(stepped,Int.MAX_VALUE,true)
        assertEquals(273.6,arrived.demoRangeKm,.0001)
        assertEquals(arrived,JourneyReducer.advance(arrived,60,true))
    }
    @Test fun actualModeNeverFallsBackToDemoValues() {
        val s=WorkState(demoMode=false)
        val missing=journeyEstimate(s,VehicleState(),100)
        assertNull(missing.seconds);assertNull(missing.km);assertNull(missing.fuelPercent);assertNull(missing.rangeKm)
        assertEquals(s,JourneyReducer.advance(s.copy(phase="transit"),60,true).copy(phase="prepare"))
        assertEquals(s,JourneyReducer.completeDemoStop(s,true))
    }
    @Test fun onlyMatchingFreshNavUpdatesThisSchedule() {
        val target=NavTarget("42dot",37.48544722,127.03636666)
        val s=WorkState(demoMode=false,bindings=mapOf("hanbit" to target))
        val car=VehicleState(navigation=NavReading(target,600,5000,1000),rangeKm=22.0,rangeUpdatedAt=1000)
        val e=journeyEstimate(s,car,2000)
        assertEquals(600,e.seconds);assertEquals(5.0,e.km!!,.001);assertTrue(e.lowRange)
        assertEquals(17.0,e.arrivalRange!!,.001)
        assertNull(journeyEstimate(s,car,100000).seconds)
        assertNull(journeyEstimate(s,car.copy(navigation=car.navigation!!.copy(target=target.copy(latitude=38.0))),2000).seconds)
        assertNull(scheduleEstimates(s,car,2000,9*3600)[1])
    }
    @Test fun freightRequiresArrivalUnloadAndSignature() {
        var s=WorkState(scenario="freight")
        assertEquals(s,WorkReducer.freight(s,"unload",true,true))
        s=JourneyReducer.depart(s,true,true);s=JourneyReducer.advance(s,Int.MAX_VALUE,true)
        assertEquals("arrival",s.phase)
        assertEquals(s,WorkReducer.freight(s,"unload",true,true))
        assertEquals(s.copy(dock="07"),WorkReducer.freight(s.copy(dock="07"),"unload",false,true))
        s=s.copy(dock="07");s=WorkReducer.freight(s,"unload",true,true)
        assertTrue(s.unloaded);assertFalse(s.signed)
        assertEquals(s,WorkReducer.freight(s,"finish",true,true))
        s=WorkReducer.freight(s,"sign",true,true);s=WorkReducer.freight(s,"finish",true,true)
        assertTrue(s.ended);assertEquals(setOf("freight"),s.completedStops)
    }
    @Test fun savedJourneyRestoresFullProgress() {
        val gson=Gson()
        var s=JourneyReducer.depart(WorkState(),true,true)
        s=JourneyReducer.advance(s,60,true)
        val restored=gson.fromJson(gson.toJson(s),WorkState::class.java)
        assertEquals(s,restored)
        assertEquals(JourneyReducer.advance(s,60,true),JourneyReducer.advance(restored,60,true))
    }
    @Test fun arrivalTimeStaysFixedWhileLaterScheduleChanges() {
        var s=JourneyReducer.advance(JourneyReducer.depart(WorkState(),true,true),Int.MAX_VALUE,true)
        val arrived=scheduleEstimates(s,VehicleState(),0,0)
        assertEquals(9*3600,arrived[0])
        s=JourneyReducer.enterField(s,true)
        s=WorkReducer.record(s,s.parcels.first().id,"done",true,"",true)
        assertEquals(arrived[0],scheduleEstimates(s,VehicleState(),0,0)[0])
        val done=JourneyReducer.completeDemoStop(s,true)
        val next=WorkReducer.nextStop(done,true)
        assertEquals(9*3600,scheduleEstimates(next,VehicleState(),0,0)[0])
    }
    @Test fun energySignalsExpireIndependently() {
        val car=VehicleState(fuelPercent=60,fuelUpdatedAt=1,batteryPercent=80,batteryUpdatedAt=100000,rangeKm=300.0,rangeUpdatedAt=100000)
        val e=journeyEstimate(WorkState(demoMode=false),car,100001)
        assertNull(e.fuelPercent);assertEquals(80,e.batteryPercent);assertEquals(300.0,e.rangeKm!!,.001)
    }
    @Test fun partialBundleCanReturnForRemainingParcels() {
        var s=WorkState(phase="arrival")
        val first=s.stopParcels.first()
        s=WorkReducer.saveBundle(s,setOf(first.id),false,true)
        s=JourneyReducer.enterField(s,true)
        s=WorkReducer.record(s,first.id,"done",true,"",true)
        s=JourneyReducer.review(s,true)
        assertEquals(s,WorkReducer.nextStop(s,true))
        s=WorkReducer.saveBundle(s,s.stopParcels.filter {!it.pickup && it.status=="pending"}.map {it.id}.toSet(),false,true)
        assertEquals(11,s.bundle.size)
        s=JourneyReducer.enterField(s,true)
        s.stopParcels.filter {it.status=="pending"}.forEach {s=WorkReducer.record(s,it.id,"done",true,"",true)}
        assertTrue(s.stopResolved)
        assertEquals(1,WorkReducer.nextStop(JourneyReducer.review(s,true),true).stopIndex)
    }
    @Test fun restPausesWorkAndUpdatesDemoScheduleOnResume() {
        val s=WorkReducer.toggleRest(WorkState(phase="field"),1000,true)
        assertEquals(s,WorkReducer.record(s,s.parcels.first().id,"done",true,"",true))
        val resumed=WorkReducer.toggleRest(s,301000,true)
        assertEquals(s.demoClock+300,resumed.demoClock);assertEquals(300000L,resumed.restMs)
        assertNull(resumed.restStartedAt)
    }
    @Test fun unloadIsIdempotentAndRealArrivalUsesActualClock() {
        val s=WorkState(scenario="freight",phase="arrival",dock="07")
        val unloaded=WorkReducer.freight(s,"unload",true,true)
        assertEquals(unloaded,WorkReducer.freight(unloaded,"unload",true,true))
        val real=JourneyReducer.arrive(WorkState(demoMode=false,phase="transit"),true,14*3600)
        assertEquals(14*3600,scheduleEstimates(real,VehicleState(),0,15*3600)[0])
        assertEquals(0,journeyEstimate(real,VehicleState(),0).seconds)
    }
    @Test fun fieldListFollowsBundleVisitOrder() {
        val s=WorkState(phase="field")
        assertEquals(s.fieldItems.map {it.id}.reversed(),s.copy(descending=true).fieldItems.map {it.id})
        val one=s.parcels.first().id
        assertEquals(3,s.copy(bundle=setOf(one)).fieldItems.size)
    }
}
