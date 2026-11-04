package com.minwook.pleoswork

/** Read-only story snapshots. No repository, gateway, native route or vehicle writes. */
data class FlowPreviewNotice(val title: String, val body: String)
data class FlowPreviewScene(
    val label: String,
    val state: CompanionState,
    val durationMs: Long = 6_000,
    val moving: Boolean = false,
    val notice: FlowPreviewNotice? = null,
)

object FlowPreview {
    // Only the isolated story uses this parked fixture. The screen also checks the real vehicle.
    private val parked = VehicleState(automotive = true, uxRestricted = false, gear = 4, speedKph = 0f, parkingBrake = true)

    fun stateAt(scene: FlowPreviewScene, elapsedMs: Long): CompanionState {
        if (!scene.moving) return scene.state
        var state = scene.state
        var elapsed = 0L
        val target = elapsedMs.coerceIn(0, 18_000)
        val start = state.run!!.guidance!!.startedAt
        while (elapsed < target) {
            val delta = minOf(1_500, target - elapsed)
            elapsed += delta
            state = GuidanceFlow.tick(state, parked, start + elapsed, delta)
        }
        return state
    }

    fun scenes(mode: ServiceMode, now: Long): List<FlowPreviewScene> {
        val driver = demoDrivers.first { it.mode == mode && it.dispatch != null }
        var state = CompanionState(
            link = DriverLink(phase = LinkPhase.ACTIVE, activeId = driver.id),
            run = DemoWorkflow.create(driver, now), guidanceMode = GuidanceMode.DEMO,
            targetTemperature = if (driver.refrigerated) -18 else 4,
        )
        val scenes = mutableListOf<FlowPreviewScene>()
        var clock = now
        fun add(label: String, duration: Long = 6_000, moving: Boolean = false, notice: FlowPreviewNotice? = null) {
            scenes += FlowPreviewScene(label, state, duration, moving, notice)
            clock += duration
        }
        fun terminal(kind: TerminalKind) {
            state = DemoWorkflow.terminal(state, state.run!!.event(kind), true, clock)
        }
        fun travel(label: String) {
            state = GuidanceFlow.start(state, true, clock)
            add(label, 18_000, moving = true)
            state = stateAt(scenes.last(), 18_000)
        }

        add("출발 준비")
        repeat(state.run!!.plan.stops.size) { index ->
            travel(if (index == 0) "첫 목적지로 이동" else "다음 방문지로 이동")
            val stop = state.run!!.stop
            add("자동 도착", notice = FlowPreviewNotice("목적지에 도착했습니다", stop.name))
            if (mode == ServiceMode.FREIGHT) {
                terminal(TerminalKind.DOCK)
                add("도크 배정", notice = FlowPreviewNotice("${state.run!!.result.dock} 배정", "${stop.operation} 준비 · ${stop.access}"))
            }
            add(if (mode == ServiceMode.PARCEL) "물품 준비·단말 작업" else "${stop.operation} 작업 중")
            terminal(TerminalKind.RESULT)
            val r = state.run!!
            add("단말 결과 수신", notice = FlowPreviewNotice(
                if (mode == ServiceMode.PARCEL) "배송·회수 결과를 받았습니다" else "${stop.operation} 수량을 받았습니다",
                if (mode == ServiceMode.PARCEL) "배송 ${r.result.deliveries}개 · 회수 ${r.result.pickups}건"
                else "${r.result.units}${stop.freight!!.unit}${if (stop.operation == "하차") " · 인수 확인 대기" else " · 출발 준비 확인"}",
            ))
            if (mode == ServiceMode.FREIGHT && stop.operation == "하차") {
                terminal(TerminalKind.RECEIPT)
                add("인수 확인", notice = FlowPreviewNotice("인수 확인이 완료됐습니다", "단말 확인 수신 · 다음 방문지 준비"))
            }
            if (mode == ServiceMode.FREIGHT && stop.operation != "하차") {
                state = DemoWorkflow.advance(state, true)
                add("출발 준비 확인", notice = FlowPreviewNotice("출발 준비", "적재 고정·문·서류를 확인하는 단계입니다"))
            }
            state = GuidanceFlow.next(state, true, clock)
        }
        travel("센터로 복귀")
        add("센터 도착", notice = FlowPreviewNotice("센터에 도착했습니다", "회수품·남은 물품 인계"))
        terminal(TerminalKind.HANDOFF)
        add("반납·인계 확인", notice = FlowPreviewNotice("센터 인계 확인", "단말에서 반납·인계를 확인했습니다"))
        state = DemoWorkflow.advance(state, true)
        add("운행 종료", notice = FlowPreviewNotice("오늘 운행을 마쳤습니다", "모든 방문지와 센터 인계 완료 · 시뮬레이션"))
        return scenes
    }
}
