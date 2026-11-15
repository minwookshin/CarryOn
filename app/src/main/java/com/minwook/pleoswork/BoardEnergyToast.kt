package com.minwook.pleoswork

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable fun BoardEnergyToast(car: VehicleState, now: Long, restoreFocus: () -> Unit) {
    val alerts = remember { VehicleEnergyAlerts() }
    var notice by remember { mutableStateOf<VehicleEnergyAlert?>(null) }
    val accessibility = LocalAccessibilityManager.current
    val focus = LocalWorkFocus.current
    LaunchedEffect(car.fuelPercent, car.fuelUpdatedAt, car.batteryPercent, car.batteryUpdatedAt,
        car.rangeKm, car.rangeUpdatedAt, now) {
        alerts.takeNext(car, now, notice?.severity ?: 0)?.let { notice = it }
    }
    LaunchedEffect(notice?.id) {
        if(notice != null) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(8_000, containsIcons = true,
                containsText = true, containsControls = true) ?: 8_000)
            // Do not remove a keyboard user's focused dismiss button on a timer.
            while(focus?.current == "vehicle-notice/dismiss") delay(1000)
            notice = null
        }
    }
    notice?.let { alert ->
        val accent = if(alert.severity == 2) MaterialTheme.colorScheme.error else Color(0xFFF1C21B)
        Surface(Modifier.fillMaxWidth().padding(16.dp), shadowElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
                CarbonIcon(R.drawable.ic_warning, null, Modifier.padding(start = 16.dp).size(WorkDimens.icon))
                Column(Modifier.weight(1f).padding(16.dp).semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(alert.title, style = MaterialTheme.typography.titleSmall)
                    Text(alert.detail, style = MaterialTheme.typography.bodySmall)
                }
                CompositionLocalProvider(LocalFocusScope provides "vehicle-notice") {
                    IconAction(R.drawable.ic_close, "차량 알림 닫기", focusKey = "dismiss") {
                        val hadFocus = focus?.current == "vehicle-notice/dismiss"
                        notice = null
                        if(hadFocus) restoreFocus()
                    }
                }
            }
        }
    }
}
