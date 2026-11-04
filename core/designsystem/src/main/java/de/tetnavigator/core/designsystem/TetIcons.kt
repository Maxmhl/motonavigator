package de.tetnavigator.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Strich-Icons (24er Raster, 2 px Strich) – bewusst eigene, schlichte Formen,
 * damit keine Icon-Bibliothek nachgeladen werden muss.
 */
object TetIcons {
    private fun icon(name: String, vararg paths: String, filled: Set<Int> = emptySet()): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEachIndexed { i, p ->
            if (i in filled) {
                b.addPath(addPathNodes(p), fill = SolidColor(Color.Black))
            } else {
                b.addPath(
                    addPathNodes(p),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }

    val TurnRight = icon("turnRight", "M7 21V12a3 3 0 0 1 3-3h9", "M15 5l4 4-4 4")
    val TurnLeft = icon("turnLeft", "M17 21V12a3 3 0 0 0-3-3H5", "M9 5L5 9l4 4")
    val SharpRight = icon("sharpRight", "M7 21V8a3 3 0 0 1 6 0v8", "M9 13l4 4 4-4")
    val SharpLeft = icon("sharpLeft", "M17 21V8a3 3 0 0 0-6 0v8", "M7 13l4 4 4-4")
    val Straight = icon("straight", "M12 21V4", "M7 9l5-5 5 5")
    val UTurn = icon("uTurn", "M8 21V9a4 4 0 0 1 8 0v4", "M12 10l4 4 4-4")
    val Flag = icon("flag", "M5 21V4", "M5 4h12l-2 4 2 4H5")
    val Center = icon("center", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3", "M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", filled = setOf(5))
    val Layers = icon("layers", "M12 3l9 5-9 5-9-5z", "M3 13l9 5 9-5", "M3 17.5l9 4.5 9-4.5")
    val Mountain = icon("mountain", "M2 20l7-12 4 6 3-4 6 10z")
    val Map = icon("map", "M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3z", "M9 3v15", "M15 6v15")
    val Route = icon("route", "M6 20a2 2 0 1 0 0-4a2 2 0 1 0 0 4z", "M18 8a2 2 0 1 0 0-4a2 2 0 1 0 0 4z", "M7.5 16.5L16.5 7.5")
    val Book = icon("book", "M5 3h12a2 2 0 0 1 2 2v16H7a2 2 0 0 1-2-2z", "M9 8h6", "M9 12h6")
    val Back = icon("back", "M15 5l-7 7 7 7")
    val Forward = icon("forward", "M9 5l7 7-7 7")
    val Upload = icon("upload", "M12 15V3", "M7 8l5-5 5 5", "M4 15v5h16v-5")
    val Download = icon("download", "M12 3v12", "M7 10l5 5 5-5", "M4 15v5h16v-5")
    val Warning = icon("warning", "M12 3l10 18H2z", "M12 10v5", "M12 18v.5")
    val Camera = icon("camera", "M3 8h4l2-3h6l2 3h4v12H3z", "M12 10a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z")
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Minus = icon("minus", "M5 12h14")
    val Menu = icon("menu", "M4 6h16", "M4 12h16", "M4 18h16")
    val Pin = icon("pin", "M12 22s7-7 7-12a7 7 0 0 0-14 0c0 5 7 12 7 12z", "M12 7a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")
    val Settings = icon("settings", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M12 2v3", "M12 19v3", "M4.9 4.9l2.1 2.1", "M17 17l2.1 2.1", "M2 12h3", "M19 12h3", "M4.9 19.1L7 17", "M17 7l2.1-2.1")
    val Fuel = icon("fuel", "M4 21V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16", "M3 21h12", "M7 8h4", "M14 10h2a2 2 0 0 1 2 2v4a1.5 1.5 0 0 0 3 0V8l-3-3")
    val Sun = icon("sun", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v2", "M12 20v2", "M2 12h2", "M20 12h2", "M5 5l1.5 1.5", "M17.5 17.5L19 19", "M5 19l1.5-1.5", "M17.5 6.5L19 5")
    val Tent = icon("tent", "M3 20L12 4l9 16z", "M12 4v16", "M9 20l3-5 3 5")
    val Water = icon("water", "M12 3s6 7 6 11a6 6 0 0 1-12 0c0-4 6-11 6-11z")
    val Wrench = icon("wrench", "M14 6a4 4 0 0 0 5 5l-9 9a2 2 0 0 1-3-3l9-9a4 4 0 0 0-2-2z")
    val Cart = icon("cart", "M3 4h2l2 11h11l2-8H6", "M9 20a1 1 0 1 0 0-2a1 1 0 1 0 0 2z", "M17 20a1 1 0 1 0 0-2a1 1 0 1 0 0 2z")
    val Hut = icon("hut", "M3 11l9-7 9 7", "M5 10v10h14V10", "M10 20v-5h4v5")
    val Reverse = icon("reverse", "M7 4L3 8l4 4", "M3 8h13a4 4 0 0 1 0 8h-3", "M17 20l4-4-4-4")
    val Trash = icon("trash", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val Check = icon("check", "M5 12l5 5 9-10")
    val Close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val Note = icon("note", "M5 3h10l4 4v14H5z", "M14 3v5h5", "M8 13h8", "M8 17h5")
    val Battery = icon("battery", "M3 8h15v8H3z", "M21 11v2", "M6 11v2")
    val Bluetooth = icon("bluetooth", "M7 7l10 10-5 4V3l5 4L7 17")
    val Calendar = icon("calendar", "M4 6h16v14H4z", "M4 10h16", "M8 3v4", "M16 3v4")
    val Hazard = icon("hazard", "M12 3l10 18H2z", filled = setOf(0))
    val RecDot = icon("recDot", "M12 6a6 6 0 1 0 0 12a6 6 0 1 0 0-12z", filled = setOf(0))
    val Stop = icon("stop", "M6 6h12v12H6z", filled = setOf(0))
    val Navigate = icon("navigate", "M12 2l7 19-7-4-7 4z", filled = setOf(0))
    val Ford = icon("ford", "M2 16c2 0 2-1.5 4-1.5s2 1.5 4 1.5 2-1.5 4-1.5 2 1.5 4 1.5 2-1.5 4-1.5", "M2 20c2 0 2-1.5 4-1.5s2 1.5 4 1.5 2-1.5 4-1.5 2 1.5 4 1.5 2-1.5 4-1.5", "M12 3v8", "M9 8l3 3 3-3")
    val Gate = icon("gate", "M4 21V4", "M20 21V4", "M4 8h16", "M4 14h16", "M4 14l16-6")
    val Landslide = icon("landslide", "M2 20h20", "M4 20l6-12 4 6", "M15 17a1.5 1.5 0 1 0 0-3a1.5 1.5 0 1 0 0 3z", "M19 19a1 1 0 1 0 0-2a1 1 0 1 0 0 2z")
    val Blocked = icon("blocked", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M6 6l12 12")
}
