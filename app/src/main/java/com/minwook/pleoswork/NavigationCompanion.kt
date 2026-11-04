package com.minwook.pleoswork

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp

@Composable fun NavigationSummary(s: CompanionState, car: VehicleState, now: Long, open: () -> Unit) {
    val r = s.run ?: return
    val reading = linkedNavigation(r, car.navigation, now)
    val bound = r.activeNavigationBinding != null
    // An empty navigation service needs no permanent setup card on the work screen.
    if (!bound && car.navigation?.fresh(now) != true && car.navigationScreenMessage.isBlank()) return
    val changed = bound && reading == null && car.navigation?.fresh(now) == true
    val title = if (reading != null) {
        "도착까지 ${durationLabel(reading.seconds)} · ${"%.1f".format(reading.meters / 1000.0)} km"
    } else if (changed) "내비 목적지가 바뀌었습니다" else if (bound) "내비 수신 대기" else "방문지 내비 미연결"
    val detail = when {
        reading != null -> "기본 내비 수신 · 테스트 연결"
        car.navigationScreenMessage.isNotBlank() -> car.navigationScreenMessage
        changed -> "현재 안내 · ${car.navigation?.target?.name} · 연결 정보 확인"
        bound -> "시간·거리 미수신 · 방문지 정보 유지"
        car.navigation?.fresh(now) == true -> "기본 내비는 ${car.navigation.target.name} 안내 중"
        else -> "경로 안내와 함께 보기"
    }
    ContextRow(R.drawable.ic_location, title, detail, car.canWork, "navigation-summary", open)
}

/** Readable beside the native map. These are not native map overlays or truck-route guarantees. */
@Composable fun SiteQuickContext(s: CompanionState, car: VehicleState, open: (String) -> Unit) {
    s.site ?: return
    val entrance = s.entrance
    val dock = s.run?.result?.dock.orEmpty()
    val atSite = s.run?.atFacility == true
    val waiting = s.mode == ServiceMode.FREIGHT && dock.isBlank() && s.run?.returning != true
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        ContextRow(if (atSite) R.drawable.ic_truck else R.drawable.ic_location,
            if (atSite) "${if (waiting) "대기" else "정차"} · ${s.stoppingPlace()}"
            else "${entrance?.name ?: "진입구 미수신"} → ${s.stoppingPlace()}",
            if (atSite) "${if (waiting) "도크 배정 전" else s.bay?.side ?: "현장 안내 확인"} · 진입·정차 예시"
            else "${entrance?.let { "진입 높이 ${"%.1f".format(it.heightCm / 100.0)} m · " } ?: ""}현장 예시",
            car.canWork, "quick-site", { open("site") })
    }
}

@Composable private fun ContextRow(icon: Int, title: String, detail: String, enabled: Boolean, key: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 92.dp).carFocusOutline(key)
        .clickable(enabled = enabled, role = Role.Button, onClick = click)
        .semantics(mergeDescendants = true) {}.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CarbonIcon(icon, null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CarbonIcon(R.drawable.ic_arrow_right, null)
    }
}

@Composable fun NavigationConnectionPanel(s: CompanionState, car: VehicleState, repo: CompanionRepository,
    gateway: PleosGateway, now: Long, open: () -> Unit) {
    val r = s.run ?: return
    val reading = car.navigation?.takeIf { it.fresh(now) }
    val linked = linkedNavigation(r, car.navigation, now)
    Text(r.visitName, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
    ActionButton("기본 내비와 함께 보기", Modifier.fillMaxWidth(), enabled = car.canWork, icon = R.drawable.ic_location, onClick = open)
    Text("한 칸 창에서는 출발 준비·다음 방문지·센터 복귀 때 지도를 자동으로 엽니다. 확장 화면이라면 오른쪽 창 막대를 왼쪽으로 밀어 처음 한 번 나란히 배치하세요.", style = MaterialTheme.typography.bodyLarge)
    if (car.navigationScreenMessage.isNotBlank()) Text(car.navigationScreenMessage,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    Text("${if (linked != null) "연결됨" else "기본 내비 목적지"} · ${reading?.target?.name ?: "수신 대기"}", style = MaterialTheme.typography.titleMedium)
    if (reading != null) {
        Text(reading.target.address, style = MaterialTheme.typography.bodyLarge)
        Text("${durationLabel(reading.seconds)} · ${"%.1f".format(reading.meters / 1000.0)} km · 실제 내비 수신", style = MaterialTheme.typography.bodySmall)
    }
    val canBind = car.canWork && r.canConnectNavigation && reading != null
    if (r.activeNavigationBinding == null) {
        ActionButton("현재 목적지로 연결 · 테스트", Modifier.fillMaxWidth(), enabled = canBind,
            secondary = true, icon = R.drawable.ic_mobile) {
            repo.update { DemoWorkflow.bindNavigation(it, car.navigation, car.canWork, System.currentTimeMillis()) }
        }
    } else {
        Text("${r.activeNavigationBinding!!.target.name}의 시간·도착 신호를 사용합니다.", style = MaterialTheme.typography.bodyLarge)
        ActionButton("방문지 연결 해제", Modifier.fillMaxWidth(), secondary = true, enabled = car.canWork) {
            repo.update { if (car.canWork) it.copy(run = it.run?.copy(navigationBinding = null)) else it }
        }
    }
    ActionButton("내비 정보 새로 받기", Modifier.fillMaxWidth(), secondary = true, enabled = car.canWork,
        icon = R.drawable.ic_reset) { gateway.refreshNavigation() }
    Text("예시 배차에는 실제 좌표가 없습니다. 연결하면 화면의 목적지와 도착 시간을 현재 내비 기준으로 표시합니다. 진입구·정차 위치는 가상 현장 예시이며 실제 목적지의 시설 정보가 아닙니다.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
