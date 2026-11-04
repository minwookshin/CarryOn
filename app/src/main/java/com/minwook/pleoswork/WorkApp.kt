package com.minwook.pleoswork

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable fun WorkApp(s: WorkState, car: VehicleState, repo: WorkRepository, gateway: PleosGateway, resize: (Boolean) -> Unit) {
    var panel by rememberSaveable { mutableStateOf("") }
    var parentPanel by rememberSaveable { mutableStateOf("") }
    val focus = remember { WorkFocus() }
    val inputMode = LocalInputModeManager.current
    val returnFocus = remember { mutableMapOf<String, String>() }
    fun entryFocus(): String {
        val first=if(car.canWork && s.scenario=="parcel" && s.phase=="field")
            s.fieldItems.firstOrNull {it.status=="pending"} ?: s.fieldItems.firstOrNull() else null
        return first?.let {"field-list/item/${it.id}"} ?: "main/entry"
    }
    fun showPanel(next: String, parent: String = "") {
        if(next.isNotEmpty()) focus.current?.let { returnFocus[next]=it }
        panel=next; parentPanel=parent
        focus.moveTo(if(next.isEmpty()) entryFocus() else if(next=="inventory") "inventory-list/back" else "panel/$next/back")
    }
    fun closePanel() {
        val target=returnFocus.remove(panel)
        panel=parentPanel; parentPanel=""
        focus.moveTo(target ?: if(panel.isEmpty()) entryFocus() else "panel/$panel/back")
    }
    LaunchedEffect(focus.pending) {
        val target=focus.pending?.second ?: return@LaunchedEffect
        withFrameNanos { }
        if(inputMode.inputMode == InputMode.Keyboard && !focus.restore(target))
            focus.restore(if(panel.isEmpty()) entryFocus() else "panel/$panel/back")
    }
    LaunchedEffect(s.phase,s.stopIndex,s.ended,s.dock,s.unloaded,s.signed) {
        if(panel.isEmpty() && focus.current!=null) focus.moveTo(entryFocus())
    }
    var itemId by rememberSaveable { mutableStateOf("") }
    var selectedStop by rememberSaveable { mutableIntStateOf(0) }
    val inventoryScroll=rememberScrollState()
    val fieldScroll=rememberScrollState()
    val itemDrafts=rememberSaveableStateHolder()
    fun showItem(id: String, inventory: Boolean) {
        itemId=id
        showPanel("item",if(inventory) "inventory" else "")
        returnFocus["item"]="${if(inventory) "inventory-list" else "field-list"}/item/$id"
    }
    fun showStop(index: Int, parent: String = "") {
        selectedStop=index
        showPanel("stop",parent)
        returnFocus["stop"]="schedule/stop/$index"
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while(true) { kotlinx.coroutines.delay(1000); now=System.currentTimeMillis() } }
    val localSeconds=java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY)*3600+it.get(java.util.Calendar.MINUTE)*60+it.get(java.util.Calendar.SECOND) }
    val parked=car.canWork && !s.ended
    val allowed=parked && s.restStartedAt==null
    val estimate=journeyEstimate(s,car,now)
    val arrivals=scheduleEstimates(s,car,now,localSeconds)
    fun recordItem(item: Parcel, status: String, proof: Boolean, reason: String) {
        val previous=repo.state.value
        repo.update { WorkReducer.record(it,item.id,status,proof,reason,car.canWork) }
        if(repo.state.value==previous) return
        val next=repo.state.value.fieldItems.firstOrNull {it.status=="pending"}
        if(parentPanel!="inventory" && status!="pending") {
            if(next!=null) {
                itemId=next.id
                returnFocus["item"]="field-list/item/${next.id}"
            } else closePanel()
        }
    }
    fun leaveField() {
        repo.update {JourneyReducer.review(it,allowed)}
        showPanel("")
    }
    @Composable fun ItemContent(item: Parcel) {
        itemDrafts.SaveableStateProvider(item.id) {
            ItemDetail(item,allowed && item.stop==s.stop.id && s.phase in setOf("field","return")) {status,proof,reason ->
                recordItem(item,status,proof,reason)
            }
        }
    }
    @Composable fun PanelContent() {
                        when(panel) {
                            "schedule" -> CompositionLocalProvider(LocalFocusScope provides "schedule") {
                                JourneySchedule(s,arrivals) { showStop(it,"schedule") }
                            }
                            "stop" -> StopDetails(s,selectedStop.coerceIn(s.routeStops.indices),arrivals)
                            "bundle" -> BundleEditor(s,allowed) { ids,order -> repo.update { WorkReducer.saveBundle(it,ids,order,car.canWork) };showPanel("") }
                            "inventory" -> CompositionLocalProvider(LocalFocusScope provides "inventory-list") {
                                ParcelList(s.parcels,true,scroll=inventoryScroll) { showItem(it,true) }
                            }
                            "item" -> s.parcels.find {it.id==itemId}?.let {ItemContent(it)}
                            "rest" -> RestPanel(s,parked) { repo.update { WorkReducer.toggleRest(it,System.currentTimeMillis(),car.canWork) } }
                            "settings" -> JourneySettings(s,car.canWork,select={ scenario -> repo.update { WorkState(scenario=scenario,demoMode=it.demoMode,demoClock=if(scenario=="freight") 12*3600+28*60 else 8*3600+48*60) };showPanel("") },connection={showPanel("connection","settings")},mode={demo ->repo.update { it.copy(demoMode=demo) }},reset={repo.update { WorkState(scenario=it.scenario,demoMode=it.demoMode,demoClock=if(it.scenario=="freight") 12*3600+28*60 else 8*3600+48*60) };showPanel("")})
                            "connection" -> ConnectionPanel(car,gateway,now)
                            "navigation" -> RoutePanel(s,car,gateway,allowed,now) { target ->repo.update { JourneyReducer.bind(it,target,allowed) };showPanel("") }
                            "energy" -> EnergyPanel(s,estimate,allowed,repo,gateway)
                            "demo" -> DemoPanel(s,allowed,repo) { showPanel("") }
                            "history" -> HistoryPanel(s)
                            "temperature" -> Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                                Text("냉동 구획 센서 미연결",style=MaterialTheme.typography.headlineMedium)
                                Text("화물 구획의 별도 온도 센서가 연결되면 표시됩니다.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
    }
    BackHandler(panel.isNotEmpty() || s.phase == "field") {
        if(panel.isNotEmpty()) closePanel() else repo.update { JourneyReducer.review(it,allowed) }
    }
    LaunchedEffect(car.gear,car.uxRestricted) {
        if(car.uxRestricted==true || (car.gear!=null && car.gear!=4)) repo.update {WorkReducer.stopRest(it,System.currentTimeMillis())}
    }
    LaunchedEffect(car.canWork) { if(!car.canWork) { itemId=""; if(panel !in listOf("settings","connection")) showPanel("") } }
    CompositionLocalProvider(LocalWorkFocus provides focus) {
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints {
            // Pleos portrait uses one app window, even when that window exceeds 700dp.
            val expanded=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE && maxWidth>=700.dp
            val inlineStop=expanded && panel=="stop"
            val inventoryWorkspace=panel=="inventory" || (panel=="item" && parentPanel=="inventory")
            val fieldWorkspace=s.scenario=="parcel" && s.phase=="field" && (panel.isEmpty() || (panel=="item" && parentPanel.isEmpty()))
            val inlinePanel=expanded && car.canWork && panel in setOf("stop","bundle","rest","settings","connection","navigation","energy","demo","history","temperature")
            LaunchedEffect(expanded) {
                if(expanded && panel=="schedule") {
                    selectedStop=s.stopIndex
                    showPanel("stop")
                }
                if(expanded && parentPanel=="schedule") parentPanel=""
            }
            Column(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalFocusScope provides "toolbar") {
                FlowRow(Modifier.fillMaxWidth().semantics { isTraversalGroup=true; traversalIndex=0f }.padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    IconAction(R.drawable.ic_delivery,"업무 홈",isSelected=panel.isEmpty()) { showPanel("") }
                    IconAction(R.drawable.ic_calendar,"운송 일정",isSelected=panel in setOf("schedule","stop")) {
                        selectedStop=s.stopIndex
                        showPanel(if(expanded) "stop" else "schedule")
                    }
                    IconAction(R.drawable.ic_inventory,"물품 목록",s.scenario=="parcel" && s.phase!="transit",isSelected=panel=="inventory" || parentPanel=="inventory") { showPanel("inventory") }
                    IconAction(R.drawable.ic_time,"휴식",parked && s.phase!="transit",isSelected=panel=="rest") { showPanel("rest") }
                    QuietButton(onClick={showPanel(if(s.demoMode) "demo" else "connection")},focusKey="데모 시각") { Text(if(s.demoMode) "데모 · ${clockLabel(s.demoClock)}" else "실제 API",style=MaterialTheme.typography.bodySmall) }
                    IconAction(R.drawable.ic_settings,"설정",isSelected=panel=="settings" || parentPanel=="settings") { showPanel("settings") }
                    IconAction(if(expanded) R.drawable.ic_collapse else R.drawable.ic_expand,if(expanded) "한 칸으로" else "두 칸으로",car.canWork,focusKey="window-size") { resize(!expanded) }
                }
                }
                Divider()
                CompositionLocalProvider(LocalFocusScope provides if(panel.isEmpty()) "main" else "panel/$panel") {
                Box(Modifier.weight(1f).semantics { isTraversalGroup=true; traversalIndex=1f }) {
                    if(expanded && car.canWork && (inventoryWorkspace || fieldWorkspace)) {
                        ParcelWorkspace(s,inventoryWorkspace,itemId.takeIf {panel=="item"},allowed,
                            if(inventoryWorkspace) inventoryScroll else fieldScroll,
                            select={showItem(it,inventoryWorkspace)},closeDetail={closePanel()},
                            exit={if(inventoryWorkspace) showPanel("") else leaveField()},
                            demoComplete={repo.update {JourneyReducer.completeDemoStop(it,allowed)}},detail={ItemContent(it)})
                    } else if(panel.isNotEmpty() && !inlinePanel) Panel(if(panel=="item" && parentPanel=="inventory") "물품 상세" else panelTitle(panel),{closePanel()},if(parentPanel=="schedule") "일정으로" else if(parentPanel.isEmpty()) "업무 화면으로" else "${panelTitle(parentPanel)}으로") {
                        PanelContent()
                    } else if(!car.canWork) Restricted(s,car) { showPanel("connection") }
                    else Row(Modifier.fillMaxSize()) {
                        if(expanded) CompositionLocalProvider(LocalFocusScope provides "schedule") {
                        Column(Modifier.weight(.34f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant).semantics { isTraversalGroup=true; traversalIndex=0f }) {
                            SectionHeader("오늘의 운송")
                            Divider()
                            Box(Modifier.weight(1f)) { JourneySchedule(s,arrivals,viewedStop=selectedStop.takeIf {inlineStop}) { showStop(it) } }
                            Text(if(s.scenario=="parcel") "배송 ${s.deliveryCount}/25 · 회수 ${s.pickupCount}/3" else "12팔레트 · ${if(s.signed) "인계 완료" else "인계 대기"}",Modifier.padding(24.dp),style=MaterialTheme.typography.titleMedium)
                        }
                        }
                        CompositionLocalProvider(LocalFocusScope provides if(inlinePanel) "panel/$panel" else "main") {
                        Column(Modifier.weight(.66f).fillMaxHeight().semantics { isTraversalGroup=true; traversalIndex=1f }) {
                            if(inlinePanel) Panel(panelTitle(panel),{closePanel()},if(parentPanel.isEmpty()) "업무 화면으로" else "${panelTitle(parentPanel)}으로") {
                                key(panel,if(panel=="stop") selectedStop else -1) { PanelContent() }
                            } else {
                            FlowSteps(s)
                            Box(Modifier.weight(1f).workFocus("entry").focusGroup().semantics { isTraversalGroup=true }) {
                                when {
                                    s.ended -> EndSummary(s) {showPanel("settings")}
                                    s.phase=="prepare" -> PreparePanel(s,estimate,arrivals.getOrNull(s.stopIndex),allowed,repo,navigation={showPanel("navigation")},energy={showPanel("energy")})
                                    s.phase=="transit" -> TransitPanel(s,estimate,arrivals.getOrNull(s.stopIndex),allowed,repo,localSeconds,navigation={showPanel("navigation")},energy={showPanel("energy")})
                                    s.scenario=="freight" -> FreightPanel(s,allowed,repo) { showPanel("navigation") }
                                    s.phase=="field" -> FieldPanel(s,allowed,fieldScroll,select={showItem(it,false)},back={leaveField()},demoComplete={repo.update {JourneyReducer.completeDemoStop(it,allowed)}})
                                    s.phase in setOf("return","finish") -> ReviewPanel(s,allowed,bundle={showPanel("bundle")},resume={repo.update {JourneyReducer.enterField(it,allowed)}},next={repo.update {WorkReducer.nextStop(it,car.canWork)}},end={repo.update {WorkReducer.end(it,car.canWork)}})
                                    else -> ArrivalPanel(s,allowed,bundle={showPanel("bundle")},field={repo.update {JourneyReducer.enterField(it,allowed)}},navigation={showPanel("navigation")},temperature={showPanel("temperature")})
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
}

private fun panelTitle(panel: String) = when(panel) {
    "schedule" -> "오늘의 정차"; "bundle" -> "묶음 수정"; "inventory" -> "물품 목록"
    "item" -> "현장 확인"; "rest" -> "휴식·중간 점검"; "settings" -> "설정"
    "connection" -> "차량 연결"; "navigation" -> "내비게이션"; "history" -> "작업 기록"
    "temperature" -> "냉동 구획"; "energy" -> "연료·배터리"; "demo" -> "데모 조작"; "stop" -> "정차 일정"; else -> ""
}

@Composable fun Panel(title: String, close: () -> Unit, backLabel: String = "업무 화면으로", content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().semantics { paneTitle=title }) {
        SectionHeader(title,close,backLabel)
        Divider()
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable fun SectionHeader(title: String, close: (() -> Unit)? = null, backLabel: String = "업무 화면으로") {
    Row(Modifier.fillMaxWidth().heightIn(min=WorkDimens.control).padding(horizontal=if(close!=null) 8.dp else WorkDimens.inset),
        verticalAlignment=Alignment.CenterVertically) {
        if(close!=null) {
            IconAction(R.drawable.ic_arrow_left,backLabel,focusKey="back",onClick=close)
            Spacer(Modifier.width(16.dp))
        }
        Text(title,Modifier.weight(1f).padding(vertical=12.dp).semantics {heading()},style=MaterialTheme.typography.titleLarge)
    }
}

@Composable private fun ArrivalPanel(s: WorkState, allowed: Boolean, bundle: () -> Unit, field: () -> Unit, navigation: () -> Unit, temperature: () -> Unit) {
    TaskLayout(actions={
        ActionButton("묶음 수정",Modifier.weight(1f),secondary=true,enabled=allowed,icon=R.drawable.ic_edit,onClick=bundle)
        ActionButton("현장 작업",Modifier.weight(2f),enabled=allowed && (s.bundle.isNotEmpty() || s.stop.pickups>0),onClick=field)
    }) {
        StopHeading(s)
        Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            TripValue(s.bundle.size.toString(),"가져갈 물품",Modifier.weight(1f),"개")
            TripValue(s.stopParcels.count {it.pickup && it.status=="pending"}.toString(),"회수 예정",Modifier.weight(1f),"건")
        }
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("적재 구역",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                s.stopParcels.filter {!it.pickup}.groupBy {it.zone}.forEach { (zone,items) ->
                    Row(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min=WorkDimens.control)
                        .carFocusOutline("zone/$zone").clickable(enabled=allowed,role=Role.Button,onClickLabel="가져갈 물품 수정",onClick=bundle).padding(16.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Text("$zone 구역",style=MaterialTheme.typography.titleMedium)
                        Text("${items.count {it.id in s.bundle}}개",Modifier.weight(1f).padding(start=16.dp),style=MaterialTheme.typography.titleLarge)
                        CarbonIcon(R.drawable.ic_edit,null)
                    }
                }
            }
        }
        QuietButton(onClick=navigation,focusKey="entrance") {
            CarbonIcon(R.drawable.ic_location,null);Spacer(Modifier.width(12.dp));Text("진입 위치·경로 보기")
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min=WorkDimens.control).carFocusOutline("temperature")
            .clickable(role=Role.Button,onClickLabel="냉동 구획 센서 정보 보기",onClick=temperature).padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            CarbonIcon(R.drawable.ic_temperature,null)
            Text("냉동 구획 · 센서 미연결",Modifier.weight(1f).padding(start=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            CarbonIcon(R.drawable.ic_arrow_right,null)
        }
    }
}
@Composable fun Metric(value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants=true) {}) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 56.sp, lineHeight = 64.sp, fontFamily = Plex)
            Text(" $unit", Modifier.padding(bottom = 9.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, focusKey: String = label, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control).carFocusOutline(focusKey).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp))
    }
}

@Composable private fun BundleEditor(s: WorkState, allowed: Boolean, save: (Set<String>, Boolean) -> Unit) {
    var ids by remember(s.stopIndex) { mutableStateOf(s.bundle.intersect(s.stopParcels.filter {it.status=="pending" && !it.pickup}.map {it.id}.toSet())) }
    var descending by remember { mutableStateOf(s.descending) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ids.size}개 선택", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            QuietButton(onClick = { descending = !descending }, enabled = allowed, focusKey="sort") { Text(if (descending) "높은 층부터 ↓" else "낮은 층부터 ↑") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            val rows = s.stopParcels.filter { !it.pickup && it.status == "pending" }.sortedBy { it.building + it.unit }.let { if (descending) it.reversed() else it }
            rows.forEach { item ->
                CheckRow("${item.reference} · ${item.zone} · ${item.building} ${item.unit}", item.id in ids, allowed, focusKey="bundle/${item.id}") { checked -> ids = if (checked) ids + item.id else ids - item.id }
                Divider()
            }
        }
        ActionButton("${ids.size}개 저장", Modifier.fillMaxWidth().padding(24.dp), enabled = allowed, icon = R.drawable.ic_save) { save(ids, descending) }
    }
}

@Composable private fun ReviewPanel(s: WorkState, allowed: Boolean, bundle: () -> Unit, resume: () -> Unit, next: () -> Unit, end: () -> Unit) {
    val remaining=s.stopParcels.count {it.status=="pending"}
    TaskLayout(hint=if(remaining>0) "이번 정차에 미처리 물품 ${remaining}건이 남아 있어요." else null,actions={
        if(s.phase!="finish") ActionButton("현장 작업으로",Modifier.weight(1f),secondary=true,enabled=allowed,onClick=resume)
        if(s.phase=="finish") ActionButton("업무 종료",Modifier.fillMaxWidth(),enabled=allowed && s.parcels.none {it.status=="pending"},onClick=end)
        else ActionButton(if(s.stopIndex<2) "다음 정차지" else "업무 검토",Modifier.weight(2f),enabled=allowed && s.stopResolved,onClick=next)
    }) {
        Text(if(s.phase=="finish") "오늘의 운송 확인" else s.stop.name,Modifier.semantics {heading()},style=MaterialTheme.typography.headlineLarge)
        if(s.phase!="finish") Text(s.stop.detail,style=MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            TripValue(s.deliveryCount.toString(),"오늘 배송 확인",Modifier.weight(1f),"개")
            TripValue(s.pickupCount.toString(),"오늘 회수 확인",Modifier.weight(1f),"건")
        }
        DataRow("전체 보류","${s.deferredCount}건")
        DataRow("전체 미처리","${s.parcels.count {it.status=="pending"}}건")
        if(s.phase!="finish") ActionButton("가져갈 물품 수정",Modifier.fillMaxWidth(),secondary=true,enabled=allowed,icon=R.drawable.ic_edit,onClick=bundle)
    }
}
@Composable private fun RestPanel(s: WorkState, allowed: Boolean, toggle: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.restStartedAt) { while (s.restStartedAt != null) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val seconds = (s.restMs + (s.restStartedAt?.let { (now-it).coerceAtLeast(0) } ?: 0)) / 1000
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(if (s.restStartedAt == null) "잠시 쉬어가세요" else "휴식 중", style = MaterialTheme.typography.headlineLarge)
        Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.displayLarge)
        Text(if(s.scenario=="parcel") "배송 ${s.deliveryCount}/25 · 회수 ${s.pickupCount}/3 · 보류 ${s.deferredCount}" else "화물 12팔레트 · ${if(s.signed) "인수 완료" else "운송 중"}")
        ActionButton(if(s.restStartedAt == null) "휴식 시작" else "휴식 종료", Modifier.fillMaxWidth(), enabled = allowed, icon = if(s.restStartedAt == null) R.drawable.ic_play else R.drawable.ic_pause, onClick = toggle)
    }
}

@Composable private fun Restricted(s: WorkState, car: VehicleState, details: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        CarbonIcon(R.drawable.ic_delivery, null)
        Spacer(Modifier.height(24.dp))
        Text(s.stop.name, style = MaterialTheme.typography.headlineLarge)
        Text(s.stop.detail, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Text(if (car.uxRestricted == true || (car.gear != null && car.gear != 4)) "주차 후 작업할 수 있어요" else "차량 상태를 확인하고 있어요", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(if(s.scenario=="parcel") "배송 ${s.deliveryCount}/25 · 회수 ${s.pickupCount}/3" else "화물 12팔레트", color = MaterialTheme.colorScheme.onSurfaceVariant)
        QuietButton(onClick = details,focusKey="connection") { Text("연결 상태 보기") }
    }
}

@Composable fun DataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants=true) {}.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontWeight = FontWeight.Medium)
    }
    Divider()
}

@Composable private fun ConnectionPanel(car: VehicleState, gateway: PleosGateway, now: Long) {
    val energy=journeyEstimate(WorkState(demoMode=false),car,now)
    val nav=car.navigation?.takeIf {it.fresh(now)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DataRow("Vehicle SDK", car.vehicleStatus)
        DataRow("NaviHelper", car.navigationStatus)
        DataRow("속도", car.speedKph?.let { "%.1f km/h".format(it) } ?: "확인되지 않음")
        DataRow("기어", when(car.gear) { 4 -> "P"; 8 -> "D"; 2 -> "R"; 1 -> "N"; null -> "확인되지 않음"; else -> car.gear.toString() })
        DataRow("주행 제한", when(car.uxRestricted) { true -> "활성"; false -> "해제"; null -> "확인되지 않음" })
        DataRow("연료",energy.fuelPercent?.let { "$it%" } ?: "미지원·미수신")
        DataRow("배터리",energy.batteryPercent?.let { "$it%" } ?: "미지원·미수신")
        DataRow("주행 가능 거리",energy.rangeKm?.let { "%.1f km".format(it) } ?: "미지원·미수신")
        DataRow("내비 목적지",nav?.target?.name ?: "미수신")
        DataRow("내비 남은 시간",durationLabel(nav?.seconds))
        DataRow("운전석 문 위치", car.doorPosition?.toString() ?: "확인되지 않음")
        ActionButton("차량 상태 새로고침", Modifier.fillMaxWidth(), secondary = true, icon = R.drawable.ic_refresh) { gateway.refreshDoor(); gateway.refreshEnergy(); gateway.refreshNavigation() }
        ActionButton("공식 예제 경로 요청 · 42dot",Modifier.fillMaxWidth(),secondary=true,enabled=car.canWork && car.navigationStatus=="연결됨",icon=R.drawable.ic_location) { gateway.requestSampleRoute() }
        if(car.routeMessage.isNotEmpty()) Text(car.routeMessage,style=MaterialTheme.typography.bodySmall)
        if (car.errors.isNotEmpty()) {
            Text("연결 응답", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            car.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable private fun HistoryPanel(s: WorkState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if(s.scenario=="parcel") "배송 ${s.deliveryCount}/25 · 회수 ${s.pickupCount}/3" else "화물 12팔레트 · ${if(s.signed) "인수 완료" else "인수 대기"}", style = MaterialTheme.typography.titleLarge)
        if (s.events.isEmpty()) Text("아직 작업 기록이 없어요", color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.events.forEach { Text(it); Divider() }
    }
}

@Composable private fun FreightPanel(s: WorkState, allowed: Boolean, repo: WorkRepository, navigation: () -> Unit) {
    var unloadProof by remember {mutableStateOf(false)}
    var signProof by remember {mutableStateOf(false)}
    TaskLayout(actions={
        ActionButton("경로 보기",Modifier.weight(1f),secondary=true,icon=R.drawable.ic_location,onClick=navigation)
        ActionButton(when { !s.unloaded->"하차 확인"; !s.signed->"인수 확인"; else->"인계 후 업무 종료" },Modifier.weight(2f),
            enabled=allowed && s.dock.isNotEmpty() && when { !s.unloaded->unloadProof; !s.signed->signProof; else->true },icon=R.drawable.ic_check) {
            repo.update {WorkReducer.freight(it,when { !s.unloaded->"unload"; !s.signed->"sign"; else->"finish" },true,allowed)}
        }
    }) {
        StopHeading(s)
        Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            TripValue("12","운송 물량",Modifier.weight(1f),"팔레트")
            TripValue(s.dock.ifEmpty {"—"},"배정 도크",Modifier.weight(1f))
        }
        if(s.dock.isEmpty()) {
            Text("배정 도크를 선택하세요",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                listOf("05","06","07").forEach { dock ->
                    ActionButton("${dock}번 도크",Modifier.weight(1f),secondary=true,enabled=allowed,icon=R.drawable.ic_check) {
                        repo.update {if(it.phase=="arrival" && !it.ended) it.copy(dock=dock) else it}
                    }
                }
            }
        } else if(!s.unloaded) {
            CheckRow("12팔레트 하차를 확인했어요",unloadProof,allowed) {unloadProof=it}
        } else if(!s.signed) {
            Text("하차 완료 · 인수 확인 대기",style=MaterialTheme.typography.titleLarge)
            CheckRow("인수자·인수증을 확인했어요",signProof,allowed) {signProof=it}
        } else Text("12팔레트 인수 확인 완료",style=MaterialTheme.typography.titleLarge)
    }
}
