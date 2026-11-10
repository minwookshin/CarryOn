package com.minwook.pleoswork

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timeText(at: Long): String = if (at <= 0) "미수신" else SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at))
private fun modeName(mode: ServiceMode) = if (mode == ServiceMode.PARCEL) "택배" else "장거리 화물"

@Composable fun OperationsApp(s: BoardState, car: VehicleState, session: WorkSessionViewModel) {
    var page by rememberSaveable { mutableStateOf("work") }
    var entrance by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { WorkFocus() }
    val input = LocalInputModeManager.current
    val density = LocalDensity.current
    val ledger = s.ledger
    fun home() { page = "work"; entrance = null; focus.moveTo("toolbar/work") }
    fun open(value: String) { page = value; entrance = null; focus.moveTo("detail/back") }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    LaunchedEffect(ledger?.streamId, ledger?.currentVisitId) {
        if (entrance != null) { entrance = null; focus.moveTo("toolbar/work") }
    }
    LaunchedEffect(car.canWork) {
        if (!car.canWork) { page = "work"; entrance = null; focus.moveTo("toolbar/work") }
    }
    LaunchedEffect(focus.pending) { withFrameNanos { }; if (input.inputMode == InputMode.Keyboard) focus.pending?.second?.let { focus.restore(it) } }
    BackHandler(page != "work" || entrance != null) { home() }
    CompositionLocalProvider(LocalWorkFocus provides focus, LocalDensity provides Density(density.density, if (s.largeText) density.fontScale.coerceAtLeast(1.3f) else density.fontScale)) {
        Surface(Modifier.fillMaxSize()) {
            if (ledger == null) {
                CompositionLocalProvider(LocalFocusScope provides "setup") { BoardSetup(car.canWork, session::selectMode) }
            } else BoxWithConstraints {
                val mapBeside = maxWidth >= 1150.dp && s.navigationMode == GuidanceMode.DEMO && !s.expanded
                val canExpand = maxWidth >= 1150.dp
                Column(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalFocusScope provides "toolbar") {
                        Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control).padding(horizontal = 12.dp)
                            .semantics { isTraversalGroup = true; traversalIndex = 0f }, verticalAlignment = Alignment.CenterVertically) {
                            Text(ledger.driverName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconAction(R.drawable.ic_delivery, "현재 업무", focusKey = "work", isSelected = page == "work", onClick = ::home)
                            IconAction(R.drawable.ic_document, "운송 현황", focusKey = "overview", enabled = car.canWork, isSelected = page == "overview") { open("overview") }
                            IconAction(R.drawable.ic_settings, "연결과 화면 설정", focusKey = "settings", enabled = car.canWork, isSelected = page == "settings") { open("settings") }
                        }
                    }
                    Divider()
                    if (!car.canWork) BoardNotice("주행 중에는 요약을 표시합니다 · 상세는 정차 후 P에서", false)
                    else if (car.parkingBrake != true) BoardNotice(if (car.parkingBrake == false) "주차 브레이크 해제 · 차량에서 확인하세요" else "주차 브레이크 확인 불가 · 직접 확인하세요", true)
                    if (!s.connected && !ledger.streamId.startsWith("local-")) BoardNotice("${if (s.connectionMessage.isBlank()) "연결 확인 필요" else s.connectionMessage} · 마지막 ${timeText(ledger.lastSyncAt)}", false)
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (mapBeside) {
                            BoardMap(s, Modifier.weight(.55f).fillMaxHeight())
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        CompositionLocalProvider(LocalFocusScope provides "detail") {
                            Column(Modifier.weight(if (mapBeside) .45f else 1f).fillMaxHeight()
                                .semantics { isTraversalGroup = true; traversalIndex = 2f; paneTitle = when(page) { "settings" -> "연결과 화면 설정"; "overview" -> "운송 현황"; else -> "현재 업무" } }) {
                                if (page != "work" || entrance != null) SectionHeader(if (entrance != null) entrance!!.replace("|", " · ") else if (page == "overview") "운송 현황" else if (page == "mode") "업무 유형" else "연결과 화면 설정", ::home, "현재 업무로 돌아가기")
                                when (page) {
                                    "mode" -> BoardSetup(car.canWork, { mode, cold -> session.selectMode(mode,cold); home() })
                                    "settings" -> BoardSettings(s, car, session, now) { open("mode") }
                                    "overview" -> BoardOverview(s, car, now)
                                    else -> {
                                        if (entrance != null && car.canWork) Box(Modifier.weight(1f)) { BoardItems(ledger.currentTasks.filter { "${it.building}|${it.entrance}" == entrance }) }
                                        else if (s.expanded && canExpand && car.canWork) Row(Modifier.weight(1f)) {
                                            Box(Modifier.weight(1f)) { BoardCurrent(s, car, now, { entrance = it; focus.moveTo("detail/back") }) }
                                            VerticalDivider()
                                            Box(Modifier.weight(1f)) { BoardOverview(s, car, now) }
                                        } else Box(Modifier.weight(1f)) { BoardCurrent(s,car,now,{ entrance = it; focus.moveTo("detail/back") }) }
                                        Divider()
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (car.canWork) "주차 브레이크 ${brakeLabel(car.parkingBrake)}" else "${modeName(ledger.mode)} · ${ledger.sourceLabel}",
                                                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            IconAction(R.drawable.ic_location, if (s.route == null || s.route.status == GuidanceStatus.FAILED) "현재 목적지 안내 시작" else "현재 목적지 내비 보기",
                                                enabled = car.canWork && ledger.current != null, focusKey = "navigate") {
                                                if (s.route == null || s.route.status == GuidanceStatus.FAILED) session.board.navigate()
                                                if (s.navigationMode == GuidanceMode.NATIVE_SAMPLE) session.gateway.openNavigation()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun BoardSetup(allowed: Boolean, choose: (ServiceMode, Boolean) -> Unit) {
    var cold by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("어떤 업무를 하시나요?", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text("이후에는 진행 중인 업무를 이어 보여드립니다.", style = MaterialTheme.typography.bodyLarge)
        ActionButton("택배", Modifier.fillMaxWidth(), enabled = allowed, icon = R.drawable.ic_delivery, focusKey = "parcel") { choose(ServiceMode.PARCEL, cold) }
        ActionButton("장거리 화물", Modifier.fillMaxWidth(), secondary = true, enabled = allowed, icon = R.drawable.ic_truck, focusKey = "freight") { choose(ServiceMode.FREIGHT, cold) }
        Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control).carFocusOutline("cold-option")
            .toggleable(value = cold, enabled = allowed, role = Role.Checkbox) { cold = it }.semantics(mergeDescendants = true) { stateDescription = if(cold) "사용" else "사용 안 함" }
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CarbonIcon(R.drawable.ic_temperature, null)
            Text("냉장·냉동 화물", Modifier.weight(1f).padding(start=16.dp), style=MaterialTheme.typography.bodyLarge)
            Text(if(cold) "사용" else "사용 안 함", color=MaterialTheme.colorScheme.primary)
        }
        if (!allowed) Text("정차 후 P에서 설정할 수 있습니다.", color = MaterialTheme.colorScheme.error)
        Text("연결 전에는 예시 업무를 표시합니다. 실제 업무 연결은 설정에서 할 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BoardNotice(text: String, warning: Boolean) {
    Text(text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=20.dp,vertical=12.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
        color = if(warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun BoardCurrent(s: BoardState, car: VehicleState, now: Long, select: (String) -> Unit) {
    val l = s.ledger ?: return
    val v = l.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(if(l.dayClosed) "업무 시스템 · 오늘 업무 종료" else if(l.drivingEnded) "오늘 운전 마침 · 남은 운송 보존" else "${v?.round ?: 1}회차 · ${v?.operation ?: "배정 대기"}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v?.name ?: "현재 배정이 없습니다", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        val arrived = s.route?.visitId == v?.id && s.route?.status == GuidanceStatus.ARRIVED
        if (arrived) Text("도착했습니다", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style=MaterialTheme.typography.titleLarge, color=MaterialTheme.colorScheme.primary)
        else if (s.route?.status == GuidanceStatus.FAILED) Text(s.route.message, color=MaterialTheme.colorScheme.error, style=MaterialTheme.typography.bodySmall)
        else if (s.navigationMode == GuidanceMode.NATIVE_SAMPLE) car.navigation?.takeIf { it.fresh(now) && v?.target?.let { t -> requestedDestinationMatches(t,it.target) } == true }?.let {
            Text("${durationLabel(it.seconds)} · ${if(it.meters < 1000) "${it.meters} m" else "%.1f km".format(it.meters/1000.0)}",style=MaterialTheme.typography.titleMedium)
        }
        if (v == null) { Text("새 배정 또는 현재 작업을 수신하면 표시합니다."); return@Column }
        val primaryKind = l.primaryKind
        if (primaryKind != null) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp), horizontalArrangement=Arrangement.spacedBy(20.dp), verticalAlignment=Alignment.CenterVertically) {
            CarbonIcon(if(primaryKind in setOf(TaskKind.RETURN,TaskKind.CONTAINER)) R.drawable.ic_pickup else R.drawable.ic_inventory, null, Modifier.size(64.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants=true) { liveRegion = LiveRegionMode.Polite }) {
                Text("남은 ${primaryKind.label}", style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${l.remaining(primaryKind)}${l.currentTasks.firstOrNull { it.kind == primaryKind }?.unit ?: "개"}", style=MaterialTheme.typography.headlineLarge)
            }
            if (primaryKind != TaskKind.RETURN && l.currentTasks.any { it.kind == TaskKind.RETURN }) Column(Modifier.semantics(mergeDescendants=true) {}) {
                Text("회수",style=MaterialTheme.typography.labelMedium)
                Text("${l.remaining(TaskKind.RETURN)}건",style=MaterialTheme.typography.titleLarge)
            }
        }
        if (l.currentTasks.any { it.awaitingSync }) Text("단말 처리 ${l.currentTasks.filter { it.awaitingSync }.sumOf { it.completed }} · 서버 반영 대기", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.primary)
        if (l.mode == ServiceMode.FREIGHT) {
            if (car.canWork) {
                s.route?.takeIf { arrived && it.arrivedAt > 0 }?.let { BoardFact("현장 체류", "도착 후 ${((now-it.arrivedAt).coerceAtLeast(0)/60000)}분 · 위치 기준") }
                BoardFact("현장 절차", listOfNotNull(v.phase.label,v.dock).joinToString(" · "))
                v.registeredAt?.let { BoardFact("접수 기록", "${timeText(it)} · 경과 ${((now-it).coerceAtLeast(0)/60000)}분") }
                if (l.currentTasks.any { it.kind == TaskKind.RECEIPT && it.remaining > 0 }) BoardNotice("인수 미완료 · 하역 결과와 별도 확인", true)
                BoardFact("출차 · 다음 배차", "확정 정보 미수신")
            }
        } else if (car.canWork && primaryKind == TaskKind.DELIVERY) {
            val groups = l.currentTasks.groupBy { "${it.building}|${it.entrance}" }
            groups.forEach { (id, items) -> key(id) {
                val deliveries = items.filter { it.kind == TaskKind.DELIVERY }.sumOf { it.remaining }
                val returns = items.filter { it.kind == TaskKind.RETURN }.sumOf { it.remaining }
                Row(Modifier.fillMaxWidth().heightIn(min=80.dp).carFocusOutline("entrance/$id")
                    .clickable(role=Role.Button, onClickLabel="물품별 상태 보기") { select(id) }
                    .semantics(mergeDescendants=true) {}.padding(vertical=12.dp), horizontalArrangement=Arrangement.spacedBy(12.dp), verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(items.first().building.ifBlank { "건물 미수신" },style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(items.first().entrance.ifBlank { "출입구 미수신" },style=MaterialTheme.typography.bodyLarge)
                    }
                    Text("${deliveries}개${if(returns>0) " · 회수 ${returns}" else ""}",style=MaterialTheme.typography.titleMedium)
                    CarbonIcon(R.drawable.ic_arrow_right,null)
                }
                Divider()
            } }
        } else if (car.canWork && primaryKind != TaskKind.DELIVERY) l.currentTasks.forEach { t ->
            BoardFact("${t.kind.label} · ${t.number}","${t.remaining}${t.unit} 남음 · ${t.sync.label}")
        }
        if (l.refrigerated) BoardTemperature(l,now)
        if (car.canWork || s.route?.progress?.let { it >= .55f } == true) {
            v.access?.let { BoardFact("진입",it) }
            if (car.canWork) v.parking?.let { BoardFact("정차",it) }
        }
        if (l.currentTasks.any { it.location != null }) BoardFact("확인된 적재 위치",l.currentTasks.mapNotNull { it.location }.distinct().joinToString(" · "))
        l.note?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("${l.sourceLabel} · 마지막 갱신 ${timeText(l.lastSyncAt)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BoardFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants=true) {},verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun BoardItems(tasks: List<LedgerTask>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        tasks.forEach { t -> key(t.id) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants=true) {},horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                CarbonIcon(if(t.kind == TaskKind.RETURN) R.drawable.ic_pickup else R.drawable.ic_inventory,null)
                Column(Modifier.weight(1f)) {
                    Text("${t.number} · ${t.kind.label}",style=MaterialTheme.typography.titleMedium)
                    Text("남음 ${t.remaining}${t.unit} · ${t.sync.label}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }; Divider()
        } }
    }
}

@Composable private fun BoardTemperature(l: WorkLedger, now: Long) {
    val sample=l.sensor
    val v=l.current
    val fresh=sample?.fresh(now)==true
    val requirement = if(v?.requiredLow!=null && v.requiredHigh!=null) "${v.requiredLow} ~ ${v.requiredHigh}°C" else "미수신"
    val outside = fresh && v?.requiredLow != null && v.requiredHigh != null && sample!!.measuredC!! !in v.requiredLow..v.requiredHigh
    val settingOutside = fresh && v?.requiredLow != null && v.requiredHigh != null && sample?.setpointC?.let { it !in v.requiredLow..v.requiredHigh } == true
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("화물칸 공기 온도",style=MaterialTheme.typography.labelMedium)
        Text(if(fresh) "${sample!!.measuredC}°C${if(outside) " · 요구 범위 이탈" else ""}" else "현재 온도 확인 불가",
            style=MaterialTheme.typography.titleLarge,color=if(outside) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text("요구 $requirement · ${if(!fresh) "마지막 설정" else "설정"} ${sample?.setpointC?.let { "$it°C" } ?: "미수신"}",style=MaterialTheme.typography.bodySmall)
        if(settingOutside) Text("설정 온도가 요구 범위와 다릅니다",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        Text("최근 ${timeText(sample?.observedAt ?: 0)}${if(!fresh && sample?.measuredC!=null) " · 마지막 ${sample.measuredC}°C" else ""} · ${if(!fresh) "마지막 문 상태" else "문"} ${when(sample?.doorOpen) { true->"열림"; false->"닫힘"; null->"미확인" }}",style=MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun BoardOverview(s: BoardState, car: VehicleState, now: Long) {
    val l=s.ledger ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Text("차량",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        fun fresh(at:Long)=at>0 && now-at in 0..90_000
        BoardFact("배터리 · 연료",listOfNotNull(car.batteryPercent?.takeIf { fresh(car.batteryUpdatedAt) }?.let { "배터리 $it%" },car.fuelPercent?.takeIf { fresh(car.fuelUpdatedAt) }?.let { "연료 $it%" }).joinToString(" · ").ifBlank { "지원 정보 미수신" })
        BoardFact("주행 가능 거리",car.rangeKm?.takeIf { fresh(car.rangeUpdatedAt) }?.let { "${it.toInt()} km" } ?: "미수신")
        BoardFact("운행 거리 · 전비/연비","확인된 기록 없음")
        Divider(); Text("확인된 운행·정차",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        BoardFact("수신된 속도 구간", if(s.segments.isEmpty()) "집계할 수신 기록 없음" else "주행 ${s.segments.filter { it.moving }.sumOf { it.end-it.start }/60000}분 · 정차 ${s.segments.filter { !it.moving }.sumOf { it.end-it.start }/60000}분")
        Text("수신된 구간만 집계 · 정차 용도 미확인",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        s.segments.takeLast(5).forEach { BoardFact("${timeText(it.start)}–${timeText(it.end)}",if(it.moving) "주행 기록" else "정차 · 활동 미확인") }
        Divider(); Text("남은 업무",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
        l.visits.forEach { v ->
            val ts=l.tasks.filter { it.visitId==v.id }
            BoardFact("${v.round}회차 · ${v.name}", ts.groupBy { it.kind }.entries.joinToString(" · ") { (kind,items)->"${kind.label} ${items.sumOf { it.remaining }}${items.first().unit}" })
        }
        BoardFact("전송 대기","${l.pendingCount}건")
        BoardFact("업무 종료",if(l.dayClosed) "업무 시스템에서 종료 수신" else "종료 정보 미수신")
        BoardFact("오늘 운전",if(l.drivingEnded) "종료 기록 수신 · 미완료 운송 보존" else "종료 기록 미수신")
        if(l.refrigerated) {
            BoardTemperature(l,now)
            Text("최근 화물칸 기록",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
            if(l.cargoHistory.isEmpty()) Text("확인된 기록 없음",style=MaterialTheme.typography.bodySmall)
            l.cargoHistory.takeLast(6).reversed().forEach { record ->
                val sample = record.sample
                BoardFact("${timeText(sample.observedAt)} · ${l.visits.find { it.id == record.visitId }?.name ?: "화물칸"}",
                    if(!sample.available) "센서 확인 불가" else "${sample.measuredC?.let { "$it°C" } ?: "온도 미수신"}${if(record.outside) " · 요구 범위 이탈" else ""} · 문 ${when(sample.doorOpen) { true->"열림"; false->"닫힘"; null->"미확인" }}")
            }
            Text("최근 ${l.cargoHistory.size}건 보관 · 제품 내부 온도는 확인할 수 없습니다",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun BoardMap(s: BoardState, modifier: Modifier) {
    val l=s.ledger ?: return
    val route=s.route
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics { isTraversalGroup=true; traversalIndex=1f }) {
        Row(Modifier.fillMaxWidth().padding(24.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            CarbonIcon(R.drawable.ic_location,null)
            Text(l.current?.name ?: "목적지 대기",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clearAndSetSemantics { contentDescription="예시 이동 지도. ${l.current?.name}. ${if(route?.status==GuidanceStatus.ARRIVED) "도착" else "이동 체험"}" }) {
            OfflineDemoMap(l.visits.indexOfFirst { it.id==l.currentVisitId }.coerceAtLeast(0)%3,false,route?.progress ?: 0f,Modifier.fillMaxSize(),followVehicle=true)
        }
        Text(when { l.drivingEnded->"오늘 운전 마침"; l.dayClosed->"오늘 업무 종료"; route?.status==GuidanceStatus.ARRIVED->"도착 · 업무 기록 수신 중"; route==null->"안내 준비"; else->"목적지로 이동 중" },Modifier.padding(24.dp),style=MaterialTheme.typography.titleMedium)
        Text("예시 이동 · © OpenStreetMap contributors · ODbL",Modifier.padding(horizontal=24.dp,vertical=12.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BoardSettings(s: BoardState, car: VehicleState, session: WorkSessionViewModel, now: Long, chooseMode: () -> Unit) {
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
        ActionButton("기본 내비와 함께 보기",Modifier.fillMaxWidth(),secondary=true,isSelected=s.navigationMode==GuidanceMode.NATIVE_SAMPLE,enabled=car.canWork,icon=R.drawable.ic_location) { session.board.navigationMode(GuidanceMode.NATIVE_SAMPLE); session.gateway.openNavigation() }
        ActionButton("앱 지도 · 이동 시험",Modifier.fillMaxWidth(),secondary=true,isSelected=s.navigationMode==GuidanceMode.DEMO,enabled=car.canWork,icon=R.drawable.ic_play) { session.board.navigationMode(GuidanceMode.DEMO) }
        Text("예시 배차의 기본 내비 목적지는 공식 SDK 시험 좌표입니다. 회사 목적지와 센서 정보는 별도 연동으로 받습니다.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        s.ledger?.let { BoardFact("마지막 업무 갱신",timeText(it.lastSyncAt)) }
    }
}
