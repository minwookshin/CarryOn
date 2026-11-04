package com.minwook.pleoswork

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

@Composable
fun LegacyConnectedVehicleApp(s: CompanionState, car: VehicleState, repo: CompanionRepository, gateway: PleosGateway) {
    var panel by rememberSaveable { mutableStateOf("dispatch") }
    var previewId by rememberSaveable(s.driver?.id) { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var narrowDetail by rememberSaveable { mutableStateOf(false) }
    val focus = remember { WorkFocus() }
    val inputMode = LocalInputModeManager.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    LaunchedEffect(focus.pending) {
        val target = focus.pending?.second ?: return@LaunchedEffect
        withFrameNanos { }
        if (inputMode.inputMode == InputMode.Keyboard) focus.restore(target)
    }
    fun closeSettings() { settings = false; focus.moveTo("toolbar/settings") }
    fun returnToSite() { narrowDetail = false; focus.moveTo(if (panel == "link") "toolbar/link" else "site/$panel") }
    BackHandler(settings || narrowDetail) { if (settings) closeSettings() else returnToSite() }
    CompositionLocalProvider(LocalWorkFocus provides focus) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints {
                val wide = maxWidth >= 1300.dp
                Column(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalFocusScope provides "toolbar") {
                        Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control).padding(horizontal = 8.dp)
                            .semantics { isTraversalGroup = true; traversalIndex = 0f }, verticalAlignment = Alignment.CenterVertically) {
                            IconAction(R.drawable.ic_mobile, "연결된 기사·단말", isSelected = panel == "link" && !settings, focusKey = "link") {
                                panel = "link"; settings = false; narrowDetail = true; focus.moveTo("detail/back")
                            }
                            Text(s.driver?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            IconAction(R.drawable.ic_settings, "연결·데모 설정", isSelected = settings, focusKey = "settings") {
                                if (settings) closeSettings() else { settings = true; focus.moveTo("settings/back") }
                            }
                        }
                    }
                    Divider()
                    s.vehicleNotices(car).forEach { notice ->
                        Text(notice.message, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                    }
                    if (!car.canWork) {
                        Text("정차 후 P에서 조작할 수 있습니다", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
                    }
                    val select: (String) -> Unit = { value ->
                        panel = value; settings = false; narrowDetail = true; focus.moveTo("detail/back")
                    }
                    val overview: @Composable () -> Unit = {
                        CompositionLocalProvider(LocalFocusScope provides "site") {
                            SiteOverview(s, car.navigation?.takeIf { it.fresh(now) }, panel, select)
                        }
                    }
                    val detail: @Composable () -> Unit = {
                        if (settings) CompositionLocalProvider(LocalFocusScope provides "settings") {
                            CompanionSettings(s, car, repo, gateway, now, ::closeSettings)
                        } else CompositionLocalProvider(LocalFocusScope provides "detail") {
                            Column(Modifier.fillMaxSize().semantics { paneTitle = sitePanelTitle(panel, s.mode) }) {
                                SectionHeader(sitePanelTitle(panel, s.mode), ::returnToSite, "현장 항목으로 돌아가기")
                                Divider()
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(WorkDimens.inset), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    when (panel) {
                                        "dispatch" -> DispatchPanel(s, car, previewId, { previewId = it; narrowDetail = true }) { select("entrance") }
                                        "entrance" -> EntrancePanel(s, car, repo)
                                        "bay" -> BayPanel(s, car, repo)
                                        "link" -> DriverConnectionPanel(s, car, repo)

                                    }
                                }
                            }
                        }
                    }
                    if (wide) {
                        Row(Modifier.weight(1f).fillMaxWidth().semantics { isTraversalGroup = true; traversalIndex = 1f }) {
                            Box(Modifier.weight(.40f).fillMaxHeight().semantics { isTraversalGroup = true; traversalIndex = 0f }) { overview() }
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Box(Modifier.weight(.60f).fillMaxHeight().semantics { isTraversalGroup = true; traversalIndex = 1f }) { detail() }
                        }
                    } else {
                        Box(Modifier.weight(1f).semantics { isTraversalGroup = true; traversalIndex = 1f }) {
                            if (settings || narrowDetail) detail() else overview()
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CompanionHeading(title: String, source: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        source?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

internal fun gearLabel(gear: Int?): String = when (gear) { 4 -> "P"; 8 -> "D"; 2 -> "R"; 1 -> "N"; else -> "—" }
internal fun brakeLabel(on: Boolean?): String = when (on) { true -> "체결"; false -> "해제"; null -> "확인 불가" }

@Composable fun ParkingPanel(s: CompanionState, car: VehicleState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ParkingStatus(car)
        ParkingAngles(s)
    }
}

@Composable fun ParkingStatus(car: VehicleState) {
    val attention = car.canWork && car.parkingBrake != true
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(androidx.compose.ui.res.painterResource(if (attention) R.drawable.ic_warning else R.drawable.ic_car_front),
            null, Modifier.size(WorkDimens.icon), tint = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("주차 브레이크 ${brakeLabel(car.parkingBrake)}", style = MaterialTheme.typography.titleLarge,
                color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("기어 ${gearLabel(car.gear)} · ${if (attention) "차량에서 브레이크를 확인하세요" else if (!car.canWork) "정차 후 P에서 조작할 수 있습니다" else "차량 상태 수신"}",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun ParkingAngles(s: CompanionState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.demo) Text("기울기 · 예시 값", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.demo) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AngleReading("앞뒤", s.parking.pitch, true, Modifier.weight(1f))
                AngleReading("좌우", s.parking.roll, false, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("—", style = MaterialTheme.typography.headlineLarge)
                Text("경사 센서 미연결", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable private fun AngleReading(label: String, angle: Float, side: Boolean, modifier: Modifier) {
    val direction = if (side) { if (angle >= 0) "앞쪽 높음" else "뒤쪽 높음" } else { if (angle >= 0) "오른쪽 높음" else "왼쪽 높음" }
    val number = String.format(Locale.US, "%.1f°", abs(angle))
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp)
        .clearAndSetSemantics { contentDescription = "$label 기울기 $number, $direction, 데모 값" },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CarbonIcon(if (side) R.drawable.ic_truck else R.drawable.ic_car_front, null)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Text(number, style = MaterialTheme.typography.titleLarge)
        Text(direction, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun CargoPanel(s: CompanionState, car: VehicleState, repo: CompanionRepository) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min = 76.dp).padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            CarbonIcon(R.drawable.ic_inventory, null)
            Spacer(Modifier.width(16.dp))
            Text(if (!s.demo) "차량 연동 필요" else if (s.cargoOpen) "문 열림" else "문 닫힘", style = MaterialTheme.typography.titleMedium)
        }
        VehicleToggle("적재함 잠금", if (s.demo) s.cargoLocked else null,
            if (!s.demo) "미연결" else if (s.cargoLocked) "잠김" else "해제",
            if (s.cargoLocked && s.demo) R.drawable.ic_locked else R.drawable.ic_unlocked,
            enabled = s.canControl && car.canWork && !s.cargoOpen, focusKey = "lock") { locked ->
            repo.update { CompanionReducer.lock(it, locked, car.canWork) }
        }
        VehicleToggle("적재등", if (s.demo) s.cargoLight else null,
            if (!s.demo) "미연결" else if (s.cargoLight) "켜짐" else "꺼짐", R.drawable.ic_light,
            enabled = s.canControl && car.canWork, focusKey = "light") { on ->
            repo.update { CompanionReducer.light(it, on, car.canWork) }
        }
        if (s.demo && s.cargoOpen) Text("문을 닫으면 잠글 수 있습니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.refrigerated) {
            Spacer(Modifier.height(8.dp))
            Divider()
            CompanionHeading(if (s.run == null) "냉장·냉동" else "냉동기 제어", if (s.demo) "특장 예시" else "미연결")
            if (s.run == null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text("적재함 온도", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.airTemperature?.let { "$it°C" } ?: "—", style = MaterialTheme.typography.headlineMedium)
                }
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text("설정 온도", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (s.demo) "${s.targetTemperature}°C" else "—", style = MaterialTheme.typography.headlineMedium)
                }
            }
            s.dispatch?.current?.freight?.temperature?.let { requirement ->
                Text("화물 요구 온도 ${requirement.label} · 단말 예시", style = MaterialTheme.typography.bodyLarge)
                if (!requirement.contains(s.targetTemperature.toFloat())) Text("설정 온도가 화물 요구 범위 밖입니다", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            }
            VehicleToggle("냉동기", if (s.demo) s.coolingOn else null,
                if (!s.demo) "미연결" else if (s.coolingOn) "켜짐" else "꺼짐", R.drawable.ic_temperature,
                enabled = s.canControl && car.canWork, focusKey = "cooling") { on ->
                repo.update { CompanionReducer.cooling(it, on, car.canWork) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(-18, 4, 8).forEach { value ->
                    ActionButton("${value}°C", Modifier.width(160.dp), secondary = s.targetTemperature != value,
                        isSelected = s.demo && s.targetTemperature == value, enabled = s.canControl && car.canWork && s.coolingOn,
                        icon = R.drawable.ic_temperature) { repo.update { CompanionReducer.temperature(it, value, car.canWork) } }
                }
            }
        }
    }
}

@Composable private fun VehicleToggle(label: String, checked: Boolean?, value: String, icon: Int, enabled: Boolean, focusKey: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.listRow).background(MaterialTheme.colorScheme.surfaceVariant)
        .carFocusOutline(focusKey).toggleable(value = checked == true, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics(mergeDescendants = true) { stateDescription = value }
        .padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CarbonIcon(icon, null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Visual only. The entire row is one accessible switch and one keyboard target.
        Box(Modifier.size(width = 48.dp, height = 28.dp).background(if (checked == true && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
            Box(Modifier.padding(4.dp).size(20.dp).align(if (checked == true) Alignment.CenterEnd else Alignment.CenterStart).background(MaterialTheme.colorScheme.surface))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun CompanionSettings(s: CompanionState, car: VehicleState, repo: CompanionRepository, gateway: PleosGateway, now: Long, close: () -> Unit) {
    Column(Modifier.fillMaxSize().semantics { paneTitle = "연결·데모 설정" }) {
        SectionHeader("연결·데모", close, "현장 화면으로 돌아가기")
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("데모", Modifier.weight(1f), secondary = !s.demo, enabled = car.canWork, isSelected = s.demo, icon = R.drawable.ic_play) { repo.update { it.copy(demo = true) } }
                ActionButton("차량 API", Modifier.weight(1f), secondary = s.demo, enabled = car.canWork, isSelected = !s.demo, icon = R.drawable.ic_car_front) { repo.update { it.copy(demo = false) } }
            }
            if (s.demo) {
                Text("현장·차고·적재함 상태는 예시입니다. 기어·주차 브레이크와 연결한 내비의 시간·거리는 API 수신값입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VehicleToggle("내비 지연 예시", s.trafficDelayMinutes > 0, if (s.trafficDelayMinutes > 0) "+25분 · 예약 시간 비교" else "기본 도착 예상", R.drawable.ic_time, car.canWork && s.dispatch != null, "traffic") {
                    delayed -> repo.update { CompanionReducer.traffic(it, delayed, car.canWork) }
                }
                VehicleToggle("적재함 문 열림 예시", s.cargoOpen, if (s.cargoOpen) "열림" else "닫힘", R.drawable.ic_inventory, car.canWork, "door-example") {
                    open -> repo.update { CompanionReducer.door(it, open, car.canWork) }
                }
                VehicleToggle("현장 정보 수신 예시", s.terminalConnected, if (s.terminalConnected) "연결됨" else "끊김", R.drawable.ic_mobile, car.canWork, "terminal") {
                    connected -> repo.update { it.copy(terminalConnected = connected) }
                }
            } else {
                Text("경사 센서·ST1 적재함·물류 단말은 아직 연결되지 않았습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Divider()
            Text("차량 연결", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            ConnectionLine("기어", gearLabel(car.gear))
            ConnectionLine("주차 브레이크", brakeLabel(car.parkingBrake))
            ConnectionLine("운전석 문", when (car.doorPosition) { 0 -> "닫힘"; null -> "확인 불가"; else -> "열림" })
            ConnectionLine("내비게이션", if (car.navigation?.fresh(now) == true) "목적지 수신" else if (car.navigationStatus == "연결됨") "목적지 없음" else car.navigationStatus)
            ActionButton("연결 다시 확인", secondary = true, icon = R.drawable.ic_refresh) { gateway.refreshDoor(); gateway.refreshNavigation() }
        }
    }
}

@Composable private fun ConnectionLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
