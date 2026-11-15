package com.minwook.pleoswork

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Keep dispatch order, including earlier unresolved stops and results awaiting confirmation. */
fun remainingRouteVisits(l: WorkLedger): List<LedgerVisit> = l.visits.filter { visit ->
    l.tasks.any { it.visitId == visit.id && it.needsAttention }
}

@Composable fun RemainingRouteCards(l: WorkLedger) {
    val visits = remainingRouteVisits(l)
    if (visits.isEmpty()) {
        Text("남은 업무가 없습니다", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge)
        return
    }
    val line = MaterialTheme.colorScheme.outlineVariant
    val blue = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth()) {
        visits.forEachIndexed { index, visit -> key(visit.id) {
            val active = visit.id == l.currentVisitId
            val cardGap = if(index == visits.lastIndex) 0.dp else 16.dp
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Canvas(Modifier.width(20.dp).fillMaxHeight().clearAndSetSemantics {}) {
                    val center = Offset(size.width / 2, (size.height - cardGap.toPx()).coerceAtLeast(0f) / 2)
                    if(index > 0) drawLine(line, Offset(center.x, 0f), center, 2.dp.toPx())
                    if(index < visits.lastIndex) drawLine(line, center, Offset(center.x, size.height), 2.dp.toPx())
                    drawCircle(if(active) blue else neutral, if(active) 7.dp.toPx() else 5.dp.toPx(), center)
                }
                Surface(Modifier.weight(1f).padding(bottom = cardGap)
                    .semantics(mergeDescendants = true) { if(active) stateDescription = "안내 중인 장소" },
                    color = if(active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(if(active) 2.dp else 1.dp, if(active) blue else line)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(visit.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                        Text(remainingQuantityText(l.tasks.filter { it.visitId == visit.id }),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } }
    }
}
