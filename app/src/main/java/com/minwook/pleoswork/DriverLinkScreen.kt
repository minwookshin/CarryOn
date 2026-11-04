package com.minwook.pleoswork

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.floor

@Composable
fun VehicleCompanionApp(s: CompanionState, car: VehicleState, repo: CompanionRepository, gateway: PleosGateway) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            repo.update { it.copy(link = DriverLinkReducer.expire(it.link, now)) }
            kotlinx.coroutines.delay(1000)
        }
    }
    LaunchedEffect(s.link.phase, s.link.challenge?.id, car.canWork) {
        if (s.link.phase == LinkPhase.LOADING && car.canWork) {
            val id = s.link.challenge?.id ?: return@LaunchedEffect
            kotlinx.coroutines.delay(1000)
            repo.update { it.copy(link = DriverLinkReducer.finish(it.link, id, car.canWork)) }
        }
    }
    if (s.driver == null) DriverLinkScreen(s, car, repo, now)
    else key(s.driver!!.id) { ConnectedVehicleApp(s, car, repo, gateway) }
}

@Composable private fun DriverLinkScreen(s: CompanionState, car: VehicleState, repo: CompanionRepository, now: Long) {
    var terminal by rememberSaveable { mutableStateOf(false) }
    var chosenId by rememberSaveable { mutableStateOf(demoDrivers.first().id) }
    var failureExample by rememberSaveable { mutableStateOf(false) }
    val focus = remember { WorkFocus() }
    val inputMode = LocalInputModeManager.current
    val phase = s.link.phase
    fun reset() { repo.newLink(car.canWork); terminal = false; failureExample = false }
    BackHandler(terminal) {
        if (phase == LinkPhase.APPROVAL || phase == LinkPhase.FAILED) reset() else terminal = false
    }
    LaunchedEffect(phase, terminal) {
        if (inputMode.inputMode != InputMode.Keyboard) return@LaunchedEffect
        withFrameNanos { }
        val target = when (phase) {
            LinkPhase.APPROVAL -> "terminal/이 차량에 연결"
            LinkPhase.FAILED -> "pair/배차 다시 받기"
            LinkPhase.EXPIRED -> "pair/새 QR 만들기"
            else -> if (terminal) "terminal/driver-$chosenId" else "pair/Mac에서 연결 체험"
        }
        focus.restore(target)
    }
    CompositionLocalProvider(LocalWorkFocus provides focus) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control).padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CarbonIcon(R.drawable.ic_mobile, null)
                    Text("단말 연결", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text("연결 데모", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Divider()
                if (!car.canWork) Text("정차 후 P에서 연결할 수 있습니다", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp))
                val pageTitle = when (phase) {
                    LinkPhase.APPROVAL -> "차량 연결 승인"
                    LinkPhase.LOADING -> "배차 불러오는 중"
                    LinkPhase.FAILED -> "배차 수신 실패"
                    LinkPhase.EXPIRED -> "QR 만료"
                    else -> if (terminal) "기사 계정 선택" else "차량 QR 연결"
                }
                BoxWithConstraints(Modifier.weight(1f).semantics { paneTitle = pageTitle; isTraversalGroup = true }) {
                    val wide = maxWidth >= 1300.dp
                    val qr: @Composable () -> Unit = {
                        CompositionLocalProvider(LocalFocusScope provides "pair") {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                                verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(when (phase) {
                                    LinkPhase.APPROVAL -> "단말에서 연결을 확인해 주세요"
                                    LinkPhase.LOADING -> "배차를 불러오고 있어요"
                                    LinkPhase.FAILED -> "배차를 불러오지 못했어요"
                                    LinkPhase.EXPIRED -> "QR 유효 시간이 끝났어요"
                                    else -> "기사님 단말기로 연결하세요"
                                }, Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.headlineMedium)
                                if (phase == LinkPhase.WAITING) {
                                    Text("QR 스캔 후 단말에서 이 차량을 확인하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    s.link.challenge?.let { challenge ->
                                        QrImage(challenge.payload)
                                        val remaining = ((challenge.expiresAt - now).coerceAtLeast(0) / 1000).toInt()
                                        Text("유효 시간 %02d:%02d".format(remaining / 60, remaining % 60), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("연결된 기사님의 현장 정보만 표시합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!terminal) ActionButton("Mac에서 연결 체험", Modifier.widthIn(max = 460.dp).fillMaxWidth(), enabled = car.canWork, icon = R.drawable.ic_mobile) { terminal = true }
                                    QuietButton(onClick = ::reset, enabled = car.canWork, focusKey = "renew") { CarbonIcon(R.drawable.ic_refresh, null); Spacer(Modifier.width(12.dp)); Text("QR 새로고침") }
                                    Text("실제 단말 연동 전입니다. 예시 계정으로 흐름을 체험할 수 있어요.", Modifier.widthIn(max = 560.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (phase == LinkPhase.APPROVAL) {
                                    CarbonIcon(R.drawable.ic_mobile, null, Modifier.size(96.dp).padding(12.dp))
                                    Text("${s.link.pending?.name} · ${s.link.pending?.carrier}", style = MaterialTheme.typography.titleLarge)
                                    Text("아직 차량에 배차가 표시되지 않습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (phase == LinkPhase.LOADING) {
                                    CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 4.dp)
                                    Text("${s.link.pending?.name} 기사님", style = MaterialTheme.typography.titleLarge)
                                } else {
                                    CarbonIcon(if (phase == LinkPhase.EXPIRED) R.drawable.ic_time else R.drawable.ic_warning, null, Modifier.size(64.dp))
                                    Text(if (phase == LinkPhase.EXPIRED) "새 QR로 다시 연결해 주세요." else "연결 확인은 끝났습니다. 배차 수신을 다시 시도하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (phase == LinkPhase.FAILED) ActionButton("배차 다시 받기", Modifier.widthIn(max = 460.dp).fillMaxWidth(), enabled = car.canWork, icon = R.drawable.ic_refresh) {
                                        repo.update { it.copy(link = DriverLinkReducer.retry(it.link, car.canWork)) }
                                    }
                                    ActionButton("새 QR 만들기", Modifier.widthIn(max = 460.dp).fillMaxWidth(), secondary = phase == LinkPhase.FAILED, enabled = car.canWork, icon = R.drawable.ic_refresh, onClick = ::reset)
                                }
                            }
                        }
                    }
                    val phone: @Composable () -> Unit = {
                        CompositionLocalProvider(LocalFocusScope provides "terminal") {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                SectionHeader("단말 화면 · 데모", { if (phase == LinkPhase.APPROVAL) reset() else terminal = false }, "차량 QR로 돌아가기")
                                if (phase == LinkPhase.WAITING) {
                                    Text("로그인된 기사 계정", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    demoDrivers.forEach { driver ->
                                        ActionButton("${driver.name} · ${driver.carrier}", Modifier.fillMaxWidth(), secondary = chosenId != driver.id,
                                            isSelected = chosenId == driver.id, enabled = car.canWork, focusKey = "driver-${driver.id}", icon = if (driver.mode == ServiceMode.PARCEL) R.drawable.ic_delivery else R.drawable.ic_truck) { chosenId = driver.id }
                                    }
                                    ActionButton("QR 스캔 · 데모", Modifier.fillMaxWidth(), enabled = car.canWork, icon = R.drawable.ic_qr_code) {
                                        repo.update { it.copy(link = DriverLinkReducer.scan(it.link, it.link.challenge?.payload.orEmpty(), chosenId, SystemClock.elapsedRealtime(), car.canWork, failureExample)) }
                                    }
                                    QuietButton(onClick = { failureExample = !failureExample }, enabled = car.canWork, focusKey = "failure") {
                                        Text(if (failureExample) "수신 실패 예시 켜짐" else "수신 실패도 체험")
                                    }
                                } else if (phase == LinkPhase.APPROVAL) {
                                    Text("${s.link.pending?.name} 기사님", style = MaterialTheme.typography.headlineMedium)
                                    Text(s.link.pending?.carrier.orEmpty())
                                    Divider()
                                    Text("이 차량에 배차를 연결할까요?", style = MaterialTheme.typography.titleLarge)
                                    Text("ST1 · 데모 차량", style = MaterialTheme.typography.titleMedium)
                                    Text("차량에 방문 순서·물량·예약 시간과 현장 정보를 표시합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    ActionButton("이 차량에 연결", Modifier.fillMaxWidth(), enabled = car.canWork, icon = R.drawable.ic_mobile) {
                                        repo.update { it.copy(link = DriverLinkReducer.approve(it.link, SystemClock.elapsedRealtime(), car.canWork), entranceIndex = 0, bayIndex = 0, terminalConnected = true) }
                                    }
                                    ActionButton("취소", Modifier.fillMaxWidth(), secondary = true, enabled = car.canWork, icon = R.drawable.ic_close, onClick = ::reset)
                                } else {
                                    Text(s.link.pending?.name?.let { "$it 기사님" } ?: "새 QR로 연결해 주세요", style = MaterialTheme.typography.titleLarge)
                                    Text(if (phase == LinkPhase.LOADING) "차량에서 배차를 확인하는 중입니다." else "차량 화면에서 다시 시도할 수 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (terminal && wide) Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(.52f).fillMaxHeight()) { qr() }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.weight(.48f).fillMaxHeight().semantics { paneTitle = "단말 연결 체험" }) { phone() }
                    } else if (terminal && phase in setOf(LinkPhase.WAITING, LinkPhase.APPROVAL)) phone()
                    else qr()
                }
            }
        }
    }
}

@Composable private fun QrImage(payload: String) {
    val matrix = remember(payload) { pairingQr(payload) }
    Canvas(Modifier.sizeIn(maxWidth = 336.dp, maxHeight = 336.dp).size(336.dp).background(Color.White)
        .semantics { contentDescription = "차량 연결용 QR 코드, 5분간 유효한 데모 코드" }) {
        val cell = floor(size.minDimension / matrix.width)
        val inset = (size.minDimension - cell * matrix.width) / 2f
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(inset + x * cell, inset + y * cell), Size(cell, cell))
        }
    }
}

@Composable fun DriverConnectionPanel(s: CompanionState, car: VehicleState, repo: CompanionRepository) {
    var confirm by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    val focus = LocalWorkFocus.current
    val scope = LocalFocusScope.current
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(confirm) {
        if (changed && inputMode.inputMode == InputMode.Keyboard) {
            withFrameNanos { }
            focus?.restore("$scope/${if (confirm) "해제하고 새 기사 연결" else "기사 교대"}")
        }
    }
    val driver = s.driver ?: return
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("${driver.name} 기사님", style = MaterialTheme.typography.headlineMedium)
        Text("${driver.carrier} · ${driver.mode.label}", style = MaterialTheme.typography.titleLarge)
        Text(if (driver.assignment == null) "등록된 배차가 없습니다" else "이 기사님의 배차가 연결되었습니다", style = MaterialTheme.typography.bodyLarge)
        Divider()
        if (confirm) {
            Text("현재 기사님의 연결을 해제할까요?", style = MaterialTheme.typography.titleLarge)
            Text(if (s.run != null) "이 기사의 예시 진행 상태와 전송 대기 결과를 지우고 새 QR을 표시합니다. 차량 제어 상태는 유지됩니다." else "현재 방문지와 도크 선택을 지우고 새 QR을 표시합니다. 차량 제어 상태는 유지됩니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton("해제하고 새 기사 연결", Modifier.fillMaxWidth(), enabled = car.canWork, icon = R.drawable.ic_mobile) { repo.newLink(car.canWork) }
            ActionButton("취소", Modifier.fillMaxWidth(), secondary = true, icon = R.drawable.ic_close) { changed = true; confirm = false }
        } else {
            ActionButton("기사 교대", Modifier.fillMaxWidth(), enabled = car.canWork, icon = R.drawable.ic_mobile) { changed = true; confirm = true }
        }
    }
}
