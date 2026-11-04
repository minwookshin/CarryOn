package com.minwook.pleoswork

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

fun guidanceLabel(r: DemoRun): String {
    val leg = r.guidance
    return when {
        r.resting -> "휴식 중"
        r.dayEnded -> "오늘 운행 마침"
        r.stage == RunStage.ENDED -> "운행 종료"
        r.stage == RunStage.REVIEW -> if (r.parcelAway) "단말 결과 수신" else "처리 결과 확인"
        r.stage == RunStage.DEPARTURE -> "출발 준비"
        r.stage == RunStage.RETURNED -> "센터 반납·인계"
        r.stage == RunStage.WAITING -> "도크 배정 대기"
        r.stage == RunStage.WORKING -> if (r.parcelAway) "차 밖에서 작업 중" else "${r.stop.operation} 작업 중"
        leg?.status == GuidanceStatus.ARRIVED -> if (leg.mode == GuidanceMode.DEMO) "자동 도착 · 데모" else "자동 도착 · 기본 내비"
        r.showNavigation && leg?.status == GuidanceStatus.FAILED -> "안내 연결 확인"
        r.showNavigation && leg?.status == GuidanceStatus.REQUESTED -> "목적지 전달 중"
        r.showNavigation && leg?.paused == true -> "안내 일시정지"
        r.showNavigation && leg?.status == GuidanceStatus.ACTIVE -> "목적지 안내 중"
        r.showNavigation -> "안내 준비"
        else -> r.stage.label
    }
}

/** Offline OSM map and explicitly simulated motion; never a native GPS reading. */
@Composable fun DemoNavigationPane(s: CompanionState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val r = s.run ?: return
    val leg = r.guidance
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        .clearAndSetSemantics {
            traversalIndex = if (compact) 0f else 1f
            contentDescription = "이동 데모 지도. ${r.visitName}. ${guidanceLabel(r)}. 지도는 OpenStreetMap, 이동은 시뮬레이션입니다."
        }) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarbonIcon(R.drawable.ic_location, null)
            Text(r.visitName, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (r.stage == RunStage.ENDED) "복귀 완료" else if (leg?.status == GuidanceStatus.ARRIVED) "도착"
                else if (leg?.status == GuidanceStatus.ACTIVE) if (leg.paused) "일시정지" else "${leg.remainingSeconds}초"
                else "안내 준비",
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        OfflineDemoMap(r.index, r.returning, leg?.progress ?: 0f, Modifier.weight(1f).fillMaxWidth(), followVehicle = compact)
        Text("이동 데모 · © OpenStreetMap contributors · ODbL", Modifier.padding(horizontal=16.dp, vertical=8.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Destination identity leads; state and route provenance stay visually secondary. */
@Composable fun VisitHeading(title: String, status: String, detail: String? = null, progress: String? = null, error: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            progress?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun GuidedCurrentStop(s: CompanionState, car: VehicleState, now: Long, open: (String) -> Unit) {
    val r = s.run ?: return
    val leg = r.guidance
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WorkDimens.sectionGap)) {
        key("visit-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VisitHeading(r.visitName, guidanceLabel(r),
                    if (s.guidanceMode == GuidanceMode.NATIVE_SAMPLE) "내비 · ${leg?.target?.name ?: r.navigationTarget?.name ?: "좌표 미수신"} · 시험 배차" else null,
                    if (r.returning) null else "${r.index + 1} / ${r.plan.stops.size}", leg?.status == GuidanceStatus.FAILED)
                if (leg?.status == GuidanceStatus.FAILED)
                    Text(leg.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (!r.dayEnded && !r.resting && r.showNavigation && s.guidanceMode == GuidanceMode.NATIVE_SAMPLE) {
                    val reading = linkedNavigation(r, car.navigation, now)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (reading != null) CarbonIcon(R.drawable.ic_time, null)
                        Text(if (reading != null) "${durationLabel(reading.seconds)} · ${if (reading.meters < 1000) "${reading.meters} m" else "%.1f km".format(reading.meters / 1000.0)}"
                            else if (leg?.status == GuidanceStatus.REQUESTED && leg.issuedAt == 0L) "내비 연결 후 목적지를 전달합니다"
                            else if (leg?.status == GuidanceStatus.REQUESTED) "내비 응답 대기 · 30초 후 재시도 가능"
                            else if (leg?.status == GuidanceStatus.ARRIVED) "정차 후 P에서 현장 정보가 표시됩니다"
                            else "실제 내비의 시간·거리 수신 대기", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        if (r.stage == RunStage.ENDED) GuidanceFact("방문 완료", "${r.plan.stops.size}곳 · 센터 인계 완료")
        else if (!r.dayEnded && !r.resting) {
            if (r.returning) {
                GuidanceFact("센터 인계", if (s.mode == ServiceMode.PARCEL) "회수 ${r.collected}건 · 미배송 ${r.deferredDeliveries}개" else "회수 용기 ${r.result.units}개")
                if (r.handedOver) Text("반납·인계 확인됨", style = MaterialTheme.typography.bodyLarge)
            } else {
                key("packages") {
                    if (r.stage == RunStage.REVIEW) GuidanceFact("처리 수량",
                        r.stop.parcel?.let { "배송 ${r.result.deliveries}/${it.deliveries}개 · 회수 ${r.result.pickups}/${it.pickups}건" }
                            ?: "${r.result.units}/${r.stop.freight?.units}${r.stop.freight?.unit}")
                    else PackageSummary(r.stop, if (r.stage == RunStage.DEPARTURE) "적재 물량" else "${r.stop.operation} 예정")
                }
                key("storage") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GuidanceFact("적재 위치", r.stop.parcel?.zones?.joinToString(" · ") { "${it.name} ${it.units}개" } ?: r.stop.freight?.location.orEmpty())
                        if (r.stage == RunStage.WAITING) Text("도크 배정을 기다리고 있습니다", style = MaterialTheme.typography.bodyLarge)
                        if (r.result.dock.isNotBlank()) Text(r.result.dock, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (r.stage == RunStage.REVIEW && !r.ready) {
                    val needsReceipt = r.stop.operation == "하차" && r.result.units == r.stop.freight?.units
                    Text(if (needsReceipt) "인수 확인 대기" else if (r.parcelAway) "재탑승 후 미처리를 확인하세요"
                        else if (r.stop.parcel != null) "미배송 ${r.missingDeliveries}개 · 미회수 ${r.missingPickups}건" else "남은 물량을 단말에서 확인하세요",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (needsReceipt || r.parcelAway) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
                if (r.stage == RunStage.WORKING && r.parcelAway)
                    Text("배송·회수 결과 수신 대기", style = MaterialTheme.typography.bodyLarge)
                if (r.stage == RunStage.REVIEW && r.ready && r.parcelAway)
                    Text("재탑승 후 결과를 확인하세요", style = MaterialTheme.typography.bodyLarge)
                if (r.stage == RunStage.REVIEW && r.ready && !r.parcelAway && r.stop.parcel != null)
                    Text(if (r.result.reason.isBlank()) "미처리 없음" else "사유 인계됨", style = MaterialTheme.typography.bodyLarge)
                if (r.stage == RunStage.DEPARTURE)
                    Text("적재 고정 · 문 · 서류 확인 후 출발", style = MaterialTheme.typography.bodyLarge)
                if (r.result.reason.isNotBlank()) Text(r.result.reason, style = MaterialTheme.typography.bodySmall)
            }
            s.siteHint(car, now)?.let { hint ->
                Row(Modifier.fillMaxWidth().heightIn(min = WorkDimens.control)
                    .background(MaterialTheme.colorScheme.surfaceVariant).carFocusOutline("site-context")
                    .clickable(enabled = car.canWork, role = Role.Button, onClickLabel = "진입·정차 상세 보기") { open("site") }
                    .semantics(mergeDescendants = true) {}.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CarbonIcon(R.drawable.ic_truck, null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(hint.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(hint.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    CarbonIcon(R.drawable.ic_arrow_right, null)
                }
            }
        }
    }
}

@Composable private fun GuidanceFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable fun GuidanceSettings(s: CompanionState, car: VehicleState, repo: CompanionRepository, gateway: PleosGateway, now: Long, close: () -> Unit) {
    val r = s.run ?: return
    Text("목적지 안내", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
    ActionButton("앱 지도 · 이동 체험", Modifier.fillMaxWidth(), secondary = s.guidanceMode != GuidanceMode.DEMO,
        isSelected = s.guidanceMode == GuidanceMode.DEMO, enabled = car.canWork, icon = R.drawable.ic_play) {
        repo.update { GuidanceFlow.mode(it, GuidanceMode.DEMO, car.canWork) }
    }
    Text("이동 → 도착 → 단말 결과 수신 → 재탑승·미처리 확인 → 다음 방문지로 이어집니다. 각 구간은 약 18초이며 이동·도크·작업 결과는 로컬 시뮬레이션입니다.", style = MaterialTheme.typography.bodyLarge)
    ActionButton("기본 내비 · SDK 예제 경로", Modifier.fillMaxWidth(), secondary = s.guidanceMode != GuidanceMode.NATIVE_SAMPLE,
        isSelected = s.guidanceMode == GuidanceMode.NATIVE_SAMPLE, enabled = car.canWork, icon = R.drawable.ic_location) {
        repo.update { GuidanceFlow.mode(it, GuidanceMode.NATIVE_SAMPLE, car.canWork) }
    }
    Text("42dot ↔ 소프트웨어드림센터를 번갈아 안내합니다. 실제 배송 주소가 아닙니다. 안내 시작 시 현재 내비 경로를 바꾸며, 다음 방문 버튼도 다음 목적지를 전송합니다.", style = MaterialTheme.typography.bodyLarge)
    if (s.guidanceMode == GuidanceMode.NATIVE_SAMPLE) {
        Text("${car.navigationStatus} · ${car.routeMessage.ifBlank { "경로 요청 전" }}", style = MaterialTheme.typography.bodyLarge)
        ActionButton("기본 내비 열기", Modifier.fillMaxWidth(), secondary = true, enabled = car.canWork, icon = R.drawable.ic_location) { gateway.openNavigation() }
    }
    if (r.canConnectNavigation) ActionButton("현재 방문지 안내 시작", Modifier.fillMaxWidth(), enabled = car.canWork && !s.cargoOpen) {
        repo.update { GuidanceFlow.start(it, car.canWork, now) }; close()
    }
    Text("Pleos 공개 에뮬레이터는 지도 미제공·GPS 안내 개발 중입니다. 기본 내비의 자동 도착은 실제 SDK 도착 신호가 있어야 반영됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
