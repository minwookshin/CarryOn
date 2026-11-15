package com.minwook.pleoswork

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun timeText(at: Long): String = recordTime(at)
private val workPages = listOf("work", "overview")
private val workPageLabels = listOf("현재 업무", "현재 남은 업무")

@Composable fun OperationsApp(s: BoardState, car: VehicleState, session: WorkSessionViewModel) {
    val pager = rememberPagerState { workPages.size }
    val scope = rememberCoroutineScope()
    var tabChange by remember { mutableStateOf<Job?>(null) }
    var detailPage by rememberSaveable { mutableStateOf<String?>(null) }
    val page = detailPage ?: workPages[pager.settledPage]
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { WorkFocus() }
    val input = LocalInputModeManager.current
    val density = LocalDensity.current
    val ledger = s.ledger
    val workScroll = rememberScrollState()
    val overviewScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    fun selectTab(index: Int) {
        if (index != 0 && !car.canWork) return
        detailPage = null
        tabChange?.cancel()
        tabChange = scope.launch {
            pager.animateScrollToPage(index)
            focus.moveTo("navigation/${workPages[index]}")
        }
    }
    fun closeSettings() { detailPage = null; focus.moveTo("header/settings") }
    fun openSettings() {
        if (!car.canWork) return
        tabChange?.cancel()
        detailPage = "settings"
        focus.moveTo("settings/back")
    }
    fun back() {
        when (detailPage) {
            "mode" -> { detailPage = "settings"; focus.moveTo("settings/mode") }
            "settings" -> closeSettings()
            else -> selectTab(0)
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    LaunchedEffect(ledger?.streamId, ledger?.currentVisitId) { workScroll.scrollTo(0) }
    SideEffect { session.workVisible = ledger?.current != null && (page == "work" || s.navigationMode == GuidanceMode.DEMO) }
    DisposableEffect(Unit) { onDispose { session.workVisible = false } }
    LaunchedEffect(car.canWork) {
        if (!car.canWork) {
            tabChange?.cancel(); detailPage = null
            pager.scrollToPage(0)
            focus.moveTo("navigation/work")
        }
    }
    LaunchedEffect(focus.pending) { withFrameNanos { }; if (input.inputMode == InputMode.Keyboard) focus.pending?.second?.let { focus.restore(it) } }
    BackHandler(page != "work") { back() }
    CompositionLocalProvider(LocalWorkFocus provides focus, LocalDensity provides Density(density.density, if (s.largeText) density.fontScale.coerceAtLeast(1.3f) else density.fontScale)) {
        Surface(Modifier.fillMaxSize()) {
            if (ledger == null) {
                CompositionLocalProvider(LocalFocusScope provides "setup") { BoardSetup(s, car.canWork, false, session::selectMode) }
            } else BoxWithConstraints {
                val mapLayout = workMapLayout(maxWidth.value, s.navigationMode)
                Row(Modifier.fillMaxSize()) {
                    if (mapLayout.beside) {
                        BoardNavigationMap(s, Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control).padding(start = 24.dp, end = 12.dp)
                            .semantics { isTraversalGroup = true; traversalIndex = 0f }, verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
                                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(ledger.driverName, style = MaterialTheme.typography.bodyMedium)
                                Text("|", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                                Text(ledger.serviceLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CompositionLocalProvider(LocalFocusScope provides "header") {
                                IconAction(R.drawable.ic_settings, if (detailPage == null) "설정 열기" else "설정 닫기",
                                    enabled = car.canWork, isSelected = detailPage != null, focusKey = "settings") {
                                    if (detailPage == null) openSettings() else closeSettings()
                                }
                            }
                        }
                        Divider()
                        if (!car.canWork) BoardNotice("주행 중에는 요약을 표시합니다 · 상세는 정차 후 P에서", false)
                        else s.parkingBrakeNotice(car)?.let { BoardNotice(it, true) }
                        if (!s.connected && !ledger.streamId.startsWith("local-")) BoardNotice("${if (s.connectionMessage.isBlank()) "연결 확인 필요" else s.connectionMessage} · 마지막 ${timeText(ledger.lastSyncAt)}", false)
                        if (ledger.refrigerated && ledger.current != null) {
                            val cargo = cargoStatus(ledger, now)
                            if(cargo.exceptional) BoardNotice(cargo.title, cargo.warning)
                        }
                        if (mapLayout.above) BoardNavigationMap(s, Modifier.fillMaxWidth().height(196.dp), compact = true)
                        // View changes never replace the map or the company's active destination.
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (detailPage != null) CompositionLocalProvider(LocalFocusScope provides if(detailPage == "mode") "mode" else "settings") {
                                Column(Modifier.fillMaxSize().semantics { isTraversalGroup = true; traversalIndex = 2f; paneTitle = if(detailPage == "mode") "업무 유형" else "설정" }) {
                                    SectionHeader(if(detailPage == "mode") "업무 유형" else "설정", ::back,
                                        if(detailPage == "mode") "설정으로 돌아가기" else "이전 화면으로 돌아가기")
                                    Box(Modifier.weight(1f)) {
                                        if(detailPage == "mode") BoardSetup(s, car.canWork, true, { mode, cold -> session.selectMode(mode,cold); back() })
                                        else BoardSettings(s, car, session, now, settingsScroll,
                                            { detailPage = "mode"; focus.moveTo("mode/back") }, { selectTab(0) })
                                    }
                                }
                            } else HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = car.canWork, key = { workPages[it] }) { index ->
                                val tab = workPages[index]
                                CompositionLocalProvider(LocalFocusScope provides if(tab == "work") "detail" else tab,
                                    LocalWorkFocusEnabled provides (pager.settledPage == index)) {
                                    Column(Modifier.fillMaxSize().semantics { isTraversalGroup = true; traversalIndex = 2f; paneTitle = workPageLabels[index] }) {
                                        if(tab == "overview") BoardOverview(s, overviewScroll)
                                        else {
                                            Box(Modifier.weight(1f)) { BoardCurrent(s,car,now,workScroll,::openSettings,session::loadExample) }
                                            if (s.navigationMode == GuidanceMode.NATIVE_SAMPLE) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(if (car.canWork && car.parkingBrake == true) "주차 브레이크 체결" else "",
                                                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                IconAction(R.drawable.ic_location, if (s.route == null || s.route.status == GuidanceStatus.FAILED) "현재 목적지 안내 시작" else "현재 목적지 내비 보기",
                                                    enabled = car.canWork && ledger.current != null && !ledger.dayClosed && !ledger.drivingEnded, focusKey = "navigate") {
                                                    if (s.route == null || s.route.status == GuidanceStatus.FAILED) session.board.navigate()
                                                    session.gateway.openNavigation()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        BoardEnergyToast(car, now) {
                            focus.moveTo(if (detailPage != null) "header/settings" else "navigation/${workPages[pager.settledPage]}")
                        }
                        Divider()
                        CompositionLocalProvider(LocalFocusScope provides "navigation") {
                            Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control).selectableGroup()
                                .semantics { isTraversalGroup = true; traversalIndex = 3f },
                                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                listOf(R.drawable.ic_delivery, R.drawable.ic_document).forEachIndexed { index, icon ->
                                    IconAction(icon, workPageLabels[index], enabled = index == 0 || car.canWork,
                                        isSelected = detailPage == null && pager.currentPage == index, focusKey = workPages[index], role = Role.Tab) { selectTab(index) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun BoardSetup(s: BoardState, allowed: Boolean, editing: Boolean, choose: (ServiceMode, Boolean) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(s.preferredMode) }
    var cold by rememberSaveable { mutableStateOf(s.preferredCold) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(if(editing) "업무 · 차량 기본 설정" else "어떤 업무를 하시나요?", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(if(editing) "현재 업무와 연결은 유지됩니다. 배정된 업무·화물 조건은 단말 정보를 따릅니다." else "처음 한 번 설정하면 진행 중인 업무를 이어 보여드립니다.", style = MaterialTheme.typography.bodySmall)
        ActionButton("택배", Modifier.fillMaxWidth(), isSelected = mode == ServiceMode.PARCEL, enabled = allowed, icon = R.drawable.ic_delivery, focusKey = "parcel") { mode = ServiceMode.PARCEL }
        ActionButton("장거리 화물", Modifier.fillMaxWidth(), isSelected = mode == ServiceMode.FREIGHT, enabled = allowed, icon = R.drawable.ic_truck, focusKey = "freight") { mode = ServiceMode.FREIGHT }
        Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control).carFocusOutline("cold-option")
            .toggleable(value = cold, enabled = allowed, role = Role.Checkbox) { cold = it }.semantics(mergeDescendants = true) { stateDescription = if(cold) "사용" else "사용 안 함" }
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CarbonIcon(R.drawable.ic_temperature, null)
            Text("냉장·냉동 화물", Modifier.weight(1f).padding(start=16.dp), style=MaterialTheme.typography.bodyLarge)
            Text(if(cold) "사용" else "사용 안 함", color=MaterialTheme.colorScheme.primary)
        }
        if (!allowed) Text("정차 후 P에서 설정할 수 있습니다.", color = MaterialTheme.colorScheme.error)
        ActionButton(if(editing) "설정 저장" else "현재 업무로", Modifier.fillMaxWidth(), enabled = allowed, focusKey = "save") { choose(mode, cold) }
    }
}

@Composable private fun BoardNotice(text: String, warning: Boolean) {
    Text(text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=20.dp,vertical=12.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
        color = if(warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun BoardCurrent(s: BoardState, car: VehicleState, now: Long, scroll: ScrollState,
    connect: () -> Unit, example: () -> Unit) {
    val l = s.ledger ?: return
    val v = l.current
    val summary = workSummary(l)
    val ended = l.dayClosed || l.drivingEnded
    val arrived = s.hasArrivedAtCurrentVisit
    var details by rememberSaveable(l.streamId, v?.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (v == null) {
            Text("현재 배정이 없습니다", Modifier.semantics { heading() }, style=MaterialTheme.typography.headlineMedium)
            if(s.preferredCold) Text("냉장·냉동", style=MaterialTheme.typography.bodyLarge)
            Text("단말에서 배정된 업무를 받으면 여기에 표시합니다.", style=MaterialTheme.typography.bodySmall)
            ActionButton("단말 연결", Modifier.fillMaxWidth(), enabled=car.canWork, icon=R.drawable.ic_mobile, onClick=connect)
            ActionButton("예시 업무 보기", Modifier.fillMaxWidth(), secondary=true, enabled=car.canWork, icon=R.drawable.ic_play, onClick=example)
            return@Column
        }
        Text(if(ended) summary.title else v.name, Modifier.semantics { heading() }, style=MaterialTheme.typography.headlineMedium)
        if(ended) {
            Text(summary.detail, Modifier.semantics { liveRegion=LiveRegionMode.Polite }, style=MaterialTheme.typography.titleLarge)
            BoardFact("마지막 작업", v.name)
            RemainingWork(l, compact=true)
            if(l.refrigerated) BoardTemperature(l,now)
            Text("${l.sourceLabel} · ${timeText(l.lastSyncAt)}", style=MaterialTheme.typography.labelSmall)
            return@Column
        }
        if(summary.state != WorkHeadline.ACTIVE) StatusCard(summary.title, summary.detail, warning=summary.state==WorkHeadline.RECEIPT)
        else if(l.mode==ServiceMode.FREIGHT && v.phase==FacilityPhase.CALLED) StatusCard(v.dock ?: "현장 호출", "호출 정보가 도착했습니다")
        else if(arrived) Text("도착했습니다", Modifier.semantics { liveRegion=LiveRegionMode.Polite }, style=MaterialTheme.typography.titleLarge, color=MaterialTheme.colorScheme.primary)
        else if(now-s.assignmentChangedAt in 0..10_000 && s.assignmentChangedAt>0) Text("다음 업무로 업데이트되었습니다", Modifier.semantics { liveRegion=LiveRegionMode.Polite }, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.primary)

        if(s.route?.status==GuidanceStatus.FAILED) Text(s.route.message, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.error)
        else if(!arrived && s.navigationMode==GuidanceMode.NATIVE_SAMPLE) car.navigation?.takeIf { it.fresh(now) && v.target?.let { t -> requestedDestinationMatches(t,it.target) }==true }?.let {
            Text("${durationLabel(it.seconds)} · ${if(it.meters<1000) "${it.meters} m" else "%.1f km".format(it.meters/1000.0)}", style=MaterialTheme.typography.titleMedium)
        }
        if(summary.state != WorkHeadline.COMPLETE) {
            if(car.canWork && arrived) v.parking?.let { BoardFact("정차", it) }
            v.timeWindow?.let { BoardFact("시간 조건", it) }
            v.plannedStop?.let { BoardFact("예정 정차", it) }
            if(l.mode==ServiceMode.FREIGHT && v.phase!=FacilityPhase.CALLED && car.canWork && summary.state!=WorkHeadline.RECEIPT) {
                if(v.phase!=FacilityPhase.UNKNOWN || arrived) BoardFact("현장 절차", listOfNotNull(v.phase.label,v.dock).joinToString(" · "))
            }
            l.note?.let { BoardFact("변경사항",it) }
            val primary = l.attentionKind
            if(primary != null && summary.state != WorkHeadline.RECEIPT) QuantitySummary(l,primary)
        }
        if(l.refrigerated) BoardTemperature(l,now)

        if(car.canWork && l.mode==ServiceMode.PARCEL && l.primaryKind==TaskKind.DELIVERY) ParcelLineSummary(l)
        if(l.currentTasks.any { it.location != null && it.needsAttention }) BoardFact("확인된 적재 위치",l.currentTasks.filter { it.needsAttention }.mapNotNull { it.location }.distinct().joinToString(" · "))
        val hasSiteDetails = (!arrived && v.parking != null) || v.cargoInstructions != null || v.receiptInstructions != null ||
            (l.mode == ServiceMode.FREIGHT && (arrived || v.registeredAt != null))
        if(car.canWork && hasSiteDetails) {
            BoardDisclosure("현장 안내",details,"work-details") { details=!details }
            if(details) {
                if(!arrived) v.parking?.let { BoardFact("정차 안내",it) }
                v.cargoInstructions?.let { BoardFact("화물 조건",it) }
                v.receiptInstructions?.let { BoardFact("인수 조건",it) }
                if(l.mode==ServiceMode.FREIGHT) {
                    s.route?.takeIf { arrived && it.arrivedAt>0 }?.let { BoardFact("현장 체류","도착 후 ${((now-it.arrivedAt).coerceAtLeast(0)/60000)}분 · 위치 기준") }
                    v.registeredAt?.let { BoardFact("접수 기록","${timeText(it)} · 경과 ${((now-it).coerceAtLeast(0)/60000)}분") }
                }
            }
        }
        Text("${l.sourceLabel} · 마지막 갱신 ${timeText(l.lastSyncAt)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Location totals are readable at a glance; individual parcel handling remains on the terminal. */
@Composable private fun ParcelLineSummary(l: WorkLedger) {
    val groups = l.currentTasks.filter { it.needsAttention }.groupBy { it.building to it.entrance }
    if(groups.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        groups.forEach { (place,items) -> key(place) {
            Row(Modifier.fillMaxWidth().heightIn(min=WorkDimens.listRow).padding(vertical=16.dp)
                .semantics(mergeDescendants=true) {}, horizontalArrangement=Arrangement.spacedBy(24.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(place.first.ifBlank { "건물 미수신" },style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(place.second.ifBlank { "출입구 미수신" },style=MaterialTheme.typography.bodyLarge)
                }
                Text(remainingQuantityText(items),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,
                    textAlign=TextAlign.End)
            }
            Divider()
        } }
    }
}

@Composable private fun StatusCard(title: String, detail: String, warning: Boolean=false) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp)
        .semantics(mergeDescendants=true) { liveRegion=LiveRegionMode.Polite },verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge,color=if(warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if(detail.isNotBlank()) Text(detail,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun QuantitySummary(l: WorkLedger, primary: TaskKind) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
        CarbonIcon(if(primary in setOf(TaskKind.RETURN,TaskKind.CONTAINER)) R.drawable.ic_pickup else R.drawable.ic_inventory,null,Modifier.size(64.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants=true) { liveRegion=LiveRegionMode.Polite }) {
            Text("남은 ${primary.label}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${l.remaining(primary)}${l.currentTasks.first { it.kind==primary }.unit}",style=MaterialTheme.typography.headlineLarge)
        }
        if(primary!=TaskKind.RETURN && l.currentTasks.any { it.kind==TaskKind.RETURN }) Column(Modifier.semantics(mergeDescendants=true) {}) {
            Text("회수",style=MaterialTheme.typography.labelMedium)
            Text("${l.remaining(TaskKind.RETURN)}건",style=MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable private fun BoardDisclosure(label: String, expanded: Boolean, focusKey: String, click: ()->Unit) {
    ActionButton("$label ${if(expanded) "접기" else "보기"}",Modifier.fillMaxWidth().semantics { stateDescription=if(expanded) "펼쳐짐" else "접힘" },
        secondary=true,icon=if(expanded) R.drawable.ic_collapse else R.drawable.ic_expand,focusKey=focusKey,onClick=click)
}

@Composable private fun BoardFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants=true) {},verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun BoardTemperature(l: WorkLedger, now: Long, showHistory: Boolean=false) {
    val sample=l.sensor
    val status=cargoStatus(l,now)
    val fresh=sample?.fresh(now)==true
    var details by rememberSaveable(l.streamId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("화물칸 공기 온도",style=MaterialTheme.typography.labelMedium)
        Text(status.title,style=MaterialTheme.typography.titleLarge,color=if(status.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(status.detail,style=MaterialTheme.typography.bodySmall)
        Text("${if(!fresh) "마지막 문 상태" else "문"} ${when(sample?.doorOpen) { true->"열림"; false->"닫힘"; null->"미확인" }} · ${timeText(sample?.observedAt ?: 0)}",style=MaterialTheme.typography.labelSmall)
        if(showHistory) {
            BoardDisclosure("온도 기록",details,"temperature-details") { details=!details }
            if(details) {
                if(!fresh && sample?.measuredC!=null) Text("마지막 측정 ${sample.measuredC}°C · 마지막 설정 ${sample.setpointC?.let { "$it°C" } ?: "미확인"}",style=MaterialTheme.typography.bodySmall)
                l.cargoHistory.takeLast(3).reversed().forEach { r -> BoardFact(timeText(r.sample.observedAt),
                    if(!r.sample.available) "센서 확인 불가" else "${r.sample.measuredC}°C${if(r.outside) " · 요구 범위 이탈" else ""}") }
                Text("상품 자체 온도가 아닌 화물칸 공기 측정값입니다.",style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun RemainingWork(l: WorkLedger, compact: Boolean=false) {
    val remaining=l.visits.filter { v -> l.tasks.any { it.visitId==v.id && it.needsAttention } }
    if(remaining.isEmpty()) Text("미처리·전송 대기 없음",style=MaterialTheme.typography.bodyLarge)
    (if(compact) remaining.take(3) else remaining).forEach { v ->
        val ts=l.tasks.filter { it.visitId==v.id && it.needsAttention }
        BoardFact(v.name,ts.groupBy { it.kind }.entries.joinToString(" · ") { (kind,items)->
            "${kind.label} ${items.sumOf { it.remaining }}${items.first().unit}${if(items.any { it.awaitingSync }) " (전송 대기)" else ""}" })
    }
    if(compact && remaining.size>3) Text("그 외 ${remaining.size-3}곳 · 현재 남은 업무에서 확인",style=MaterialTheme.typography.bodySmall)
}

@Composable private fun BoardOverview(s: BoardState, scroll: ScrollState) {
    val l = s.ledger ?: return
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("현재 남은 업무", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        RemainingRouteCards(l)
        if(l.dayClosed || l.drivingEnded) BoardFact("종료 기록", workSummary(l).title)
        Text("${l.sourceLabel} · 마지막 갱신 ${timeText(l.lastSyncAt)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun DrivingRecords(s: BoardState) {
    var records by rememberSaveable { mutableStateOf(false) }
    Text("확인된 운행·정차",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
    BoardFact("수신된 속도 구간", if(s.segments.isEmpty()) "집계할 수신 기록 없음" else "주행 ${s.segments.filter { it.moving }.sumOf { it.end-it.start }/60000}분 · 정차 ${s.segments.filter { !it.moving }.sumOf { it.end-it.start }/60000}분")
    Text("수신된 구간만 집계 · 정차 용도 미확인",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    BoardDisclosure("최근 주행·정차 기록",records,"driving-records") { records=!records }
    if(records) s.segments.takeLast(5).forEach { BoardFact("${timeText(it.start)}–${timeText(it.end)}",if(it.moving) "주행 기록" else "정차 · 활동 미확인") }
}

@Composable private fun BoardSettings(s: BoardState, car: VehicleState, session: WorkSessionViewModel, now: Long, scroll: ScrollState, chooseMode: () -> Unit, home: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Text("업무 연결",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        Text(s.connectionMessage.ifBlank { "현재 기록을 보존하면서 단말을 연결할 수 있습니다." },style=MaterialTheme.typography.bodyLarge)
        s.grant?.let { grant ->
            val uri=grant.verificationUriComplete ?: grant.verificationUri
            val matrix=remember(uri) { pairingQr(uri) }
            Canvas(Modifier.size(220.dp).background(Color.White).clearAndSetSemantics { contentDescription="연결 QR. 코드 ${grant.userCode}. ${grant.verificationUri}" }) {
                val unit=size.width/matrix.width
                for(y in 0 until matrix.height) for(x in 0 until matrix.width) if(matrix[x,y]) drawRect(Color.Black,Offset(x*unit,y*unit),Size(unit,unit))
            }
            Text(grant.userCode,style=MaterialTheme.typography.headlineMedium)
            Text(grant.verificationUri,style=MaterialTheme.typography.labelSmall)
            if(BuildConfig.ALLOW_LOCAL_TEST_SERVER && session.apiBase.startsWith("http://127.0.0.1")) Text("Mac의 단말 시험 화면에서 같은 코드를 승인하세요.",style=MaterialTheme.typography.bodySmall)
        }
        ActionButton(if(s.connecting) "연결 취소" else if(s.connected) "연결 중지" else "단말 연결",Modifier.fillMaxWidth(),secondary=s.connecting||s.connected,enabled=car.canWork,icon=R.drawable.ic_mobile,focusKey="connect") {
            if(s.connecting||s.connected) session.disconnect() else session.beginConnection()
        }
        ActionButton("업무 유형 · 차량 옵션",Modifier.fillMaxWidth(),secondary=true,enabled=car.canWork,icon=R.drawable.ic_truck,focusKey="mode",onClick=chooseMode)
        Divider(); Text("화면",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        ActionButton(if(s.largeText) "기본 글자 크기" else "큰 글자",Modifier.fillMaxWidth(),secondary=true,enabled=car.canWork,icon=R.drawable.ic_document) { session.board.font() }
        Divider(); Text("내비게이션",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        ActionButton("기본 내비와 함께 보기",Modifier.fillMaxWidth(),secondary=true,isSelected=s.navigationMode==GuidanceMode.NATIVE_SAMPLE,enabled=car.canWork,icon=R.drawable.ic_location) { session.board.navigationMode(GuidanceMode.NATIVE_SAMPLE); home(); session.gateway.openNavigation() }
        ActionButton("앱 지도 · 이동 시험",Modifier.fillMaxWidth(),secondary=true,isSelected=s.navigationMode==GuidanceMode.DEMO,enabled=car.canWork,icon=R.drawable.ic_play) { session.board.navigationMode(GuidanceMode.DEMO); home() }
        Text("예시 배차의 기본 내비 목적지는 공식 SDK 시험 좌표입니다. 회사 목적지와 센서 정보는 별도 연동으로 받습니다.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        s.ledger?.let { BoardFact("마지막 업무 갱신",timeText(it.lastSyncAt)) }
        Divider(); DrivingRecords(s)
        s.ledger?.takeIf { it.refrigerated }?.let { Divider(); BoardTemperature(it, now, showHistory=true) }
        Text("업무 수량은 운송장 기준이며 실제 적재·중량과 다를 수 있습니다.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Divider(); Text("예시 업무",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        Text("현재 연결을 중지하고 기본 설정의 예시 업무를 엽니다. 기존 배정과 처리 기록은 보관됩니다.",style=MaterialTheme.typography.bodySmall)
        ActionButton("예시 업무로 전환",Modifier.fillMaxWidth(),secondary=true,enabled=car.canWork,icon=R.drawable.ic_play,focusKey="example") { session.loadExample(); home() }
    }
}
