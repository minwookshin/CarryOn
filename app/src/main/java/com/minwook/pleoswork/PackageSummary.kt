package com.minwook.pleoswork

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Dispatch counts, never inferred from weight or from a total-only terminal receipt. */
@Composable fun PackageSummary(stop: DispatchStop, label: String = "${stop.operation} 예정") {
    val packages = stop.parcel?.packages ?: stop.freight?.packages.orEmpty()
    val pickups = stop.parcel?.pickups ?: 0
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (packages.isEmpty()) {
            // An actual terminal may supply only a total. Do not invent a size distribution.
            Text(stop.quantity, style = MaterialTheme.typography.headlineMedium)
        } else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val available = maxWidth.value / LocalDensity.current.fontScale
            val columns = if (available >= 720) 3 else if (available >= 440) 2 else 1
            val items = packages.map { item ->
                PackageMetricItem(item.kind.label, item.count, "개",
                    if (item.kind == PackageKind.CONTAINER) R.drawable.ic_container else R.drawable.ic_delivery,
                    when (item.kind) { PackageKind.LARGE -> 88; PackageKind.SMALL -> 60; PackageKind.CONTAINER -> 76; else -> 72 })
            } + if (pickups > 0) listOf(PackageMetricItem("회수 예정", pickups, "건", R.drawable.ic_pickup, 76)) else emptyList()
            // Weighted rows avoid pixel rounding in FlowRow pushing an otherwise fitting cell down.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { item ->
                            PackageMetric(item.label, item.count, item.unit, item.icon, item.glyph, Modifier.weight(1f))
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private data class PackageMetricItem(val label: String, val count: Int, val unit: String, val icon: Int, val glyph: Int)

@Composable private fun PackageMetric(label: String, count: Int, unit: String, icon: Int, glyph: Int, modifier: Modifier) {
    Column(modifier.clearAndSetSemantics { contentDescription = "$label $count$unit" },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                CarbonIcon(icon, null, Modifier.size(glyph.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(count.toString(), Modifier.alignByBaseline(), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
                Text(unit, Modifier.alignByBaseline(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
