package com.minwook.pleoswork

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Plex = FontFamily(Font(R.font.plex_regular, FontWeight.Normal), Font(R.font.plex_medium, FontWeight.Medium))

/** Shared app sizing; Pleos system chrome keeps its native dimensions. */
object WorkDimens {
    // Pleos iconography uses a 48 x 48dp base grid.
    val icon = 48.dp
    val control = 76.dp
    val listRow = 96.dp
    val inset = 24.dp
    val gap = 12.dp
    val sectionGap = 32.dp
}

@Composable fun CarbonTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF78A9FF), onPrimary = Color(0xFF161616),
        background = Color(0xFF161616), onBackground = Color(0xFFF4F4F4),
        surface = Color(0xFF161616), onSurface = Color(0xFFF4F4F4),
        surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFC6C6C6),
        outline = Color(0xFF6F6F6F), outlineVariant = Color(0xFF393939),
        error = Color(0xFFFF8389),
    ) else lightColorScheme(
        primary = Color(0xFF0F62FE), onPrimary = Color.White,
        background = Color.White, onBackground = Color(0xFF161616),
        surface = Color.White, onSurface = Color(0xFF161616),
        surfaceVariant = Color(0xFFF4F4F4), onSurfaceVariant = Color(0xFF525252),
        outline = Color(0xFF8D8D8D), outlineVariant = Color(0xFFE0E0E0),
        error = Color(0xFFDA1E28),
    )
    MaterialTheme(colorScheme = colors, typography = Typography(
        // Pleos' published size scale. Keep Carbon's Plex family, palette and surfaces.
        displayLarge = TextStyle(fontFamily = Plex, fontSize = 60.sp, lineHeight = 72.sp),
        displayMedium = TextStyle(fontFamily = Plex, fontSize = 56.sp, lineHeight = 68.sp),
        displaySmall = TextStyle(fontFamily = Plex, fontSize = 48.sp, lineHeight = 60.sp),
        headlineLarge = TextStyle(fontFamily = Plex, fontSize = 48.sp, lineHeight = 60.sp, fontWeight = FontWeight.Medium),
        headlineMedium = TextStyle(fontFamily = Plex, fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.Medium),
        headlineSmall = TextStyle(fontFamily = Plex, fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Medium),
        titleLarge = TextStyle(fontFamily = Plex, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontFamily = Plex, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
        titleSmall = TextStyle(fontFamily = Plex, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontFamily = Plex, fontSize = 28.sp, lineHeight = 38.sp),
        bodyMedium = TextStyle(fontFamily = Plex, fontSize = 26.sp, lineHeight = 36.sp),
        bodySmall = TextStyle(fontFamily = Plex, fontSize = 24.sp, lineHeight = 32.sp),
        labelLarge = TextStyle(fontFamily = Plex, fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontFamily = Plex, fontSize = 24.sp, lineHeight = 32.sp),
        labelSmall = TextStyle(fontFamily = Plex, fontSize = 20.sp, lineHeight = 28.sp),
    ), content = content)
}

@Composable fun CarbonIcon(icon: Int, label: String?, modifier: Modifier = Modifier.size(WorkDimens.icon)) {
    Icon(painterResource(icon), label, modifier)
}

/** The outline watches the control's existing focus target; it does not create a second tab stop. */
@Composable fun Modifier.carFocusOutline(focusKey: String? = null): Modifier {
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.primary
    val gap = MaterialTheme.colorScheme.surface
    return this.workFocus(focusKey).onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if(focused) {
                inset(1.dp.toPx()) { drawRect(ring, style=Stroke(2.dp.toPx())) }
                inset(3.dp.toPx()) { drawRect(gap, style=Stroke(2.dp.toPx())) }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun IconAction(icon: Int, label: String, enabled: Boolean = true, isSelected: Boolean? = null, focusKey: String = label, role: Role? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val tooltip = rememberTooltipState()
    val accessibility = LocalContext.current.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    // TalkBack already speaks the icon label. A focus tooltip creates another accessibility
    // window and can trap sequential reading at the toolbar on the Pleos emulator.
    LaunchedEffect(focused) { if(focused && accessibility?.isTouchExplorationEnabled != true) tooltip.show() else tooltip.dismiss() }
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = tooltip, focusable = false) {
        IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction,
            modifier = Modifier.size(WorkDimens.control).carFocusOutline(focusKey).semantics { isSelected?.let { this.selected = it }; role?.let { this.role = it } }) {
            Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
                CarbonIcon(icon, label)
                if(isSelected == true) Box(Modifier.align(Alignment.BottomCenter).padding(bottom=6.dp)
                    .size(width=WorkDimens.icon,height=3.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable fun QuietButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, focusKey: String? = null, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick=onClick, enabled=enabled,
        modifier=modifier.carFocusOutline(focusKey).sizeIn(minWidth=WorkDimens.control,minHeight=WorkDimens.control),
        shape=RectangleShape, content=content)
}

@Composable fun ActionButton(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, enabled: Boolean = true, icon: Int = R.drawable.ic_arrow_right, isSelected: Boolean? = null, focusKey: String = text, onClick: () -> Unit) {
    // A selected setting is not a call to action. Reserve solid blue for the next task.
    val primaryAction = !secondary && isSelected == null
    Button(onClick = onClick, enabled = enabled, modifier = modifier.carFocusOutline(focusKey).sizeIn(minWidth = WorkDimens.control, minHeight = WorkDimens.control).semantics { isSelected?.let { selected=it } },
        shape = RectangleShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        border = if (primaryAction) null else BorderStroke(if (isSelected == true) 2.dp else 1.dp,
            if (!enabled) MaterialTheme.colorScheme.outlineVariant else if (isSelected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primaryAction) MaterialTheme.colorScheme.primary else if (isSelected == true) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            contentColor = if (primaryAction) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(16.dp))
        CarbonIcon(if (isSelected == true) R.drawable.ic_check else icon, null)
    }
}

@Composable fun Divider() { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
