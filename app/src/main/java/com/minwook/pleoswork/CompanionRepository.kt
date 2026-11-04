package com.minwook.pleoswork

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CompanionRepository(context: Context) {
    // Keep old demo delivery records intact, but do not load them into the vehicle companion.
    private val prefs = context.getSharedPreferences("vehicle-site-v2", Context.MODE_PRIVATE)
    private val restored = DemoRunCodec.decode(prefs.getString("workflow-v1", null), System.currentTimeMillis())
    private val mutable = MutableStateFlow(CompanionState(
        demo = true,
        terminalConnected = if (restored != null) prefs.getBoolean("terminalConnected", true) else true,
        link = restored?.let { DriverLink(phase = LinkPhase.ACTIVE, activeId = it.driverId) }
            ?: DriverLinkReducer.renew(LinkChallenge.create(SystemClock.elapsedRealtime())),
        run = restored,
        guidanceMode = GuidanceMode.entries.firstOrNull { it.name == prefs.getString("guidanceMode", "DEMO") } ?: GuidanceMode.DEMO,
        entranceIndex = 0, // Only the ground/truck entrance is eligible in the supplied examples.
        bayIndex = 0,
        parking = ParkingExample.entries.getOrElse(prefs.getInt("parking", 1)) { ParkingExample.RAMP },
        cargoLocked = prefs.getBoolean("cargoLocked", true) && !prefs.getBoolean("cargoOpen", false),
        cargoLight = prefs.getBoolean("cargoLight", false),
        cargoOpen = prefs.getBoolean("cargoOpen", false),
        coolingOn = prefs.getBoolean("coolingOn", true),
        targetTemperature = prefs.getInt("targetTemperature", 4).takeIf { it in setOf(-18, 4, 8) } ?: 4,
    ))
    val state = mutable.asStateFlow()

    @Synchronized fun update(change: (CompanionState) -> CompanionState) {
        val old = mutable.value
        var next = change(old)
        val now = System.currentTimeMillis()
        if (next.driver == null) next = next.copy(run = null)
        else if (next.run?.driverId != next.driver?.id) {
            next = next.copy(run = DemoWorkflow.create(next.driver!!, now),
                targetTemperature = if (next.driver!!.refrigerated) -18 else next.targetTemperature)
        }
        if (next.terminalConnected && !old.terminalConnected) next = DemoWorkflow.connection(next, true, true, now)
        if (next.cargoOpen != old.cargoOpen) next = next.copy(run = next.run?.copy(doorOpenedAt = if (next.cargoOpen) now else null))
        if (next == mutable.value) return
        prefs.edit().putBoolean("demo", next.demo)
            .putString("guidanceMode", next.guidanceMode.name)
            .putBoolean("terminalConnected", next.terminalConnected)
            .putInt("parking", next.parking.ordinal)
            .putBoolean("cargoLocked", next.cargoLocked).putBoolean("cargoLight", next.cargoLight)
            .putBoolean("cargoOpen", next.cargoOpen).putBoolean("coolingOn", next.coolingOn)
            .putInt("targetTemperature", next.targetTemperature)
            .putString("workflow-v1", next.run?.let(DemoRunCodec::encode)).apply()
        mutable.value = next
    }

    fun newLink(parked: Boolean) {
        if (!parked) return
        update { it.copy(link = DriverLinkReducer.renew(LinkChallenge.create(SystemClock.elapsedRealtime())),
            run = null, entranceIndex = 0, bayIndex = 0, trafficDelayMinutes = 0, terminalConnected = true, demo = true) }
    }

    fun restart(parked: Boolean) {
        if (!parked) return
        update { s -> s.copy(run = s.driver?.let { DemoWorkflow.create(it, System.currentTimeMillis()) },
            entranceIndex = 0, bayIndex = 0, trafficDelayMinutes = 0, terminalConnected = true,
            cargoOpen = false, cargoLocked = true, coolingOn = true, targetTemperature = if (s.refrigerated) -18 else 4) }
    }
}
