package com.minwook.pleoswork

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.RectangleShape
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

@Composable
fun ConnectedVehicleApp(s: CompanionState, car: VehicleState, repo: CompanionRepository, gateway: PleosGateway) {
    if (!s.demo || s.run == null) { LegacyConnectedVehicleApp(s, car, repo, gateway); return }
    val r = s.run
    val journeyAction = InteractiveJourney.action(s)
    var dismissedNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val notice = journeyNotice(s)?.takeUnless { it.id == dismissedNotice }
    var panel by rememberSaveable { mutableStateOf("current") }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    val activePreview = r.previewVisit(preview)
    var compact by rememberSaveable { mutableStateOf(true) }
    var largeText by rememberSaveable { mutableStateOf(false) }
    var reset by rememberSaveable { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // A service callback can be newer than the last one-second UI tick.
    val now = maxOf(clockTick, System.currentTimeMillis())
    val focus = remember { WorkFocus() }
    val input = LocalInputModeManager.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val history = remember { mutableListOf<Triple<String, String?, String?>>() }
    fun open(value: String) {
        keyboard?.hide()
        val next = value
        if (next != panel) history.add(Triple(panel, preview, focus.current))
        panel = next; preview = null; focus.moveTo("detail/back")
    }
    fun current() { keyboard?.hide(); history.clear(); panel = "current"; preview = null; focus.moveTo("detail/primary") }
    fun chooseVisit(id: String) {
        val selected = r.previewVisit(id)
        if (selected == null) current()
        else {
            if (panel != "current") history.add(Triple(panel, preview, focus.current))
            preview = selected; panel = "current"; focus.moveTo("detail/preview-return")
        }
    }
    fun back() {
        keyboard?.hide()
        val previous = history.removeLastOrNull()
        panel = previous?.first ?: "current"; preview = previous?.second
        focus.moveTo(previous?.third ?: "detail/primary")
    }
    val configuration = LocalConfiguration.current
    val singleWindow = configuration.screenWidthDp < configuration.screenHeightDp
    // Keep one live map mounted while only the right-hand work panel changes.
    val sideNavigation = configuration.screenWidthDp >= 1150 && s.guidanceMode == GuidanceMode.DEMO
    fun showMap() {
        compact = true
        gateway.openNavigation()
    }
    // requestRoute already opens native guidance. Repeating Foreground for every visit
    // swaps the user's two-pane arrangement and steals focus from the work controls.
    LaunchedEffect(Unit) { while (true) { delay(1000); clockTick = System.currentTimeMillis() } }
    LaunchedEffect(clockTick) {
        if (s.guidanceMode == GuidanceMode.DEMO && lifecycleState.isAtLeast(Lifecycle.State.STARTED)) repo.update {
            GuidanceFlow.tick(it, car, now, if (it.run?.liveVisitVisible(panel, preview, sideNavigation) == true) 1000 else 0)
        }
    }
    LaunchedEffect(car.navigation, car.navigationArrival, car.canWork, car.routeStatus) {
        if (s.guidanceMode == GuidanceMode.DEMO && lifecycleState.isAtLeast(Lifecycle.State.STARTED))
            repo.update { GuidanceFlow.tick(it, car, now, 0) }
    }
    LaunchedEffect(r.navigationVisitId, preview) {
        if (preview != activePreview) preview = activePreview
    }
    // Only the fictional dock response is automatic. Work, receipt and handoff wait for the driver.
    LaunchedEffect(journeyAction, panel, preview, sideNavigation, lifecycleState, car.canWork, s.terminalConnected) {
        if (journeyAction?.kind == JourneyActionKind.DOCK && s.terminalConnected && car.canWork &&
            r.liveVisitVisible(panel, preview, sideNavigation) && lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
            delay(3_000)
            repo.update { InteractiveJourney.perform(it, journeyAction, car.canWork, System.currentTimeMillis()) }
        }
    }
    LaunchedEffect(focus.pending) {
        withFrameNanos { }
        if (input.inputMode == InputMode.Keyboard) focus.pending?.second?.let { focus.restore(it) }
    }
    BackHandler(panel != "current" || preview != null) { back() }
    fun send(kind: TerminalKind, partial: Boolean = false, reason: String = "") {
        focusManager.clearFocus(); keyboard?.hide()
        repo.update { DemoWorkflow.terminal(it, r.event(kind, partial, reason), car.canWork, now) }
        if (s.terminalConnected) current() else focus.moveTo("detail/terminal-connection")
    }
    fun sendParcel(deliveries: Int, pickups: Int, reason: String?) {
        val event = r.event(if (reason == null) TerminalKind.RESULT else TerminalKind.DEFER, reason = reason.orEmpty())
            .copy(deliveries = deliveries, pickups = pickups)
        repo.update { DemoWorkflow.terminal(it, event, car.canWork, now) }
        current()
    }
    fun primary() {
        when {
            r.dayEnded -> repo.update { DemoWorkflow.endDay(it, car.canWork) }
            r.resting -> repo.update { DemoWorkflow.rest(it, car.canWork) }
            r.pending.isNotEmpty() -> open("terminal")
            r.stage == RunStage.ENDED -> reset = true
            s.cargoOpen && r.ready && r.stage in setOf(RunStage.REVIEW, RunStage.DEPARTURE) -> open("terminal")
            journeyAction != null -> {
                repo.update { InteractiveJourney.perform(it, journeyAction, car.canWork, now) }
                if (journeyAction.kind in setOf(JourneyActionKind.TERMINAL, JourneyActionKind.REVIEW_RESULTS)) {
                    open("handheld"); focus.moveTo("detail/terminal-send")
                } else current()
            }
            r.stage == RunStage.ARRIVED -> { repo.update { DemoWorkflow.atSite(it, car.canWork) }; current() }
            r.stage in setOf(RunStage.PREPARING, RunStage.TRAVEL, RunStage.RETURN_TRAVEL) -> {
                if (r.guidance?.mode == GuidanceMode.DEMO && r.guidance?.status == GuidanceStatus.ACTIVE)
                    repo.update { GuidanceFlow.pause(it, car.canWork) }
                else if (r.guidance?.mode == GuidanceMode.NATIVE_SAMPLE && r.guidance?.status == GuidanceStatus.ACTIVE) open("terminal")
                else if (r.guidance?.mode == GuidanceMode.NATIVE_SAMPLE && r.guidance?.status == GuidanceStatus.REQUESTED) open("navigation")
                else { repo.update { GuidanceFlow.start(it, car.canWork, now) }; current() }
            }
            r.stage in setOf(RunStage.WAITING, RunStage.WORKING) -> open("terminal")
            r.stage == RunStage.REVIEW && !r.ready -> open("terminal")
            r.stage == RunStage.RETURNED && !r.handedOver -> open("terminal")
            s.cargoOpen && r.stage in setOf(RunStage.PREPARING, RunStage.DEPARTURE, RunStage.REVIEW) -> open("terminal")
            else -> { repo.update { GuidanceFlow.next(it, car.canWork, now) }; current() }
        }
    }
    CompositionLocalProvider(LocalWorkFocus provides focus, LocalDensity provides Density(density.density, if (largeText) 1.3f else density.fontScale)) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints {
                val demoWide = sideNavigation
                val wide = !demoWide && maxWidth >= 1300.dp && !compact
                Column(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalFocusScope provides "toolbar") {
                        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 8.dp)
                            .semantics { isTraversalGroup = true; traversalIndex = 0f }, verticalAlignment = Alignment.CenterVertically) {
                            IconAction(R.drawable.ic_mobile, "기사·차량 연결", focusKey = "link", isSelected = panel == "link") { open("link") }
                            Text(s.driver?.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (!wide) IconAction(R.drawable.ic_list, "전체 방문 순서", isSelected = panel == "itinerary") { open("itinerary") }
                            IconAction(R.drawable.ic_location, if (s.guidanceMode == GuidanceMode.NATIVE_SAMPLE) "기본 내비와 함께 보기" else "목적지 안내 설정", focusKey = "navigation", enabled = car.canWork) {
                                if (s.guidanceMode == GuidanceMode.NATIVE_SAMPLE) showMap() else open("navigation")
                            }
                            IconAction(R.drawable.ic_settings, "화면·연결 설정", focusKey = "settings", isSelected = panel == "settings") { open("settings") }
                        }
                    }
                    Divider()
                    if (!singleWindow && s.guidanceMode == GuidanceMode.NATIVE_SAMPLE && r.showNavigation && panel == "current" && preview == null)
                        WorkflowNotice("오른쪽 창 막대를 왼쪽으로 밀면 기본 내비와 나란히 볼 수 있습니다")
                    val notices = s.vehicleNotices(car)
                    if (notices.isNotEmpty()) Column(Modifier.fillMaxWidth().semantics {
                        isTraversalGroup = true; traversalIndex = .5f
                    }) {
                        notices.forEach { notice -> key(notice.id) { WorkflowNotice(notice.message, true) } }
                    }
                    if (!car.canWork) WorkflowNotice("정차 후 P에서 조작할 수 있습니다", true)
                    if (!s.terminalConnected || r.pending.isNotEmpty() || r.endPending) WorkflowNotice(
                        "${if (!s.terminalConnected) "단말 연결 끊김 · 마지막 수신 ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(r.lastReceivedAt))}" else "전송 확인 중"}${if (r.pending.isNotEmpty()) " · ${r.pending.size}건 대기" else ""}${if (r.endPending) " · 종료 전송 대기" else ""}", true)
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (demoWide) {
                            JourneyNavigationPane(s, notice, Modifier.weight(.56f).fillMaxHeight()) {
                                dismissedNotice = notice?.id
                                focus.moveTo(when {
                                    panel == "current" && activePreview != null -> "detail/preview-return"
                                    panel == "current" -> "detail/primary"
                                    panel == "handheld" -> "detail/terminal-send"
                                    else -> "detail/back"
                                })
                            }
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        } else if (wide) {
                            Box(Modifier.weight(.34f).fillMaxHeight().semantics { isTraversalGroup = true; traversalIndex = 1f }) {
                                Itinerary(s, car.canWork, activePreview, ::chooseVisit)
                            }
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        CompositionLocalProvider(LocalFocusScope provides "detail") {
                            val title = when (panel) { "current" -> if (activePreview != null) "방문지 미리보기" else "${r.visitName} · ${r.stage.label}"; "handheld" -> "기사 단말 · 체험"; "settings" -> "화면·연결 설정"; "api" -> "차량 API·데모 설정"; "navigation" -> "목적지 안내 설정"; "terminal" -> "단말·상황 체험"; "itinerary" -> "전체 방문 순서"; "site" -> "진입·정차"; else -> "기사·차량 연결" }
                            Column(Modifier.weight(if (demoWide) .44f else if (wide) .66f else 1f).fillMaxHeight().semantics { isTraversalGroup = true; traversalIndex = 2f; paneTitle = title }) {
                                if (panel == "current") {
                                    // Keep read-only semantics nodes alive across arrival and dock updates.
                                    // Replacing the whole visit makes TalkBack jump back to the toolbar.
                                    key(activePreview, r.navigationVisitId) {
                                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                            val other = r.plan.stops.firstOrNull { it.id == activePreview }
                                            if (other != null) StopPreview(other, r.results[other.id]) else {
                                                if (!demoWide && s.guidanceMode == GuidanceMode.DEMO)
                                                    JourneyNavigationPane(s, notice, Modifier.fillMaxWidth().height(320.dp), compact = true) {
                                                        dismissedNotice = notice?.id; focus.moveTo("detail/primary")
                                                    }
                                                GuidedCurrentStop(s, car, now, ::open)
                                            }
                                        }
                                    }
                                    Divider()
                                    Row(Modifier.fillMaxWidth().padding(WorkDimens.inset), horizontalArrangement = Arrangement.spacedBy(WorkDimens.gap)) {
                                        if (activePreview != null) ActionButton("현재 정차로 돌아가기", Modifier.fillMaxWidth(), focusKey = "preview-return", onClick = ::current)
                                        else {
                                            ActionButton(primaryLabel(s), Modifier.fillMaxWidth(), enabled = car.canWork, focusKey = "primary",
                                                icon = when {
                                                    r.showNavigation && r.guidance?.mode == GuidanceMode.DEMO && r.guidance?.status == GuidanceStatus.ACTIVE ->
                                                        if (r.guidance!!.paused) R.drawable.ic_play else R.drawable.ic_pause
                                                    journeyAction?.kind in setOf(JourneyActionKind.RECEIPT, JourneyActionKind.HANDOFF) -> R.drawable.ic_document
                                                    journeyAction?.kind in setOf(JourneyActionKind.TERMINAL, JourneyActionKind.REVIEW_RESULTS) -> R.drawable.ic_mobile
                                                    journeyAction?.kind == JourneyActionKind.REBOARD -> R.drawable.ic_car_front
                                                    !r.ready && r.stage == RunStage.REVIEW && journeyAction == null -> R.drawable.ic_mobile
                                                    else -> R.drawable.ic_arrow_right
                                                }, onClick = ::primary)
                                        }
                                    }
                                } else if (panel == "handheld" && r.stop.parcel != null) {
                                    SectionHeader("기사 단말 · 체험", ::back, "차량 화면으로 돌아가기")
                                    Divider()
                                    ParcelTerminalPanel(s, car, Modifier.weight(1f), ::sendParcel)
                                } else if (panel == "settings") {
                                    SectionHeader("화면·연결 설정", ::back, "이전 화면으로 돌아가기")
                                    Divider()
                                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        if (s.guidanceMode != GuidanceMode.DEMO)
                                            ActionButton(if (compact) "두 칸 화면으로" else "한 칸 화면 체험", Modifier.fillMaxWidth(), secondary = true, icon = R.drawable.ic_expand, focusKey = "layout") { compact = !compact }
                                        ActionButton(if (largeText) "기본 글자 크기" else "큰 글자 체험 · 130%", Modifier.fillMaxWidth(), secondary = true, icon = R.drawable.ic_document, focusKey = "font-size") { largeText = !largeText }
                                        ActionButton("차량 API·데모 설정", Modifier.fillMaxWidth(), secondary = true, icon = R.drawable.ic_settings) { open("api") }
                                        ActionButton("목적지·자동 도착 설정", Modifier.fillMaxWidth(), secondary = true, icon = R.drawable.ic_location) { open("navigation") }
                                        ActionButton("진입·정차 상세", Modifier.fillMaxWidth(), secondary = true, icon = R.drawable.ic_truck) { open("site") }
                                        ActionButton("단말·상황 체험", Modifier.fillMaxWidth(), secondary = true, enabled = car.canWork, icon = R.drawable.ic_play) { open("terminal") }
                                        ActionButton("처음부터 다시 체험", Modifier.fillMaxWidth(), secondary = true, enabled = car.canWork, icon = R.drawable.ic_reset) { reset = true }
                                        WorkflowFact("마지막 단말 수신", java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(r.lastReceivedAt)))
                                        Text("QR·배차·처리 결과·적재함 제어는 로컬 데모입니다. 기어·속도·운전 제한과 내비 수신은 Pleos API를 사용합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else if (panel == "api") CompanionSettings(s, car, repo, gateway, now, ::back)
                                else {
                                    SectionHeader(title, ::back, "이전 화면으로 돌아가기")
                                    Divider()
                                    if (panel == "itinerary") Itinerary(s, car.canWork, activePreview, ::chooseVisit)
                                    else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                        when (panel) {
                                            "terminal" -> TerminalSimulator(s, car, repo, now, ::send, ::current)
                                            "navigation" -> GuidanceSettings(s, car, repo, gateway, now, ::current)
                                            "link" -> DriverConnectionPanel(s, car, repo)
                                            "site" -> SiteAccessPanel(s, car, repo)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            fun dismissReset() { reset = false; focus.moveTo(if (panel == "settings") "detail/처음부터 다시 체험" else "detail/primary") }
            if (reset) AlertDialog(onDismissRequest = ::dismissReset, shape = RectangleShape,
                containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                titleContentColor = MaterialTheme.colorScheme.onSurface, textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                title = { Text("이 데모를 처음부터 시작할까요?") },
                text = { Text("현재 기사님의 예시 진행 상태와 전송 대기 결과를 초기화합니다.") },
                confirmButton = { QuietButton(onClick = { repo.restart(car.canWork); reset = false; current() }, enabled = car.canWork) { Text("처음부터 시작") } },
                dismissButton = { QuietButton(onClick = ::dismissReset) { Text("취소") } })
        }
    }
}

private fun primaryLabel(s: CompanionState): String {
    val r = s.run!!
    if (r.dayEnded) return "다음 날 이어가기"
    if (r.resting) return "휴식 후 이어가기"
    if (r.pending.isNotEmpty()) return "단말 전송 대기 확인"
    if (s.cargoOpen && r.ready && r.stage in setOf(RunStage.REVIEW, RunStage.DEPARTURE)) return "적재함 문 확인"
    InteractiveJourney.action(s)?.let { return it.label }
    if (r.showNavigation) return when {
        r.guidance?.status == GuidanceStatus.FAILED -> "안내 다시 시작"
        r.guidance?.status == GuidanceStatus.ACTIVE && r.guidance?.mode == GuidanceMode.DEMO -> if (r.guidance!!.paused) "안내 계속" else "안내 일시정지"
        r.guidance?.status == GuidanceStatus.ACTIVE -> if (r.returning) "센터 도착 체험" else if (s.mode == ServiceMode.FREIGHT) "도크 배정 확인" else "단말 처리 체험"
        r.guidance?.status == GuidanceStatus.REQUESTED -> "내비 연결 확인"
        else -> "안내 시작"
    }
    return when (r.stage) {
        RunStage.PREPARING, RunStage.TRAVEL, RunStage.ARRIVED -> "현장 도착"
        RunStage.RETURN_TRAVEL -> "센터 도착"
        RunStage.WAITING -> "도크 배정 확인"
        RunStage.WORKING -> "단말 처리 체험"
        RunStage.REVIEW -> when {
            !r.ready -> if (r.stop.operation == "하차" && r.result.units == r.stop.freight?.units) "인수 확인" else "미처리 확인"
            s.cargoOpen -> "적재함 문 확인"
            r.stop.freight != null && r.stop.operation != "하차" -> "상차 후 출발 준비"
            r.index == r.plan.stops.lastIndex -> "센터로 복귀"
            else -> "다음 목적지 안내"
        }
        RunStage.DEPARTURE -> if (s.cargoOpen) "적재함 문 확인" else if (r.index == r.plan.stops.lastIndex) "센터로 복귀" else "다음 목적지 안내"
        RunStage.RETURNED -> if (r.handedOver) "운행 종료" else "복귀 인계 확인"
        RunStage.ENDED -> "처음부터 다시 체험"
    }
}

@Composable private fun Itinerary(s: CompanionState, enabled: Boolean, preview: String?, choose: (String) -> Unit) {
    val r = s.run!!
    CompositionLocalProvider(LocalFocusScope provides "route") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("방문 순서", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("${r.plan.id} · ${r.plan.stops.size}곳", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.plan.stops.forEachIndexed { index, stop ->
                val value = r.results[stop.id]
                val stage = if (index < r.index || r.returning) if (value?.reason?.isNotBlank() == true) "사유 인계" else "처리됨" else if (index == r.index) "현재" else "예정"
                val selected = stop.id == (preview ?: r.stop.id)
                Row(Modifier.fillMaxWidth().heightIn(min = 108.dp).background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .carFocusOutline("stop-${stop.id}").selectable(selected, enabled = enabled, role = Role.RadioButton) { choose(stop.id) }
                    .semantics(mergeDescendants = true) { stateDescription = stage }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(44.dp).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${index + 1}. ${stop.name}", style = MaterialTheme.typography.titleMedium)
                        Text("${stop.window.label} · $stage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Divider()
            Text("${if (r.returning) "현재 · " else "마지막 · "}센터 복귀·인계", style = MaterialTheme.typography.bodyLarge)
            if (r.restMinutes > 0) Text("휴식 ${r.restMinutes}분", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun StopPreview(stop: DispatchStop, result: StopResult?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WorkDimens.sectionGap)) {
        VisitHeading(stop.name, "방문지 미리보기", "예약 ${stop.window.label}")
        PackageSummary(stop)
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            WorkflowFact("적재 위치", stop.parcel?.zones?.joinToString(" · ") { "${it.name} ${it.units}개" } ?: stop.freight?.location.orEmpty())
            WorkflowFact("진입", stop.access)
            stop.freight?.let { WorkflowFact("장비·요구 온도", "${it.equipment} · ${it.temperature?.label ?: "지정 없음"}") }
        }
        result?.let { WorkflowFact("받은 결과", if (stop.parcel != null) "배송 ${it.deliveries}개 · 회수 ${it.pickups}건" else "${it.units}${stop.freight?.unit}") }
        result?.reason?.takeIf { it.isNotBlank() }?.let { WorkflowFact("인계 사유", it) }
        Text(stop.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun WorkflowFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
@Composable private fun WorkflowNotice(message: String, warning: Boolean = false) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CarbonIcon(if (warning) R.drawable.ic_warning else R.drawable.ic_info, null)
        Text(message, Modifier.weight(1f), color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun TerminalSimulator(s: CompanionState, car: VehicleState, repo: CompanionRepository, now: Long,
    send: (TerminalKind, Boolean, String) -> Unit, close: () -> Unit) {
    val r = s.run!!
    var reason by rememberSaveable(r.stop.id) { mutableStateOf("고객 부재 · 미배송 반납, 회수 재방문 인계") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val enabled = car.canWork && !r.dayEnded && !r.resting
    WorkflowFact("${s.driver?.name} 단말 · 로컬 데모", if (r.returning) "복귀센터" else r.stop.name)
    if (r.pending.isNotEmpty()) WorkflowNotice("${r.pending.size}건 전송 대기 · 재연결하면 한 번만 반영됩니다.")
    if (r.dayEnded) ActionButton("다음 날 이어가기", Modifier.fillMaxWidth(), enabled = car.canWork) { repo.update { DemoWorkflow.endDay(it, car.canWork) }; close() }
    else if (r.resting) ActionButton("20분 휴식 후 이어가기 · 체험", Modifier.fillMaxWidth(), enabled = car.canWork) { repo.update { DemoWorkflow.rest(it, car.canWork) }; close() }
    else when (r.stage) {
        RunStage.PREPARING, RunStage.TRAVEL, RunStage.ARRIVED -> {
            ActionButton(if (s.mode == ServiceMode.PARCEL) "배송·회수 전량 처리 전송" else "도크 배정 수신", Modifier.fillMaxWidth(),
                enabled = enabled && r.pending.isEmpty(), icon = R.drawable.ic_mobile) {
                send(if (s.mode == ServiceMode.PARCEL) TerminalKind.RESULT else TerminalKind.DOCK, false, "")
            }
            ActionButton("현장 도착 반영", Modifier.fillMaxWidth(), secondary = true, enabled = enabled && r.pending.isEmpty(), icon = R.drawable.ic_location) {
                repo.update { DemoWorkflow.atSite(it, car.canWork) }; close()
            }
            if (r.stage == RunStage.TRAVEL) ActionButton("휴식 시작 · 체험", Modifier.fillMaxWidth(), secondary = true, enabled = enabled, icon = R.drawable.ic_pause) { repo.update { DemoWorkflow.rest(it, car.canWork) }; close() }
        }
        RunStage.RETURN_TRAVEL -> {
            ActionButton("센터 도착 반영", Modifier.fillMaxWidth(), enabled = enabled, icon = R.drawable.ic_location) { repo.update { DemoWorkflow.atSite(it, car.canWork) }; close() }
            ActionButton("휴식 시작 · 체험", Modifier.fillMaxWidth(), secondary = true, enabled = enabled, icon = R.drawable.ic_pause) { repo.update { DemoWorkflow.rest(it, car.canWork) }; close() }
        }
        RunStage.WAITING -> {
            if (r.minute < r.stop.window.startMinute) Text("예약 시각 ${r.stop.window.label}까지의 대기를 건너뛰어 체험합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton("도크 배정 수신", Modifier.fillMaxWidth(), enabled = enabled && r.pending.isEmpty(), icon = R.drawable.ic_truck) { send(TerminalKind.DOCK, false, "") }
        }
        RunStage.WORKING, RunStage.REVIEW -> {
            val awaitingReceipt = r.stage == RunStage.REVIEW && r.stop.operation == "하차" && r.result.units == r.stop.freight?.units
            ActionButton(if (r.stop.parcel != null) "배송·회수 전량 처리 전송" else "${r.stop.operation} 전량 처리 전송", Modifier.fillMaxWidth(), secondary = awaitingReceipt, enabled = enabled && r.pending.isEmpty()) { send(TerminalKind.RESULT, false, "") }
            ActionButton(if (r.stop.parcel != null) if (r.stop.parcel!!.pickups > 0) "미배송·미회수 1건씩 남기기" else "미배송 1개 남기기" else "수량 1개 부족하게 전송", Modifier.fillMaxWidth(), secondary = true, enabled = enabled && r.pending.isEmpty(), icon = R.drawable.ic_warning) { send(TerminalKind.RESULT, true, "") }
            if (awaitingReceipt) {
                ActionButton("인수 확인 전송", Modifier.fillMaxWidth(), enabled = enabled && r.pending.isEmpty(), icon = R.drawable.ic_document) { send(TerminalKind.RECEIPT, false, "") }
            }
            if (r.stage == RunStage.REVIEW && r.stop.parcel != null && r.missingDeliveries + r.missingPickups > 0) {
                OutlinedTextField(reason, { reason = it.take(80) }, Modifier.fillMaxWidth().heightIn(min = 88.dp).carFocusOutline("reason")
                    .onPreviewKeyEvent { key ->
                        if (key.key == Key.Tab && key.type == KeyEventType.KeyDown) {
                            keyboard?.hide(); focusManager.moveFocus(if (key.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true
                        } else false
                    },
                    label = { Text("미처리 인계 사유") }, enabled = enabled, minLines = 2, maxLines = 3)
                ActionButton("사유와 함께 인계", Modifier.fillMaxWidth(), secondary = true, enabled = enabled && reason.trim().length >= 2 && r.pending.isEmpty(), icon = R.drawable.ic_edit) { send(TerminalKind.DEFER, false, reason) }
            }
        }
        RunStage.RETURNED -> ActionButton("반납·인계 확인 전송", Modifier.fillMaxWidth(), enabled = enabled && r.pending.isEmpty(), icon = R.drawable.ic_document) { send(TerminalKind.HANDOFF, false, "") }
        else -> Text("현재 화면의 다음 행동으로 진행하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Divider()
    ActionButton(if (s.terminalConnected) "단말 연결 끊기 · 체험" else "재연결·대기 결과 전송", Modifier.fillMaxWidth(), secondary = true, enabled = car.canWork, icon = R.drawable.ic_mobile, focusKey = "terminal-connection") {
        keyboard?.hide()
        repo.update { DemoWorkflow.connection(it, !it.terminalConnected, car.canWork, now) }
        if (!s.terminalConnected && r.pending.isNotEmpty()) close()
    }
    if (r.stage != RunStage.ENDED) {
        if (s.cargoOpen) ActionButton("적재함 문 닫힘 반영 · 예시", Modifier.fillMaxWidth(), secondary = true,
            enabled = car.canWork, icon = R.drawable.ic_inventory) {
            repo.update { CompanionReducer.door(it, false, car.canWork) }
        }
        ActionButton(if (s.trafficDelayMinutes == 0) "예시 이동 지연 +25분" else "예시 이동 지연 해제", Modifier.fillMaxWidth(), secondary = true, enabled = enabled, icon = R.drawable.ic_time) {
            repo.update { CompanionReducer.traffic(it, it.trafficDelayMinutes == 0, car.canWork) }
        }
        if (s.refrigerated) {
            Text("냉동 센서 상황", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            listOf(SensorExample.NORMAL to "정상 온도", SensorExample.DEVIATION to "온도 이탈", SensorExample.OFFLINE to "센서 끊김").forEach { (value, label) ->
                ActionButton(label, Modifier.fillMaxWidth(), secondary = r.sensor != value, isSelected = r.sensor == value, enabled = car.canWork, icon = R.drawable.ic_temperature) {
                    repo.update { DemoWorkflow.sensor(it, value, car.canWork, now) }
                }
            }
        }
        if (s.mode == ServiceMode.FREIGHT && !r.dayEnded) ActionButton("오늘 운행 마침 · 배차 유지", Modifier.fillMaxWidth(), secondary = true,
            enabled = enabled && r.pending.isEmpty(), icon = R.drawable.ic_pause) { repo.update { DemoWorkflow.endDay(it, car.canWork) }; close() }
    }
}
