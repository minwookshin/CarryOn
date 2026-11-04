package com.minwook.pleoswork

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import kotlin.math.*

data class DemoMapFeature(val kind: String, val roadType: String, val name: String, val points: List<List<Double>>)
data class DemoMapData(val features: List<DemoMapFeature>, val routes: Map<String, List<List<Double>>>)

/** Metres on the local map, keeping east/west scale correct at Pangyo's latitude. */
data class DemoMapPoint(val x: Double, val y: Double) {
    fun distance(other: DemoMapPoint) = hypot(other.x - x, other.y - y)
}
fun demoMapPoint(coordinate: List<Double>) = DemoMapPoint((coordinate[0] - 127.094) * 88400, (37.413 - coordinate[1]) * 111200)
fun demoMapRouteKey(index: Int, returning: Boolean) = if (returning) "${index + 1}-0" else "$index-${index + 1}"
fun demoMapPosition(points: List<DemoMapPoint>, progress: Float): DemoMapPoint {
    require(points.isNotEmpty())
    val lengths = points.zipWithNext { a, b -> a.distance(b) }
    var left = lengths.sum() * progress.coerceIn(0f, 1f)
    for (i in lengths.indices) {
        if (left <= lengths[i] && lengths[i] > 0) {
            val t = left / lengths[i]
            return DemoMapPoint(points[i].x + (points[i + 1].x - points[i].x) * t,
                points[i].y + (points[i + 1].y - points[i].y) * t)
        }
        left -= lengths[i]
    }
    return points.last()
}

/** Real, attributed offline map geometry; motion and dispatch names are explicitly simulated. */
@Composable fun OfflineDemoMap(index: Int, returning: Boolean, progress: Float, modifier: Modifier = Modifier, followVehicle: Boolean = false) {
    val context = LocalContext.current
    val data = remember(context) {
        context.resources.openRawResource(R.raw.pangyo_map).bufferedReader().use { Gson().fromJson(it, DemoMapData::class.java) }
    }
    val legKey = demoMapRouteKey(index, returning)
    val route = remember(data, legKey) { data.routes.getValue(legKey).map(::demoMapPoint) }
    val features = remember(data) { data.features.map { it to it.points.map(::demoMapPoint) } }
    // A new destination starts at its own origin; never animate backwards across the previous leg.
    val animated = key(legKey) { animateFloatAsState(progress, tween(950, easing = LinearEasing), label = "demo-map-position").value }
    val colors = MaterialTheme.colorScheme
    val ground = colors.surfaceVariant
    val building = colors.outlineVariant
    val road = colors.surface
    val ink = colors.primary
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    Canvas(modifier) {
        drawRect(ground)
        val minX = route.minOf { it.x } - 90; val maxX = route.maxOf { it.x } + 90
        val minY = route.minOf { it.y } - 90; val maxY = route.maxOf { it.y } + 90
        val movingPoint = demoMapPosition(route, animated)
        // A short, wide map follows the vehicle instead of shrinking the whole route to a tiny thumbnail.
        val scale = if (followVehicle) size.width / 650.0 else min(size.width / (maxX-minX), size.height / (maxY-minY))
        val midX = if (followVehicle) movingPoint.x else (minX+maxX)/2
        val midY = if (followVehicle) movingPoint.y else (minY+maxY)/2
        fun screen(p: DemoMapPoint) = Offset((size.width/2+(p.x-midX)*scale).toFloat(), (size.height/2+(p.y-midY)*scale).toFloat())
        fun path(points: List<DemoMapPoint>, close: Boolean = false) = Path().apply {
            points.forEachIndexed { i,p -> val q=screen(p); if(i==0) moveTo(q.x,q.y) else lineTo(q.x,q.y) }
            if(close) close()
        }
        clipRect {
            features.filter { it.first.kind != "road" }.forEach { (feature, points) ->
                drawPath(path(points,true), if(feature.kind=="park") building.copy(alpha=.45f) else building)
            }
            features.filter { it.first.kind == "road" }.forEach { (feature, points) ->
                val width = when(feature.roadType) { "primary","motorway" -> 14.dp.toPx(); "tertiary","residential" -> 9.dp.toPx(); else -> 5.dp.toPx() }
                drawPath(path(points), colors.outlineVariant, style=Stroke(width+2.dp.toPx(), cap=StrokeCap.Round, join=StrokeJoin.Round))
                drawPath(path(points), road, style=Stroke(width, cap=StrokeCap.Round, join=StrokeJoin.Round))
            }
            val routePath=path(route)
            drawPath(routePath, road, style=Stroke(13.dp.toPx(), cap=StrokeCap.Round, join=StrokeJoin.Round))
            drawPath(routePath, ink, style=Stroke(7.dp.toPx(), cap=StrokeCap.Round, join=StrokeJoin.Round))
            val occupied = mutableListOf<Rect>()
            features.filter { it.first.kind == "road" && it.first.name.isNotBlank() }
                .distinctBy { it.first.name }.forEach { (feature, points) ->
                    val at = screen(demoMapPosition(points,.5f))
                    val label = measurer.measure(feature.name, labelStyle)
                    val box = Rect(at.x-label.size.width/2f, at.y-24.dp.toPx()-label.size.height,
                        at.x+label.size.width/2f, at.y-24.dp.toPx())
                    if (box.left>16.dp.toPx() && box.right<size.width-16.dp.toPx() && box.top>16.dp.toPx() &&
                        box.bottom<size.height-16.dp.toPx() && occupied.none { it.overlaps(box.inflate(16.dp.toPx())) }) {
                        drawRoundRect(road.copy(alpha=.94f), Offset(box.left-5.dp.toPx(),box.top-3.dp.toPx()),
                            androidx.compose.ui.geometry.Size(box.width+10.dp.toPx(),box.height+6.dp.toPx()),
                            androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                        drawText(label, topLeft=box.topLeft)
                        occupied.add(box)
                    }
                }
            val destination=screen(route.last())
            drawCircle(road, 14.dp.toPx(), destination)
            drawCircle(ink, 9.dp.toPx(), destination)
            val position=screen(movingPoint)
            drawCircle(ink.copy(alpha=.13f), 27.dp.toPx(), position)
            drawCircle(road, 18.dp.toPx(), position)
            drawCircle(ink, 12.dp.toPx(), position)
            drawCircle(road, 4.dp.toPx(), position)
        }
    }
}
