package com.minwook.pleoswork

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop

private fun timeText(at: Long): String = recordTime(at)
private val workPages = listOf("work", "overview", "settings")
private val workPageLabels = listOf("현재 업무", "운송 현황", "연결과 화면 설정")

@Composable fun OperationsApp(s: BoardState, car: VehicleState, session: WorkSessionViewModel) {
    val pager = rememberPagerState { workPages.size }
    val scope = rememberCoroutineScope()
    var tabChange by remember { mutableStateOf<Job?>(null) }
    var detailPage by rememberSaveable { mutableStateOf<String?>(null) }
    var entrance by rememberSaveable { mutableStateOf<String?>(null) }
    val page = detailPage ?: workPages[pager.settledPage]
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { WorkFocus() }
    val input = LocalInputModeManager.current
    val density = LocalDensity.current
    val ledger = s.ledger
    val workScroll = rememberScrollState()
    fun selectTab(index: Int, focusTarget: String = "navigation/${workPages[index]}") {
        if (index != 0 && !car.canWork) return
        detailPage = null; entrance = null
        tabChange?.cancel()
        tabChange = scope.launch {
            pager.animateScrollToPage(index)
            focus.moveTo(focusTarget)
        }
    }
    fun home() {
        val previous = entrance
        val remaining = ledger?.currentTasks?.any { "${it.building}|${it.entrance}" == previous && it.needsAttention } == true
        selectTab(0, if (previous == null) "navigation/work" else if (remaining) "detail/entrance/$previous" else "detail/completed-groups")
    }
    fun open(value: String) {
        if (!car.canWork) return
        val index = workPages.indexOf(value)
        if (index >= 0) selectTab(index)
        else { detailPage = value; entrance = null; focus.moveTo("detail/back") }
    }
    fun back() {
        when (detailPage) {
            "mode" -> { detailPage = null; focus.moveTo("settings/mode") }
            else -> home()
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { detailPage = null; entrance = null }
    }
    LaunchedEffect(ledger?.streamId, ledger?.currentVisitId) {
        workScroll.scrollTo(0)
        if (entrance != null) { entrance = null; focus.moveTo("navigation/work") }
    }
    SideEffect { session.workVisible = ledger?.current != null && (page == "work" || s.navigationMode == GuidanceMode.DEMO) }
    DisposableEffect(Unit) { onDispose { session.workVisible = false } }
    LaunchedEffect(car.canWork) {
        if (!car.canWork) {
            tabChange?.cancel(); detailPage = null; entrance = null
            pager.scrollToPage(0)
            focus.moveTo("navigation/work")
        }
    }
    LaunchedEffect(focus.pending) { withFrameNanos { }; if (input.inputMode == InputMode.Keyboard) focus.pending?.second?.let { focus.restore(it) } }
    BackHandler(page != "work" || entrance != null) { back() }
    CompositionLocalProvider(LocalWorkFocus provides focus, LocalDensity provides Density(density.density, if (s.largeText) density.fontScale.coerceAtLeast(1.3f) else density.fontScale)) {
        Surface(Modifier.fillMaxSize()) {
            if (ledger == null) {
                CompositionLocalProvider(LocalFocusScope provides "setup") { BoardSetup(s, car.canWork, false, session::selectMode) }
            } else BoxWithConstraints {
                val mapLayout = workMapLayout(maxWidth.value, s.navigationMode)
                val mapBeside = mapLayout.beside
                val canExpand = maxWidth >= 1150.dp && !mapBeside
                Row(Modifier.fillMaxSize()) {
                    if (mapBeside) {
                        BoardNavigationMap(s, Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 8.dp)
                            .semantics(mergeDescendants = true) { traversalIndex = 0f },
                            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ledger.driverName, style = MaterialTheme.typography.bodyMedium)
                            Text("|", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                            Text(ledger.serviceLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Divider()
                        if (!car.canWork) BoardNotice("주행 중에는 요약을 표시합니다 · 상세는 정차 후 P에서", false)
                        else if (car.parkingBrake != true) BoardNotice(if (car.parkingBrake == false) "주차 브레이크 해제 · 차량에서 확인하세요" else "주차 브레이크 확인 불가 · 직접 확인하세요", true)
                        if (!s.connected && !ledger.streamId.startsWith("local-")) BoardNotice("${if (s.connectionMessage.isBlank()) "연결 확인 필요" else s.connectionMessage} · 마지막 ${timeText(ledger.lastSyncAt)}", false)
                        if (ledger.refrigerated && ledger.current != null) {
                            val cargo = cargoStatus(ledger, now)
                            if(cargo.exceptional) BoardNotice(cargo.title, cargo.warning)
                        }
                        if (mapLayout.above) BoardNavigationMap(s, Modifier.fillMaxWidth().height(196.dp), compact = true)
                        // Keep the map outside the pager. Only the work pane follows a horizontal drag.
                        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = car.canWork,
                            key = { workPages[it] }) { index ->
                            val tab = workPages[index]
                            val nested = detailPage?.takeIf { it == "mode" && tab == "settings" }
                            CompositionLocalProvider(
                                LocalFocusScope provides if (nested != null || tab == "work") "detail" else tab,
                                LocalWorkFocusEnabled provides (pager.settledPage == index)
                            ) {
                                Column(Modifier.fillMaxSize().semantics {
                                    isTraversalGroup = true; traversalIndex = 2f
                                    paneTitle = when { nested == "mode" -> "업무 유형"; entrance != null && tab == "work" -> "방문지 물품"; else -> workPageLabels[index] }
                                }) {
                                    if (nested != null || (entrance != null && tab == "work")) SectionHeader(
                                        if (entrance != null) entrance!!.replace("|", " · ") else if (nested == "mode") "업무 유형" else "예정 방문지",
                                        ::back, if (nested == "mode") "설정으로 돌아가기" else "현재 업무로 돌아가기")
                                    when (nested ?: tab) {
                                        "mode" -> BoardSetup(s, car.canWork, true, { mode, cold -> session.selectMode(mode,cold); home() })
                                        "settings" -> BoardSettings(s, car, session, now, { open("mode") }, ::home)
                                        "overview" -> BoardOverview(s, car, now)
                                        else -> {
                                            if (entrance != null && car.canWork) Box(Modifier.weight(1f)) { BoardItems(ledger.currentTasks.filter { "${it.building}|${it.entrance}" == entrance }) }
                                            else if (s.expanded && canExpand && car.canWork) Row(Modifier.weight(1f)) {
                                                Box(Modifier.weight(1f)) { BoardCurrent(s, car, now, workScroll, { entrance = it; focus.moveTo("detail/back") }, { open("settings") }, session::loadExample) }
                                                VerticalDivider()
                                                Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalFocusScope provides "overview-side") { BoardOverview(s, car, now) } }
                                            } else Box(Modifier.weight(1f)) { BoardCurrent(s,car,now,workScroll,{ entrance = it; focus.moveTo("detail/back") }, { open("settings") }, session::loadExample) }
                                            if (s.navigationMode == GuidanceMode.NATIVE_SAMPLE) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(if (car.canWork && car.parkingBrake == true) "주차 브레이크 체결" else "",
                                                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                IconAction(R.drawable.ic_location, if (s.route == null || s.route.status == GuidanceStatus.FAILED) "현재 목적지 안내 시작" else "현재 목적지 내비 보기",
                                                    enabled = car.canWork && ledger.current != null && !ledger.dayClosed && !ledger.drivingEnded, focusKey = "navigate") {
                                                    if (s.route == null || s.route.status == GuidanceStatus.FAILED) session.board.navigate()
                                                    if (s.navigationMode == GuidanceMode.NATIVE_SAMPLE) session.gateway.openNavigation()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Divider()
                        CompositionLocalProvider(LocalFocusScope provides "navigation") {
                            Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control).selectableGroup()
                                .semantics { isTraversalGroup = true; traversalIndex = 3f },
                                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                listOf(R.drawable.ic_delivery, R.drawable.ic_document, R.drawable.ic_settings).forEachIndexed { index, icon ->
                                    IconAction(icon, workPageLabels[index], enabled = index == 0 || car.canWork,
                                        isSelected = pager.currentPage == index, focusKey = workPages[index], role = Role.Tab) { selectTab(index) }
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
    select: (String) -> Unit, connect: () -> Unit, example: () -> Unit) {
    val l = s.ledger ?: return
    val v = l.current
    val summary = workSummary(l)
    val ended = l.dayClosed || l.drivingEnded
    val arrived = s.route?.visitId == v?.id && s.route?.status == GuidanceStatus.ARRIVED
    var details by rememberSaveable(l.streamId, v?.id) { mutableStateOf(false) }
    var completed by rememberSaveable(l.streamId, v?.id) { mutableStateOf(false) }
    val focus = LocalWorkFocus.current
    val groups = l.currentTasks.groupBy { "${it.building}|${it.entrance}" }
    val finishedGroups = groups.filterValues { it.confirmedComplete }
    // An externally completed focused row gets a stable replacement, rather than losing keyboard focus.
    // Capture before Compose disposes the row and falls back to the toolbar.
    val restoreCompletedFocus = !completed && finishedGroups.keys.any { focus?.current == "detail/entrance/$it" }
    LaunchedEffect(finishedGroups.keys) {
        if (restoreCompletedFocus) focus?.moveTo("detail/completed-groups")
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (v == null) {
            Text("현재 배정이 없습니다", Modifier.semantics { heading() }, style=MaterialTheme.typography.headlineMedium)
            if(s.preferredCold) Text("냉장·냉동", style=MaterialTheme.typography.bodyLarge)
            Text("단말에서 배정된 업무를 받으면 여기에 표시합니다.", style=MaterialTheme.typography.bodySmall)
            ActionButton("단말 연결", Modifier.fillMaxWidth(), enabled=car.canWork, icon=R.drawable.ic_mobile, onClick=connect)
            ActionButton("예시 업무 보기", Modifier.fillMaxWidth(), secondary=true, enabled=car.canWork, icon=R.drawable.ic_play, onClick=example)
            return@Column
        }
        Text("${v.round}회차 · ${v.operation}${if(l.streamId.startsWith("local-")) " · 예시" else ""}",
            style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
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
        else if(now-s.assignmentChangedAt in 0..10_000 && s.assignmentChangedAt>0) Text("현재 업무가 변경되었습니다", Modifier.semantics { liveRegion=LiveRegionMode.Polite }, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.primary)

        if(s.route?.status==GuidanceStatus.FAILED) Text(s.route.message, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.error)
        else if(!arrived && s.navigationMode==GuidanceMode.NATIVE_SAMPLE) car.navigation?.takeIf { it.fresh(now) && v.target?.let { t -> requestedDestinationMatches(t,it.target) }==true }?.let {
            Text("${durationLabel(it.seconds)} · ${if(it.meters<1000) "${it.meters} m" else "%.1f km".format(it.meters/1000.0)}", style=MaterialTheme.typography.titleMedium)
        }
        if(summary.state != WorkHeadline.COMPLETE) {
            // Entry information must not depend on the demo route's elapsed time.
            v.access?.let { BoardFact("진입", it) }
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
        if(l.refrigerated) BoardTemperature(l,now,summaryOnly=!car.canWork)

        if(car.canWork && l.mode==ServiceMode.PARCEL && l.primaryKind==TaskKind.DELIVERY) {
            groups.filterValues { !it.confirmedComplete }.forEach { (id,items) -> key(id) { EntranceRow(id,items,select) } }
            if(finishedGroups.isNotEmpty()) {
                BoardDisclosure("처리 완료 ${finishedGroups.size}곳",completed,"completed-groups") { completed=!completed }
                if(completed) finishedGroups.forEach { (id,items) -> key(id) { EntranceRow(id,items,select) } }
            }
        } else if(car.canWork && summary.state != WorkHeadline.COMPLETE) {
            l.currentTasks.filter { it.kind!=l.primaryKind && it.kind!=TaskKind.RECEIPT && it.needsAttention }.forEach { t ->
                BoardFact("${t.kind.label} · ${t.number}","${t.remaining}${t.unit} 남음${if(t.awaitingSync) " · 전송 대기" else ""}")
            }
        }
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
        if (s.expanded && s.navigationMode == GuidanceMode.DEMO && car.canWork) BoardOverview(s, car, now, scrollable = false)
    }
}

@Composable private fun StatusCard(title: String, detail: String, warning: Boolean=false) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)
        .semantics(mergeDescendants=true) { liveRegion=LiveRegionMode.Polite },verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge,color=if(warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(detail,style=MaterialTheme.typography.bodySmall)
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

@Composable private fun EntranceRow(id: String, items: List<LedgerTask>, select: (String)->Unit) {
    val deliveries=items.filter { it.kind==TaskKind.DELIVERY }.sumOf { it.remaining }
    val returns=items.filter { it.kind==TaskKind.RETURN }.sumOf { it.remaining }
    Row(Modifier.fillMaxWidth().heightIn(min=80.dp).carFocusOutline("entrance/$id")
        .clickable(role=Role.Button,onClickLabel="물품별 상태 보기") { select(id) }
        .semantics(mergeDescendants=true) {}.padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(items.first().building.ifBlank { "건물 미수신" },style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(items.first().entrance.ifBlank { "출입구 미수신" },style=MaterialTheme.typography.bodyLarge)
            if(items.any { it.awaitingSync }) Text("처리 결과 전송 대기",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        }
        Text(if(items.confirmedComplete) "처리 완료" else "${deliveries}개${if(returns>0) " · 회수 $returns" else ""}",style=MaterialTheme.typography.titleMedium,
            color=if(items.confirmedComplete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        CarbonIcon(R.drawable.ic_arrow_right,null)
    }
    Divider()
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

@Composable private fun BoardItems(tasks: List<LedgerTask>) {
    var completed by rememberSaveable { mutableStateOf(false) }
    val done=tasks.filter { !it.needsAttention }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        val remaining=tasks.filter { it.needsAttention }
        Text(if(remaining.isEmpty()) "처리 결과 반영 완료" else if(remaining.any { it.awaitingSync }) "미처리·전송 대기 · ${remaining.size}건" else "남은 작업 · ${remaining.size}건",Modifier.semantics { heading(); liveRegion=LiveRegionMode.Polite },style=MaterialTheme.typography.titleLarge)
        remaining.forEach { t -> key(t.id) { TaskRecord(t); Divider() } }
        if(done.isNotEmpty()) {
            BoardDisclosure("처리 완료 ${done.size}건",completed,"completed-items") { completed=!completed }
            if(completed) done.forEach { t -> key(t.id) { TaskRecord(t); Divider() } }
        }
    }
}

@Composable private fun TaskRecord(t: LedgerTask) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants=true) {},horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        CarbonIcon(if(t.kind==TaskKind.RETURN) R.drawable.ic_pickup else R.drawable.ic_inventory,null)
        Column(Modifier.weight(1f)) {
            Text("${t.number} · ${t.kind.label}",style=MaterialTheme.typography.titleMedium)
            Text(if(!t.needsAttention) "처리 완료 · ${timeText(t.updatedAt)}" else "남음 ${t.remaining}${t.unit} · ${t.sync.label}",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun BoardTemperature(l: WorkLedger, now: Long, summaryOnly: Boolean=false) {
    val sample=l.sensor
    val status=cargoStatus(l,now)
    val fresh=sample?.fresh(now)==true
    var details by rememberSaveable(l.streamId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("화물칸 공기 온도",style=MaterialTheme.typography.labelMedium)
        Text(status.title,style=MaterialTheme.typography.titleLarge,color=if(status.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(status.detail,style=MaterialTheme.typography.bodySmall)
        Text("${if(!fresh) "마지막 문 상태" else "문"} ${when(sample?.doorOpen) { true->"열림"; false->"닫힘"; null->"미확인" }} · ${timeText(sample?.observedAt ?: 0)}",style=MaterialTheme.typography.labelSmall)
        if(!summaryOnly) {
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
        BoardFact("${v.round}회차 · ${v.name}",ts.groupBy { it.kind }.entries.joinToString(" · ") { (kind,items)->
            "${kind.label} ${items.sumOf { it.remaining }}${items.first().unit}${if(items.any { it.awaitingSync }) " (전송 대기)" else ""}" })
    }
    if(compact && remaining.size>3) Text("그 외 ${remaining.size-3}곳 · 운송 현황에서 확인",style=MaterialTheme.typography.bodySmall)
}

@Composable private fun BoardOverview(s: BoardState, car: VehicleState, now: Long, scrollable: Boolean = true) {
    val l=s.ledger ?: return
    var records by rememberSaveable { mutableStateOf(false) }
    var finished by rememberSaveable { mutableStateOf(false) }
    val content = if (scrollable) Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp) else Modifier.fillMaxWidth()
    Column(content,verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Text("남은 업무",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        BoardFact("현재 방문지 · ${l.current?.name ?: "배정 대기"}",remainingQuantityText(l.currentTasks))
        BoardVisitList(l, Modifier.fillMaxWidth(), scrollable = false)
        Text("운송장 기준 수량 · 실제 적재·중량은 별도 확인",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(l.dayClosed || l.drivingEnded) BoardFact("종료 기록", workSummary(l).title)
        val completed=l.visits.filter { v -> l.tasks.filter { it.visitId==v.id }.confirmedComplete }
        if(completed.isNotEmpty()) {
            BoardDisclosure("완료한 방문지 ${completed.size}곳",finished,"overview-completed") { finished=!finished }
            if(finished) completed.forEach { BoardFact("${it.round}회차 · ${it.name}","처리 결과 반영 완료") }
        }
        if(l.refrigerated) BoardTemperature(l,now)
        Divider(); Text("차량",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        fun fresh(at:Long)=at>0 && now-at in 0..90_000
        BoardFact("배터리 · 연료",listOfNotNull(car.batteryPercent?.takeIf { fresh(car.batteryUpdatedAt) }?.let { "배터리 $it%" },car.fuelPercent?.takeIf { fresh(car.fuelUpdatedAt) }?.let { "연료 $it%" }).joinToString(" · ").ifBlank { "지원 정보 미수신" })
        BoardFact("주행 가능 거리",car.rangeKm?.takeIf { fresh(car.rangeUpdatedAt) }?.let { "${it.toInt()} km" } ?: "미수신")
        Text("운행 거리·전비/연비는 확인된 기록이 없습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Divider(); Text("확인된 운행·정차",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        BoardFact("수신된 속도 구간", if(s.segments.isEmpty()) "집계할 수신 기록 없음" else "주행 ${s.segments.filter { it.moving }.sumOf { it.end-it.start }/60000}분 · 정차 ${s.segments.filter { !it.moving }.sumOf { it.end-it.start }/60000}분")
        Text("수신된 구간만 집계 · 정차 용도 미확인",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        BoardDisclosure("최근 주행·정차 기록",records,"driving-records") { records=!records }
        if(records) s.segments.takeLast(5).forEach { BoardFact("${timeText(it.start)}–${timeText(it.end)}",if(it.moving) "주행 기록" else "정차 · 활동 미확인") }
        Text("${l.sourceLabel} · 마지막 갱신 ${timeText(l.lastSyncAt)}",style=MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun BoardVisitList(l: WorkLedger, modifier: Modifier = Modifier.fillMaxSize(), scrollable: Boolean = true) {
    val upcoming = upcomingVisits(l)
    var all by rememberSaveable(l.streamId, l.currentVisitId) { mutableStateOf(scrollable) }
    val content = if (scrollable) modifier.verticalScroll(rememberScrollState()).padding(24.dp) else modifier
    Column(content, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("다음 예정", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (upcoming.isEmpty()) Text("다음 방문지 배정 대기", style = MaterialTheme.typography.bodyLarge)
        (if (all) upcoming else upcoming.take(2)).forEachIndexed { index, visit ->
            val label = when(index) { 0 -> "바로 다음"; 1 -> "그 다음"; else -> "${index + 1}번째" }
            BoardFact("$label · ${visit.round}회차 · ${visit.name}", remainingQuantityText(l.tasks.filter { it.visitId == visit.id }))
        }
        if (upcoming.size > 2) ActionButton(if(all) "두 곳만 보기" else "… 나머지 ${upcoming.size - 2}곳 보기",
            Modifier.fillMaxWidth(), secondary = true, focusKey = "remaining-visits") { all = !all }
        val previous = l.visits.take(l.visits.indexOfFirst { it.id == l.currentVisitId }.coerceAtLeast(0))
            .filter { visit -> l.tasks.any { it.visitId == visit.id && it.needsAttention } }
        if (previous.isNotEmpty()) {
            Text("이전 방문지 · 미처리", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            previous.forEach { visit -> BoardFact(visit.name, remainingQuantityText(l.tasks.filter { it.visitId == visit.id })) }
        }
    }
}

@Composable private fun BoardSettings(s: BoardState, car: VehicleState, session: WorkSessionViewModel, now: Long, chooseMode: () -> Unit, home: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
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
            if(BuildConfig.DEBUG && session.apiBase.startsWith("http://127.0.0.1")) Text("Mac의 단말 시험 화면에서 같은 코드를 승인하세요.",style=MaterialTheme.typography.bodySmall)
        }
        ActionButton(if(s.connecting) "연결 취소" else if(s.connected) "연결 중지" else "단말 연결",Modifier.fillMaxWidth(),secondary=s.connecting||s.connected,enabled=car.canWork,icon=R.drawable.ic_mobile,focusKey="connect") {
            if(s.connecting||s.connected) session.disconnect() else session.beginConnection()
        }
        ActionButton("업무 유형 · 차량 옵션",Modifier.fillMaxWidth(),secondary=true,enabled=car.canWork,icon=R.drawable.ic_truck,focusKey="mode",onClick=chooseMode)
        Divider(); Text("화면",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        ActionButton(if(s.expanded) "업무 한 칸으로" else "정차 중 상세 화면",Modifier.fillMaxWidth(),secondary=true,enabled=car.canWork,icon=R.drawable.ic_expand) { session.board.expand() }
        ActionButton(if(s.largeText) "기본 글자 크기" else "큰 글자",Modifier.fillMaxWidth(),secondary=true,enabled=car.canWork,icon=R.drawable.ic_document) { session.board.font() }
        Divider(); Text("내비게이션",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        ActionButton("기본 내비와 함께 보기",Modifier.fillMaxWidth(),secondary=true,isSelected=s.navigationMode==GuidanceMode.NATIVE_SAMPLE,enabled=car.canWork,icon=R.drawable.ic_location) { session.board.navigationMode(GuidanceMode.NATIVE_SAMPLE); home(); session.gateway.openNavigation() }
        ActionButton("앱 지도 · 이동 시험",Modifier.fillMaxWidth(),secondary=true,isSelected=s.navigationMode==GuidanceMode.DEMO,enabled=car.canWork,icon=R.drawable.ic_play) { session.board.navigationMode(GuidanceMode.DEMO); home() }
        Text("예시 배차의 기본 내비 목적지는 공식 SDK 시험 좌표입니다. 회사 목적지와 센서 정보는 별도 연동으로 받습니다.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        s.ledger?.let { BoardFact("마지막 업무 갱신",timeText(it.lastSyncAt)) }
        Divider(); Text("예시 업무",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        Text("현재 연결을 중지하고 기본 설정의 예시 업무를 엽니다. 기존 배정과 처리 기록은 보관됩니다.",style=MaterialTheme.typography.bodySmall)
        ActionButton("예시 업무로 전환",Modifier.fillMaxWidth(),secondary=true,enabled=car.canWork,icon=R.drawable.ic_play,focusKey="example") { session.loadExample(); home() }
    }
}
