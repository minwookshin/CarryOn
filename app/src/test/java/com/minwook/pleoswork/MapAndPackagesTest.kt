package com.minwook.pleoswork

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MapAndPackagesTest {
    @Test fun everyDemoDispatchHasAnExplicitBalancedSizeBreakdown() {
        for (plan in listOf(parcelEastDispatch, parcelWestDispatch, coldFreightDispatch)) {
            for (stop in plan.stops) {
                val counts = stop.parcel?.packages ?: stop.freight!!.packages
                val total = stop.parcel?.deliveries ?: stop.freight!!.units
                assertFalse(counts.isEmpty())
                assertEquals(total, counts.sumOf { it.count })
            }
        }
        assertEquals(PackageKind.CONTAINER, coldFreightDispatch.stops.last().freight!!.packages.single().kind)
    }

    @Test fun totalOnlyManifestDoesNotInventBoxSizes() {
        assertTrue(ParcelLoad(7, 0, listOf(LoadZone("A",7))).packages.isEmpty())
        assertTrue(FreightLoad("물품", 4, "개", 100, "A", "카트").packages.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun inconsistentBreakdownCannotBeRenderedAsTheDispatch() {
        ParcelLoad(4, 0, emptyList(), listOf(PackageCount(PackageKind.LARGE,3)))
    }

    @Test fun movingMarkerFollowsSegmentsAndClampsAtArrival() {
        val points = listOf(DemoMapPoint(0.0,0.0),DemoMapPoint(100.0,0.0),DemoMapPoint(100.0,100.0))
        assertEquals(points.first(), demoMapPosition(points, -1f))
        assertEquals(DemoMapPoint(100.0,50.0), demoMapPosition(points,.75f))
        assertEquals(points.last(), demoMapPosition(points,1f))
        assertEquals(points.last(), demoMapPosition(points,2f))
        assertEquals(points.first(), demoMapPosition(listOf(points.first(),points.first()),.5f))
    }

    @Test fun everyLegHasMapRoadsAndTheNextLegStartsAtThePreviousArrival() {
        val file=File("src/main/res/raw/pangyo_map.json")
        val map=Gson().fromJson(file.readText(),DemoMapData::class.java)
        assertTrue(map.features.count { it.kind=="road" } > 20)
        assertTrue(map.features.any { it.kind=="building" })
        for (count in listOf(2,3)) {
            for (i in 0 until count) {
                val path=map.routes.getValue(demoMapRouteKey(i,false))
                val next=map.routes.getValue(if(i==count-1) demoMapRouteKey(i,true) else demoMapRouteKey(i+1,false))
                assertTrue(path.size>2)
                assertEquals(path.last(),next.first())
                if(i==count-1) assertEquals(map.routes.getValue("0-1").first(),next.last())
            }
        }
    }
}
