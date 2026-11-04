package de.tetnavigator.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.tetnavigator.core.common.Format
import de.tetnavigator.core.common.LatLon
import de.tetnavigator.core.designsystem.TetTheme
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Höhenprofil-Daten: Distanz (m) und Höhe (m) je Stützpunkt. */
class ProfileData(val dist: DoubleArray, val ele: DoubleArray) {
    val length get() = dist.lastOrNull() ?: 0.0
    val maxIdx: Int get() = ele.indices.maxByOrNull { ele[it] } ?: 0
    val hasData get() = dist.size >= 2

    /** Steigung in % an Position d (über ±100 m). */
    fun gradeAt(d: Double): Double {
        if (!hasData) return 0.0
        fun eleAt(x: Double): Double {
            val i = dist.indexOfFirst { it >= x }.let { if (it < 0) dist.size - 1 else it }.coerceAtLeast(1)
            val f = ((x - dist[i - 1]) / (dist[i] - dist[i - 1]).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            return ele[i - 1] + (ele[i] - ele[i - 1]) * f
        }
        val a = (d - 100).coerceAtLeast(0.0); val b = (d + 100).coerceAtMost(length)
        return if (b - a < 20) 0.0 else (eleAt(b) - eleAt(a)) / (b - a) * 100
    }

    companion object {
        /** Auf ≤ [maxPoints] reduzieren (für Canvas). */
        fun of(dist: List<Double>, ele: List<Double?>, maxPoints: Int = 400): ProfileData {
            val idx = dist.indices.filter { ele[it] != null }
            if (idx.size < 2) return ProfileData(DoubleArray(0), DoubleArray(0))
            val step = max(1, idx.size / maxPoints)
            val pick = idx.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
            // höchsten Punkt behalten
            val top = idx.maxBy { ele[it]!! }
            if (top !in pick) { pick += top; pick.sort() }
            if (pick.last() != idx.last()) pick += idx.last()
            return ProfileData(pick.map { dist[it] }.toDoubleArray(), pick.map { ele[it]!! }.toDoubleArray())
        }
    }
}

/** Höhenprofil: Fläche accent 25 %, Linie accent, höchster Punkt markiert, aktuelle Position. */
@Composable
fun ElevationProfile(data: ProfileData, modifier: Modifier = Modifier, currentM: Double? = null, height: Dp = 150.dp) {
    val c = TetTheme.colors
    Column(modifier) {
        if (!data.hasData) {
            Text("Keine Höhendaten in der GPX", style = TetTheme.type.bodySmall, color = c.sand)
            return@Column
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val minE = data.ele.min(); val maxE = data.ele.max()
            val range = max(50.0, maxE - minE)
            val lo = minE - range * 0.1; val hi = maxE + range * 0.15
            fun x(d: Double) = (d / data.length * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - lo) / (hi - lo) * size.height).toFloat()
            // Rasterlinien
            for (k in 1..3) {
                val yy = size.height * k / 4
                drawLine(c.outline, Offset(0f, yy), Offset(size.width, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            val line = Path()
            data.dist.indices.forEach { i -> if (i == 0) line.moveTo(x(data.dist[i]), y(data.ele[i])) else line.lineTo(x(data.dist[i]), y(data.ele[i])) }
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, c.accent.copy(alpha = 0.25f))
            drawPath(line, c.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val mi = data.maxIdx
            val mx = x(data.dist[mi]); val my = y(data.ele[mi])
            drawLine(c.sand, Offset(mx, my), Offset(mx, size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
            drawCircle(c.warning, 6.dp.toPx() / 1.2f, Offset(mx, my), style = Stroke(2.5.dp.toPx()))
            currentM?.let { d ->
                val cx = x(d.coerceIn(0.0, data.length))
                drawLine(c.text, Offset(cx, 0f), Offset(cx, size.height), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("km 0", style = TetTheme.type.bodySmall, color = c.sand)
            Text("Hoch ${Format.thousands(data.ele[data.maxIdx].toLong())} m · km ${Format.km0(data.dist[data.maxIdx])}", style = TetTheme.type.bodySmall, color = c.sand)
            Text("km ${Format.km0(data.length)}", style = TetTheme.type.bodySmall, color = c.sand)
        }
    }
}

data class SurfaceShare(val gravel: Double, val dirt: Double, val asphalt: Double, val unknown: Double) {
    val total get() = (gravel + dirt + asphalt + unknown).coerceAtLeast(1e-9)
}

/** Untergrund-Balken gestapelt + Legende mit %. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SurfaceBar(s: SurfaceShare, modifier: Modifier = Modifier) {
    val c = TetTheme.colors
    val parts = listOf(
        Triple("Schotter", s.gravel, c.surfaceGravel),
        Triple("Erde", s.dirt, c.surfaceDirt),
        Triple("Asphalt", s.asphalt, c.surfaceAsphalt),
        Triple("unbekannt", s.unknown, c.surfaceUnknown),
    )
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(2.dp))) {
            parts.filter { it.second > 0 }.forEach { (_, v, col) ->
                Box(Modifier.weight((v / s.total).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(col))
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            parts.forEach { (label, v, col) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(col))
                    Spacer(Modifier.width(6.dp))
                    Text("$label ${Format.percent(v / s.total)}", style = TetTheme.type.bodySmall, color = c.text)
                }
            }
        }
    }
}

/** Mini-Karte: Track (Start grün, Ende rot, geänderte Abschnitte gelb) auf Topo-Linien. */
@Composable
fun MiniMap(lines: List<List<LatLon>>, changed: List<List<LatLon>>, modifier: Modifier = Modifier) {
    val c = TetTheme.colors
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(c.mapBg)) {
        // dezente Topo-Linien
        for (k in 0 until 6) {
            val p = Path()
            val y0 = size.height * (k + 0.5f) / 6
            p.moveTo(0f, y0)
            var xx = 0f
            while (xx <= size.width) { p.lineTo(xx, y0 + 8f * kotlin.math.sin((xx / 60f) + k).toFloat()); xx += 12f }
            drawPath(p, c.contour, style = Stroke(1.5f))
        }
        val all = lines.flatten()
        if (all.size < 2) return@Canvas
        val minLat = all.minOf { it.lat }; val maxLat = all.maxOf { it.lat }
        val minLon = all.minOf { it.lon }; val maxLon = all.maxOf { it.lon }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val w = max((maxLon - minLon) * k, 1e-6); val h = max(maxLat - minLat, 1e-6)
        val pad = 14f
        val scale = min((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - w * scale) / 2; val oy = (size.height - h * scale) / 2
        fun pt(p: LatLon) = Offset((ox + (p.lon - minLon) * k * scale).toFloat(), (size.height - oy - (p.lat - minLat) * scale).toFloat())
        fun draw(l: List<LatLon>, col: Color, width: Float) {
            if (l.size < 2) return
            val path = Path().apply { moveTo(pt(l[0]).x, pt(l[0]).y); l.drop(1).forEach { lineTo(pt(it).x, pt(it).y) } }
            drawPath(path, col, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        lines.forEach { draw(it, c.accent, 3.dp.toPx()) }
        changed.forEach { draw(it, c.warning, 4.dp.toPx()) }
        drawCircle(c.startGreen, 5.dp.toPx(), pt(lines.first().first()))
        drawCircle(c.danger, 5.dp.toPx(), pt(lines.last().last()))
    }
}
