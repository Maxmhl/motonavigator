package de.tetnavigator.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Minimale Touch-Fläche (Spezifikation 1.4: ≥ 64 dp). */
val MinTouch: Dp = 64.dp

private val ButtonShape = RoundedCornerShape(6.dp)

enum class ButtonKind { Primary, Secondary, Danger }

/**
 * Basis-Taste. Kritische Aktionen setzen [requireLongPress] – dann löst nur ein
 * Lang-Drücken (mit sichtbarem Füllbalken) aus, ein kurzer Tipp zeigt [onShortTapHint].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    requireLongPress: Boolean = false,
    onShortTapHint: (() -> Unit)? = null,
    height: Dp = 68.dp,
) {
    val c = TetTheme.colors
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(c.accent, c.onAccent, null)
        ButtonKind.Secondary -> Triple(c.surfaceRaised, c.text, BorderStroke(2.dp, c.outline))
        ButtonKind.Danger -> Triple(c.danger, c.onAccent, null)
    }
    val alpha = if (enabled) 1f else 0.4f
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val base = modifier
        .heightIn(min = maxOf(height, MinTouch))
        .widthIn(min = MinTouch)
        .clip(ButtonShape)
        .background(bg.copy(alpha = bg.alpha * alpha))
        .let { if (border != null) it.border(border, ButtonShape) else it }

    val interactive = if (!enabled) base else if (requireLongPress) {
        base
            .semantics {
                role = Role.Button
                contentDescription = "$text (lang drücken)"
                onLongClick { onClick(); true }
                onClick { onShortTapHint?.invoke(); true }
            }
            .pointerInput(onClick) {
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(LONG_PRESS_MS, easing = LinearEasing))
                    }
                    val up = waitForUpOrCancellation()
                    val done = progress.value >= 0.999f
                    job.cancel()
                    scope.launch { progress.snapTo(0f) }
                    if (done) onClick() else if (up != null) onShortTapHint?.invoke()
                }
            }
            .drawBehind {
                if (progress.value > 0f) {
                    drawRect(fg.copy(alpha = 0.25f), size = Size(size.width * progress.value, size.height))
                }
            }
    } else {
        base.combinedClickable(onClick = onClick, role = Role.Button)
    }

    Row(
        interactive.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp), tint = (iconTint ?: fg).copy(alpha = alpha))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text.uppercase(),
            style = TetTheme.type.button,
            color = fg.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

const val LONG_PRESS_MS = 900

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    TetButton(text, onClick, modifier, ButtonKind.Primary, enabled, icon)

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, iconTint: Color? = null) =
    TetButton(text, onClick, modifier, ButtonKind.Secondary, enabled, icon, iconTint)

/** Icon-Taste 64 × 64 dp, surface, 2 dp outline, Strich-Icon 30 dp. */
@Composable
fun TetIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tint: Color? = null,
) {
    val c = TetTheme.colors
    Box(
        modifier
            .size(MinTouch)
            .clip(ButtonShape)
            .background(if (active) c.accent else c.surface)
            .border(2.dp, if (active) c.accent else c.outline, ButtonShape)
            .combinedClickable(onClick = onClick, role = Role.Button)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(30.dp), tint = tint ?: if (active) c.onAccent else c.text)
    }
}

/** Panel/Karte: surface, 1 dp outline, Radius 8 dp. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    background: Color? = null,
    padding: PaddingValues = PaddingValues(14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = TetTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .clip(shape)
            .background(background ?: c.surface)
            .border(1.dp, borderColor ?: c.outline, shape)
            .let { if (onClick != null) it.combinedClickable(onClick = onClick).heightIn(min = MinTouch) else it }
            .padding(padding),
        content = content,
    )
}

/** Gestrichelte Fläche (Hinweis-Kachel, „DATEI WÄHLEN“). */
@Composable
fun DashedBox(
    modifier: Modifier = Modifier,
    color: Color = TetTheme.colors.outline,
    strokeWidth: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .let { if (onClick != null) it.combinedClickable(onClick = onClick, role = Role.Button) else it }
            .drawBehind {
                val sw = strokeWidth.toPx()
                drawRoundRect(
                    color,
                    topLeft = Offset(sw / 2, sw / 2),
                    size = Size(size.width - sw, size.height - sw),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** Header-Trenner: 2 dp gestrichelte Linie in outline. */
@Composable
fun DashedDivider(modifier: Modifier = Modifier, color: Color = TetTheme.colors.outline) {
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawLine(
            color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
            strokeWidth = size.height, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
        )
    }
}

enum class ChipKind { Ok, Warning, Danger, Neutral, Accent }

/** Status-Chip: Radius 4 dp, z. B. moss mit onAccent-Text „AUF TRACK · 4 m“. */
@Composable
fun StatusChip(text: String, kind: ChipKind, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = TetTheme.colors
    val (bg, fg) = when (kind) {
        ChipKind.Ok -> c.moss to c.onAccent
        ChipKind.Warning -> c.warning to c.onAccent
        ChipKind.Danger -> c.danger to c.onAccent
        ChipKind.Accent -> c.accent to c.onAccent
        ChipKind.Neutral -> c.bg.copy(alpha = 0.9f) to c.text
    }
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .let { if (kind == ChipKind.Neutral) it.border(1.dp, c.outline, RoundedCornerShape(4.dp)) else it }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(text.uppercase(), style = TetTheme.type.label.copy(fontSize = 13.sp), color = fg, maxLines = 1)
    }
}

/** Fortschrittsbalken: 8 dp hoch, Track surfaceRaised, Füllung accent. */
@Composable
fun TetProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = TetTheme.colors.accent) {
    val c = TetTheme.colors
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(2.dp)).background(c.surfaceRaised)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

/** Länder-Badge: 56 dp Kreis, 2 dp Rand (accent = aktiv, sand = sonst). */
@Composable
fun CountryBadge(code: String, active: Boolean, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val c = TetTheme.colors
    val col = if (active) c.accent else c.sand
    Box(modifier.size(size).border(2.dp, col, CircleShape), contentAlignment = Alignment.Center) {
        Text(code.uppercase(), style = TetTheme.type.button, color = col)
    }
}

/** Länderstempel (72 dp, leicht gedreht): gefahren = accent-Rand + %, offen = gestrichelt grau. */
@Composable
fun CountryStamp(code: String, percent: Int?, rotation: Float, modifier: Modifier = Modifier) {
    val c = TetTheme.colors
    val ridden = percent != null && percent > 0
    Box(
        modifier
            .size(72.dp)
            .rotate(rotation)
            .drawBehind {
                val sw = 2.dp.toPx()
                if (ridden) {
                    drawCircle(c.accent, radius = size.minDimension / 2 - sw / 2, style = Stroke(sw * 1.4f))
                    drawCircle(c.accent.copy(alpha = 0.5f), radius = size.minDimension / 2 - sw * 3, style = Stroke(sw * 0.6f))
                } else {
                    drawCircle(
                        c.outline, radius = size.minDimension / 2 - sw / 2,
                        style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(code.uppercase(), style = TetTheme.type.button, color = if (ridden) c.accent else c.sand)
            Text(
                if (ridden) "$percent %" else "offen",
                style = TetTheme.type.label.copy(fontSize = 11.sp),
                color = if (ridden) c.accent else c.sand,
            )
        }
    }
}

/** Kennzahl: Mini-Label (Stencil), Zahl (Barlow Bold), Einheit. */
@Composable
fun ValueCell(label: String, value: String, unit: String, modifier: Modifier = Modifier, valueColor: Color? = null, large: Boolean = false) {
    val c = TetTheme.colors
    Column(modifier) {
        Text(label.uppercase(), style = TetTheme.type.label, color = c.sand, maxLines = 1)
        Text(value, style = if (large) TetTheme.type.numberL else TetTheme.type.numberM, color = valueColor ?: c.text, maxLines = 1)
        Text(unit, style = TetTheme.type.bodySmall, color = c.sand, maxLines = 1)
    }
}

/** Ampelpunkt für Checks (Tank/Licht/Gefahren). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).clip(CircleShape).background(color))
}

/** Bildschirm-Kopf: optional Zurück-Taste, Stencil-Titel, Untertitel, Aktion rechts, gestrichelter Trenner. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = TetTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                TetIconButton(TetIcons.Back, "Zurück", onBack)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), style = TetTheme.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = TetTheme.type.bodySmall, color = c.sand, maxLines = 2)
            }
            if (action != null) Row(verticalAlignment = Alignment.CenterVertically, content = action)
        }
        DashedDivider()
    }
}

/** Abschnitts-Label (Stencil, sand). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = TetTheme.type.label, color = TetTheme.colors.sand, modifier = modifier.padding(top = 14.dp, bottom = 6.dp))
}

data class NavTab(val label: String, val icon: ImageVector)

/** Unten-Navigation: 3 Reiter, 72 dp hoch, aktiver Reiter accent + 3 dp Linie oben. */
@Composable
fun BottomNavBar(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = TetTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(c.bg)
            .drawBehind { drawLine(c.outline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
    ) {
        tabs.forEachIndexed { i, tab ->
            val active = i == selected
            val col = if (active) c.accent else c.sand
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(onClick = { onSelect(i) }, role = Role.Tab)
                    .drawBehind {
                        if (active) drawRect(c.accent, size = Size(size.width, 3.dp.toPx()))
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, null, Modifier.size(26.dp), tint = col)
                Spacer(Modifier.height(4.dp))
                Text(tab.label.uppercase(), style = TetTheme.type.label, color = col)
            }
        }
    }
}

/** Großer Schalter-Eintrag für Einstellungen (64 dp, ganze Zeile tippbar). */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = TetTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .combinedClickable(onClick = { onChange(!checked) }, role = Role.Switch)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TetTheme.type.bodyStrong, color = c.text)
            if (subtitle != null) Text(subtitle, style = TetTheme.type.bodySmall, color = c.sand)
        }
        Box(
            Modifier
                .size(width = 64.dp, height = 36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (checked) c.accent else c.surfaceRaised)
                .border(2.dp, if (checked) c.accent else c.outline, RoundedCornerShape(18.dp))
                .padding(4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (checked) c.onAccent else c.sand))
        }
    }
}

/** Zahlen-Einsteller mit großen −/+ Tasten (handschuhtauglich statt Tastatur). */
@Composable
fun StepperRow(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val c = TetTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TetTheme.type.bodyStrong, color = c.text)
            if (subtitle != null) Text(subtitle, style = TetTheme.type.bodySmall, color = c.sand)
        }
        TetIconButton(TetIcons.Minus, "$title verringern", onMinus)
        Text(value, style = TetTheme.type.numberM.copy(fontSize = 26.sp), color = c.text, modifier = Modifier.widthIn(min = 84.dp), textAlign = TextAlign.Center)
        TetIconButton(TetIcons.Plus, "$title erhöhen", onPlus)
    }
}
