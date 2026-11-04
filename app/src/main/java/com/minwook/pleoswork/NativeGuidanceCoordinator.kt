package com.minwook.pleoswork

/** Native events are consumed independently of which pane, window or Activity is visible. */
class NativeGuidanceCoordinator(
    private val work: () -> CompanionState,
    private val update: ((CompanionState) -> CompanionState) -> Unit,
    private val vehicle: () -> VehicleState,
    private val requestDestination: (NavTarget) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun synchronize() {
        val car = vehicle()
        val now = clock()
        if (work().guidanceMode != GuidanceMode.NATIVE_SAMPLE) return
        update { s ->
            val r = s.run
            if (r?.guidance == null) DemoWorkflow.navigationArrived(s, car.navigationArrival, car.canWork, now)
            else GuidanceFlow.tick(s, car, now, 0)
        }
        val s = work()
        val run = s.run ?: return
        val leg = run.guidance ?: return
        if (leg.mode != GuidanceMode.NATIVE_SAMPLE || leg.status != GuidanceStatus.REQUESTED ||
            leg.issuedAt != 0L || !run.canConnectNavigation || run.pending.isNotEmpty() ||
            s.cargoOpen || !car.canWork || car.navigationStatus != "연결됨") return
        val target = leg.target?.takeIf { it.valid() } ?: return
        // Claim the request before the SDK call: synchronous state emissions cannot dispatch twice.
        update { GuidanceFlow.issued(it, leg.id, true, now) }
        if (!requestDestination(target)) update { current ->
            val currentRun = current.run
            val currentLeg = currentRun?.guidance
            if (currentLeg?.id != leg.id || currentLeg.status != GuidanceStatus.REQUESTED) current
            else current.copy(run = currentRun.copy(visitGuidance = currentLeg.copy(
                status = GuidanceStatus.FAILED, message = "내비에 목적지를 전달하지 못했습니다 · 다시 시도하세요")))
        }
    }
}
