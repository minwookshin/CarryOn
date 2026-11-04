package com.minwook.pleoswork

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Keep the next action in a consistent position, outside the scrolling details. */
@Composable
fun TaskLayout(
    hint: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(WorkDimens.inset),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
        if(actions != null) {
        Divider()
        Column(Modifier.fillMaxWidth().padding(WorkDimens.inset), verticalArrangement = Arrangement.spacedBy(WorkDimens.gap)) {
            hint?.let { Text(it, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(12.dp), content=actions)
        }
        }
    }
}

@Composable
fun StopHeading(s: WorkState) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(s.stop.name, Modifier.semantics { heading() }, style=MaterialTheme.typography.headlineLarge)
        Text(s.stop.detail, style=MaterialTheme.typography.titleLarge)
        Text("예정 ${s.stop.time} · ${s.stop.entrance}", style=MaterialTheme.typography.bodyMedium,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TripValue(value: String, label: String, modifier: Modifier = Modifier, unit: String = "") {
    Column(modifier.semantics(mergeDescendants=true) {}, verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(label, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            Text(value, Modifier.alignByBaseline(), fontFamily=Plex, fontSize=40.sp, lineHeight=48.sp)
            if(unit.isNotEmpty()) Text(unit, Modifier.alignByBaseline().padding(start=6.dp),
                style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
