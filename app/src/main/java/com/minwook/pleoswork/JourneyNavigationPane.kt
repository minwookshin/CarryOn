package com.minwook.pleoswork

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** An in-app notice does not interrupt the user's task or take modal focus. */
@Composable fun JourneyNavigationPane(
    state: CompanionState, notice: JourneyNotice?, modifier: Modifier = Modifier,
    compact: Boolean = false, dismiss: () -> Unit,
) {
    Box(modifier) {
        DemoNavigationPane(state, Modifier.fillMaxSize(), compact)
        if (notice != null) Surface(
            Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp).padding(top = 88.dp).fillMaxWidth(),
            shape = RectangleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp,
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CarbonIcon(R.drawable.ic_location, null)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(notice.title, style = MaterialTheme.typography.titleLarge)
                    Text(notice.body, style = MaterialTheme.typography.bodySmall)
                }
                CompositionLocalProvider(LocalFocusScope provides "map") {
                    IconAction(R.drawable.ic_close, "알림 닫기", focusKey = "dismiss-notice", onClick = dismiss)
                }
            }
        }
    }
}
