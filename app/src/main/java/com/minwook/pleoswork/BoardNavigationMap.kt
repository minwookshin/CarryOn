package com.minwook.pleoswork

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Navigation presentation for the existing offline map. Controls never change dispatch or route progress. */
@Composable fun BoardNavigationMap(s: BoardState, modifier: Modifier, compact: Boolean = false) {
    val ledger = s.ledger ?: return
    var follow by rememberSaveable { mutableStateOf(true) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    // A company-assigned destination starts in following mode; paging through work does not reset the camera.
    LaunchedEffect(ledger.streamId, ledger.currentVisitId) { follow = true; zoom = 1f }
    val colors = MaterialTheme.colorScheme
    val route = s.route?.takeIf { it.visitId == ledger.currentVisitId }
    val current = ledger.current
    val mapDescription = "예시 이동 지도. ${current?.name ?: "목적지 대기"}. ${if (route?.status == GuidanceStatus.ARRIVED) "도착" else "이동 체험"}"
    val inset = if (compact) PaddingValues(bottom = 36.dp) else PaddingValues(start = 24.dp, top = 24.dp, end = 124.dp, bottom = 260.dp)
    CompositionLocalProvider(LocalFocusScope provides "map") {
        Box(modifier.background(colors.surfaceVariant).semantics { isTraversalGroup = true; traversalIndex = -1f }) {
            if (current != null) OfflineDemoMap(
                ledger.visits.indexOfFirst { it.id == ledger.currentVisitId }.coerceAtLeast(0) % 3,
                false, route?.progress ?: 0f,
                Modifier.fillMaxSize().clearAndSetSemantics {
                    contentDescription = mapDescription
                    stateDescription = "${if (follow) "현재 위치 따라가기" else "전체 경로"}, 지도 배율 ${(zoom * 100).toInt()}퍼센트"
                }, followVehicle = follow, zoom = zoom, contentPadding = inset
            )
            if (!compact) {
                // This is a 2D camera toggle, not an unsupported 3D mode or a new navigation request.
                Surface(Modifier.align(Alignment.TopEnd).padding(24.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                    TextButton(onClick = { follow = !follow; zoom = 1f },
                        enabled = current != null,
                        modifier = Modifier.size(WorkDimens.control).carFocusOutline("view")
                            .semantics { contentDescription = if (follow) "전체 경로 보기" else "현재 위치 따라가기" },
                        shape = RectangleShape, colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) {
                        Text(if (follow) "2D" else "전체", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth().background(Color(0xFF161616)).padding(24.dp)
                            .semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (route?.status == GuidanceStatus.ARRIVED) "도착" else "현재 목적지",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFFC6C6C6))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CompositionLocalProvider(LocalContentColor provides Color.White) { CarbonIcon(R.drawable.ic_location, null) }
                                Text(current?.name ?: "배정 대기", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            }
                        }
                        Surface(color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
                            Text(mapStatus(s), Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Surface(Modifier.width(WorkDimens.control), border = BorderStroke(1.dp, colors.outlineVariant)) {
                        Column {
                            IconAction(R.drawable.ic_add, "지도 확대", enabled = current != null && zoom < 2.5f, focusKey = "zoom-in") { zoom = (zoom + .25f).coerceAtMost(2.5f) }
                            Divider()
                            IconAction(R.drawable.ic_remove, "지도 축소", enabled = current != null && zoom > .6f, focusKey = "zoom-out") { zoom = (zoom - .25f).coerceAtLeast(.6f) }
                            Divider()
                            IconAction(R.drawable.ic_crosshair, "현재 위치로 돌아가기", enabled = current != null, focusKey = "recenter") { follow = true; zoom = 1f }
                        }
                    }
                }
            }
            Text("예시 이동 · © OpenStreetMap contributors · ODbL",
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(colors.surfaceVariant.copy(alpha = .96f))
                    .padding(horizontal = 24.dp, vertical = if (compact) 4.dp else 12.dp),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}
