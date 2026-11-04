package com.minwook.pleoswork

import kotlin.math.abs

/** A deliberate association, not a name-only guess between fictional dispatch and native routing. */
data class NavigationBinding(val stopId: String, val target: NavTarget, val boundAt: Long)
data class NavArrival(val target: NavTarget, val receivedAt: Long) {
    fun fresh(now: Long) = now - receivedAt in 0..90_000
}

fun NavTarget.valid() = name.isNotBlank() && latitude.isFinite() && longitude.isFinite() &&
    latitude in -90.0..90.0 && longitude in -180.0..180.0

fun NavTarget.samePlace(other: NavTarget) = valid() && other.valid() && name == other.name &&
    abs(latitude - other.latitude) < .0001 && abs(longitude - other.longitude) < .0001 &&
    (poiId.isBlank() || other.poiId.isBlank() || poiId == other.poiId)

// Returning is a separate visit, even though the terminal keeps the last stop's result.
val DemoRun.navigationVisitId: String get() = if (returning) "return:$driverId" else stop.id
val DemoRun.visitName: String get() = if (returning) "${profile.carrier} 복귀센터" else stop.name
val DemoRun.activeNavigationBinding: NavigationBinding?
    get() = navigationBinding?.takeIf { it.stopId == navigationVisitId }
val DemoRun.canConnectNavigation: Boolean get() = !dayEnded && !resting &&
    stage in setOf(RunStage.PREPARING, RunStage.TRAVEL, RunStage.RETURN_TRAVEL)

fun linkedNavigation(run: DemoRun, reading: NavReading?, now: Long): NavReading? {
    val binding = run.activeNavigationBinding ?: return null
    return reading?.takeIf { it.fresh(now) && it.target.samePlace(binding.target) }
}

/** The SDK arrival event has no coordinates; require a recently received, matching destination. */
fun navigationArrival(reading: NavReading?, name: String, address: String, now: Long): NavArrival? =
    reading?.takeIf { it.fresh(now) && it.target.valid() && it.target.name == name &&
        address.isNotBlank() && it.target.address == address }?.let { NavArrival(it.target, now) }
