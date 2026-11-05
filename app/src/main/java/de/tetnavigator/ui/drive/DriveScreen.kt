package de.tetnavigator.ui.drive

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.tetnavigator.R
import de.tetnavigator.core.common.Format
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.LatLon
import de.tetnavigator.core.database.HazardType
import de.tetnavigator.core.designsystem.ButtonKind
import de.tetnavigator.core.designsystem.ChipKind
import de.tetnavigator.core.designsystem.LocalGloveMode
import de.tetnavigator.core.designsystem.Panel
import de.tetnavigator.core.designsystem.PrimaryButton
import de.tetnavigator.core.designsystem.SecondaryButton
import de.tetnavigator.core.designsystem.StatusChip
import de.tetnavigator.core.designsystem.TetButton
import de.tetnavigator.core.designsystem.TetIconButton
import de.tetnavigator.core.designsystem.TetIcons
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.core.designsystem.ToggleRow
import de.tetnavigator.core.designsystem.ValueCell
import de.tetnavigator.feature.bedienung.RemoteAction
import de.tetnavigator.feature.logistik.FuelWarning
import de.tetnavigator.feature.navigation.DeviationMonitor
import de.tetnavigator.feature.navigation.ManeuverKind
import de.tetnavigator.feature.navigation.TurnSide
import de.tetnavigator.location.LocationService
import de.tetnavigator.map.ContourController
import de.tetnavigator.map.Contours
import de.tetnavigator.map.MapController
import de.tetnavigator.map.MapLayers
import de.tetnavigator.map.MapStyleBuilder
import de.tetnavigator.ui.Routes
import de.tetnavigator.ui.components.ElevationProfile
import java.io.File
import kotlin.math.roundToInt

private enum class Overlay { NONE, MENU, LAYERS, PROFILE, PIN, NOTE }

/** 1 · Fahr-Screen (Vollbild-Karte, Display immer an). */
@Composable
fun DriveScreen(nav: NavHostController, vm: DriveViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val c = TetTheme.colors
    val glove = LocalGloveMode.current
    val st by vm.nav.collectAsStateWithLifecycle()
    val track by vm.track.collectAsStateWithLifecycle()
    val fix by vm.fix.collectAsStateWithLifecycle()
    val aux by vm.aux.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val daylight by vm.daylight.collectAsStateWithLifecycle()
    val fuel by vm.fuel.collectAsStateWithLifecycle()
    val hazards by vm.hazards.collectAsStateWithLifecycle()
    val pois by vm.pois.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val allTracks by vm.allTracks.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val compass by vm.compass.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    var controller by remember { mutableStateOf<MapController?>(null) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }

    // Display an, GPS-Service an
    DisposableEffect(Unit) {
        vm.events.keepScreenOn.value = true
        LocationService.start(ctx)
        onDispose {
            vm.events.keepScreenOn.value = false
            LocationService.stopIfIdle(ctx)
        }
    }
    LaunchedEffect(Unit) { vm.toast.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }

    val registry by vm.storage.registry.collectAsStateWithLifecycle()
    val styleJson = remember(settings.layerHillshade, settings.layerContours, c, registry) {
        MapStyleBuilder.build(
            c, vm.storage.mapFiles(), vm.storage.hillshadeFiles(), vm.storage.contourFiles(),
            MapLayers(settings.layerHillshade, settings.layerContours),
            protomaps = vm.storage.protomapsFiles(), terrarium = vm.storage.terrariumFiles(),
        )
    }
    // Höhenlinien live aus den Höhendaten, wenn keine fertigen Höhenlinien-Pakete da sind
    val contourSource = remember(registry) {
        if (vm.storage.contourFiles().isEmpty()) ContourController(vm.storage.terrariumFiles().map { it.second }) else null
    }
    DisposableEffect(contourSource) { onDispose { contourSource?.close() } }
    LaunchedEffect(controller, contourSource, settings.layerContours) {
        val ctl = controller ?: return@LaunchedEffect
        val src = contourSource
        if (src == null || !src.available || !settings.layerContours) { ctl.setContours(emptyList(), 20); return@LaunchedEffect }
        val idle = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        ctl.onCameraIdle { idle.trySend(Unit) }
        idle.send(Unit)
        for (u in idle) {
            val (b, z) = ctl.visibleRegion()
            val lines = runCatching { src.contours(b[0], b[1], b[2], b[3], z) }.getOrDefault(emptyList())
            ctl.setContours(lines, Contours.intervalFor(z))
        }
    }

    // Remote-/Lautstärketasten
    LaunchedEffect(controller) {
        vm.events.remote.collect { a ->
            when (a) {
                RemoteAction.ZOOM_IN -> { controller?.follow = false; controller?.zoomBy(1.0) }
                RemoteAction.ZOOM_OUT -> { controller?.follow = false; controller?.zoomBy(-1.0) }
                RemoteAction.WAYPOINT -> vm.quickWaypoint()
                RemoteAction.TOGGLE_VIEW -> vm.cycleView()
                RemoteAction.CENTER -> { controller?.follow = true; vm.viewMode.value = ViewMode.FOLLOW }
            }
        }
    }

    // Overlays aktualisieren
    LaunchedEffect(controller, track, settings.layerSurface) { controller?.setTrack(track, settings.layerSurface) }
    LaunchedEffect(controller, allTracks, track) { controller?.setAllTracks(if (track == null) allTracks else emptyList()) }
    LaunchedEffect(controller, aux) { controller?.setAux(aux?.route?.points) }
    LaunchedEffect(controller, hazards, settings.layerHazards) { controller?.setHazards(hazards, settings.layerHazards) }
    LaunchedEffect(controller, pois, settings.layerPois) { controller?.setPois(pois, settings.layerPois) }
    LaunchedEffect(controller, st?.match?.alongM?.div(25)?.toInt()) { controller?.setRidden(track, st?.match?.alongM ?: 0.0) }
    LaunchedEffect(controller, viewMode, track) {
        if (viewMode == ViewMode.OVERVIEW) {
            val pts = (aux?.route?.points ?: track?.vertices?.map { LatLon(it.lat, it.lon) }).orEmpty()
            controller?.overview(pts)
        } else controller?.follow = true
    }
    val heading = (fix?.bearing ?: compass ?: 0f)
    LaunchedEffect(controller, fix, compass?.div(5)?.roundToInt(), viewMode) {
        val f = fix ?: return@LaunchedEffect
        controller?.setPosition(f.lat, f.lon, heading)
        if (viewMode != ViewMode.OVERVIEW) {
            val zoom = if (settings.autoZoom) st?.zoom ?: 15.5 else 15.5
            controller?.followCamera(f.lat, f.lon, heading.toDouble(), zoom, settings.headingUp && viewMode == ViewMode.FOLLOW)
        }
    }

    Box(Modifier.fillMaxSize().background(c.mapBg)) {
        TetMap(
            styleJson, c, gestures = !glove,
            onUserPan = { controller?.follow = false },
            onReady = { controller = it },
            modifier = Modifier.fillMaxSize(),
        )
        if (!vm.storage.hasAnyMap()) {
            Panel(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text(stringResource(R.string.no_map_installed), style = TetTheme.type.body, color = c.sand)
            }
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            TurnCard(st, track != null)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                track?.let { StatusChip("TET ${it.countryCode} · ${it.name}" + if (it.reversed) " ↺" else "", ChipKind.Neutral, Modifier.weight(1f, fill = false)) }
                Spacer(Modifier.width(8.dp))
                st?.let { s ->
                    when (s.deviation) {
                        DeviationMonitor.State.OFF_TRACK -> StatusChip(stringResource(R.string.off_track, s.deviationM.roundToInt()), ChipKind.Warning)
                        else -> StatusChip(stringResource(R.string.on_track, s.deviationM.coerceAtMost(999.0).roundToInt()), ChipKind.Ok)
                    }
                }
                if (fix == null) StatusChip(stringResource(R.string.waiting_gps), ChipKind.Neutral)
            }
            FuelBanner(fuel)
            aux?.let {
                Spacer(Modifier.height(8.dp))
                Panel(borderColor = c.warning, padding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (it.kind == de.tetnavigator.feature.navigation.AuxRoute.Kind.DETOUR) R.string.detour_active else R.string.back_route_active, Format.distance(it.route.distanceM)),
                            style = TetTheme.type.bodyStrong, color = c.text, modifier = Modifier.weight(1f),
                        )
                        TetIconButton(TetIcons.Close, stringResource(R.string.close), { vm.clearAux() })
                    }
                }
            }
        }

        // Rechts mittig: Zentrieren, Kartenebenen, Höhenprofil
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TetIconButton(TetIcons.Center, stringResource(R.string.center), { controller?.follow = true; vm.viewMode.value = ViewMode.FOLLOW }, active = viewMode == ViewMode.FOLLOW && controller?.follow == true)
            TetIconButton(TetIcons.Layers, stringResource(R.string.layers), { overlay = Overlay.LAYERS })
            TetIconButton(TetIcons.Mountain, stringResource(R.string.elevation_profile), { overlay = Overlay.PROFILE })
            if (glove) {
                TetIconButton(TetIcons.Plus, stringResource(R.string.zoom_in), { controller?.follow = false; controller?.zoomBy(1.0) })
                TetIconButton(TetIcons.Minus, stringResource(R.string.zoom_out), { controller?.follow = false; controller?.zoomBy(-1.0) })
            }
        }

        // Unten: Werte + MENÜ / PIN / REC
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Text(
                stringResource(R.string.osm_attribution),
                style = TetTheme.type.bodySmall.copy(fontSize = 12.sp), color = c.text,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp).background(c.bg.copy(alpha = 0.7f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            BottomPanel(
                speedKmh = ((fix?.speedMs ?: 0f) * 3.6f).roundToInt(),
                remaining = st?.remainingM,
                rangeKm = settings.rangeKm,
                rangeLow = fuel !is FuelWarning.Ok,
                daylightMs = daylight?.millisUntilDark,
                batteryText = if (settings.batteryMode) battery.remainingMs?.let { "Akku ${battery.percent} % · ${Format.hm(it)} h" } ?: "Akku ${battery.percent} %" else null,
                recording = rec != null,
                onMenu = { overlay = Overlay.MENU },
                onPin = { overlay = Overlay.PIN },
                onRec = { vm.toggleRecording() },
            )
        }

        when (overlay) {
            Overlay.NONE -> Unit
            Overlay.MENU -> Sheet({ overlay = Overlay.NONE }) {
                MenuContent(vm, nav, busy, hasTrack = track != null, routing = vm.session.routingAvailable) { overlay = it }
            }
            Overlay.LAYERS -> Sheet({ overlay = Overlay.NONE }) {
                Text(stringResource(R.string.layers).uppercase(), style = TetTheme.type.title, color = c.text)
                ToggleRow(stringResource(R.string.layer_hillshade), null, settings.layerHillshade) { v -> vm.setLayer { it.copy(layerHillshade = v) } }
                ToggleRow(stringResource(R.string.layer_contours), null, settings.layerContours) { v -> vm.setLayer { it.copy(layerContours = v) } }
                ToggleRow(stringResource(R.string.layer_surface), stringResource(R.string.layer_surface_sub), settings.layerSurface) { v -> vm.setLayer { it.copy(layerSurface = v) } }
                ToggleRow(stringResource(R.string.layer_pois), null, settings.layerPois) { v -> vm.setLayer { it.copy(layerPois = v) } }
                ToggleRow(stringResource(R.string.layer_hazards), null, settings.layerHazards) { v -> vm.setLayer { it.copy(layerHazards = v) } }
                ToggleRow(stringResource(R.string.day_mode), stringResource(R.string.day_mode_sub), settings.dayMode) { v -> vm.setLayer { it.copy(dayMode = v) } }
                ToggleRow(stringResource(R.string.heading_up), null, settings.headingUp) { v -> vm.setLayer { it.copy(headingUp = v) } }
            }
            Overlay.PROFILE -> Sheet({ overlay = Overlay.NONE }) {
                Text(stringResource(R.string.elevation_profile).uppercase(), style = TetTheme.type.title, color = c.text)
                Spacer(Modifier.height(8.dp))
                val along = st?.match?.alongM
                ElevationProfile(profile, currentM = along, height = 180.dp)
                if (along != null && profile.hasData) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.grade_now, profile.gradeAt(along).roundToInt()), style = TetTheme.type.bodyStrong, color = c.text)
                }
            }
            Overlay.PIN -> Sheet({ overlay = Overlay.NONE }) { PinContent(vm) { overlay = Overlay.NONE } }
            Overlay.NOTE -> Sheet({ overlay = Overlay.NONE }) { NoteContent(vm) { overlay = Overlay.NONE } }
        }
    }
}

@Composable
private fun TurnCard(st: de.tetnavigator.feature.navigation.NavState?, hasTrack: Boolean) {
    val c = TetTheme.colors
    val shape = RoundedCornerShape(6.dp)
    val off = st?.deviation == DeviationMonitor.State.OFF_TRACK
    val m = st?.maneuver
    val icon: ImageVector = when {
        off -> TetIcons.Navigate
        m == null -> TetIcons.Route
        m.kind == ManeuverKind.HAIRPIN -> if (m.side == TurnSide.RIGHT) TetIcons.SharpRight else TetIcons.SharpLeft
        m.kind == ManeuverKind.CURVE -> if (m.side == TurnSide.RIGHT) TetIcons.TurnRight else TetIcons.TurnLeft
        m.kind == ManeuverKind.FINISH -> TetIcons.Flag
        else -> TetIcons.Straight
    }
    val heading = st?.fix?.bearing ?: 0f
    val rot = if (off) ((st?.bearingBack ?: 0.0) - heading).toFloat() else 0f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(shape).background(c.surface).border(2.dp, if (off) c.warning else c.accent, shape),
    ) {
        Box(Modifier.width(84.dp).heightIn(min = 96.dp).background(if (off) c.warning else c.accent), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(54.dp).rotate(rot), tint = c.onAccent)
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).weight(1f), verticalArrangement = Arrangement.Center) {
            when {
                st == null && hasTrack -> Text(stringResource(R.string.searching_track), style = TetTheme.type.button, color = c.sand)
                st == null -> Text(stringResource(R.string.no_section_active), style = TetTheme.type.button, color = c.sand)
                off -> {
                    Text(Format.distance(st.deviationM), style = TetTheme.type.numberL, color = c.text)
                    Text(stringResource(R.string.back_to_track).uppercase(), style = TetTheme.type.label.copy(fontSize = 14.sp), color = c.warning)
                }
                else -> {
                    Text(Format.distance(st.maneuver.distanceM), style = TetTheme.type.numberL, color = c.text)
                    Text(st.maneuver.hint, style = TetTheme.type.label.copy(fontSize = 14.sp), color = c.sand, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FuelBanner(w: FuelWarning) {
    val c = TetTheme.colors
    val text = when (w) {
        FuelWarning.Ok -> return
        is FuelWarning.LastStationBeforeGap -> stringResource(R.string.fuel_last_station, Format.distance(w.stationInM), Format.km0(w.gapM))
        is FuelWarning.OutOfRange -> if (w.nextStationM != null) stringResource(R.string.fuel_out_of_range_station, Format.km0(w.rangeM), Format.km0(w.nextStationM!!)) else stringResource(R.string.fuel_out_of_range, Format.km0(w.rangeM))
        is FuelWarning.Low -> stringResource(R.string.fuel_low, Format.km0(w.rangeM))
    }
    Spacer(Modifier.height(8.dp))
    Panel(borderColor = c.warning, background = c.bg.copy(alpha = 0.92f), padding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TetIcons.Fuel, null, Modifier.size(24.dp), tint = c.warning)
            Spacer(Modifier.width(8.dp))
            Text(text, style = TetTheme.type.bodyStrong, color = c.warning)
        }
    }
}

@Composable
private fun BottomPanel(
    speedKmh: Int, remaining: Double?, rangeKm: Double, rangeLow: Boolean, daylightMs: Long?, batteryText: String?,
    recording: Boolean, onMenu: () -> Unit, onPin: () -> Unit, onRec: () -> Unit,
) {
    val c = TetTheme.colors
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(c.bg)
            .drawBehind { drawRect(c.accent, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())) }
            .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            ValueCell(stringResource(R.string.v_speed), "$speedKmh", "km/h", Modifier.weight(1f))
            ValueCell(stringResource(R.string.v_section), remaining?.let { Format.km1(it) } ?: "–", stringResource(R.string.u_km_left), Modifier.weight(1f))
            ValueCell(stringResource(R.string.v_tank), Format.thousands(rangeKm.toLong()), stringResource(R.string.u_range), Modifier.weight(1f), valueColor = if (rangeLow) c.warning else null)
            ValueCell(
                stringResource(R.string.v_light),
                when { daylightMs == null -> "–"; daylightMs == Long.MAX_VALUE -> "∞"; else -> Format.hm(daylightMs) },
                stringResource(R.string.u_until_dark), Modifier.weight(1f),
            )
        }
        batteryText?.let { Text(it, style = TetTheme.type.bodySmall, color = c.sand) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.menu), onMenu, Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.pin), onPin, Modifier.weight(1f))
            val hint = stringResource(R.string.rec_stop_hint)
            TetButton(
                text = if (recording) stringResource(R.string.rec_stop) else stringResource(R.string.rec),
                onClick = onRec,
                modifier = Modifier.weight(1f),
                kind = ButtonKind.Secondary,
                icon = if (recording) TetIcons.Stop else TetIcons.RecDot,
                iconTint = c.danger,
                requireLongPress = recording, // Stopp nur per Lang-Drücken
                onShortTapHint = { Toast.makeText(ctx, hint, Toast.LENGTH_SHORT).show() },
            )
        }
    }
}

@Composable
private fun BoxScope.Sheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = TetTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.6f)).clickable(onClick = onClose))
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 640.dp)
            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).background(c.surface)
            .border(1.dp, c.outline, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .clickable(enabled = false) {}
            .navigationBarsPadding().padding(16.dp),
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(stringResource(R.string.close), onClose, Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuContent(vm: DriveViewModel, nav: NavHostController, busy: Boolean, hasTrack: Boolean, routing: Boolean, setOverlay: (Overlay) -> Unit) {
    val c = TetTheme.colors
    val ctx = LocalContext.current
    val hint = stringResource(R.string.long_press_hint)
    val shortHint = { Toast.makeText(ctx, hint, Toast.LENGTH_SHORT).show() }
    Text(stringResource(R.string.menu).uppercase(), style = TetTheme.type.title, color = c.text)
    Spacer(Modifier.height(10.dp))
    if (busy) Text(stringResource(R.string.calculating), style = TetTheme.type.body, color = c.sand)
    val half = Modifier.fillMaxWidth(0.48f)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Track wechseln nur per Lang-Drücken (kritische Aktion)
        TetButton(stringResource(R.string.change_track), { setOverlay(Overlay.NONE); nav.navigate(Routes.TRACKS) }, half, ButtonKind.Secondary, icon = TetIcons.Route, requireLongPress = hasTrack, onShortTapHint = shortHint)
        if (hasTrack) {
            TetButton(stringResource(R.string.reverse), { vm.reverse(); setOverlay(Overlay.NONE) }, half, ButtonKind.Secondary, icon = TetIcons.Reverse, requireLongPress = true, onShortTapHint = shortHint)
        }
        SecondaryButton(stringResource(R.string.back_to_trail), { vm.backToTrail(false); setOverlay(Overlay.NONE) }, half, enabled = routing && !busy, icon = TetIcons.Navigate)
        if (hasTrack) {
            SecondaryButton(stringResource(R.string.to_section_start), { vm.backToTrail(true); setOverlay(Overlay.NONE) }, half, enabled = routing && !busy, icon = TetIcons.Flag)
            TetButton(stringResource(R.string.block_ahead), { vm.blockAhead(2_000.0); setOverlay(Overlay.NONE) }, half, ButtonKind.Secondary, icon = TetIcons.Blocked, enabled = routing && !busy, requireLongPress = true, onShortTapHint = shortHint)
        }
        SecondaryButton(stringResource(R.string.refuel), { vm.refuel(); setOverlay(Overlay.NONE) }, half, icon = TetIcons.Fuel)
        SecondaryButton(stringResource(R.string.note), { setOverlay(Overlay.NOTE) }, half, icon = TetIcons.Note)
        SecondaryButton(stringResource(R.string.pois), { setOverlay(Overlay.NONE); nav.navigate(Routes.POIS) }, half, icon = TetIcons.Tent)
        SecondaryButton(stringResource(R.string.stages), { setOverlay(Overlay.NONE); nav.navigate(Routes.STAGES) }, half, icon = TetIcons.Calendar)
        SecondaryButton(stringResource(R.string.tab_logbook), { setOverlay(Overlay.NONE); nav.navigate(Routes.LOGBOOK) }, half, icon = TetIcons.Book)
        SecondaryButton(stringResource(R.string.settings), { setOverlay(Overlay.NONE); nav.navigate(Routes.SETTINGS) }, half, icon = TetIcons.Settings)
        if (hasTrack) {
            TetButton(stringResource(R.string.end_navigation), { vm.stopNavigation(); setOverlay(Overlay.NONE) }, half, ButtonKind.Danger, icon = TetIcons.Close, requireLongPress = true, onShortTapHint = shortHint)
        }
    }
    if (!routing) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.no_routing), style = TetTheme.type.bodySmall, color = c.sand)
    }
}

@Composable
private fun rememberPhotoCapture(onPhoto: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pending?.let { onPhoto(it.toString()) } }
    val launch = {
        val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
        val f = File(dir, "tet_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        pending = uri
        camera.launch(uri)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) launch() }
    return {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
        else perm.launch(Manifest.permission.CAMERA)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinContent(vm: DriveViewModel, onDone: () -> Unit) {
    val c = TetTheme.colors
    var photo by remember { mutableStateOf<String?>(null) }
    val capture = rememberPhotoCapture { photo = it }
    Text(stringResource(R.string.hazard_pin).uppercase(), style = TetTheme.type.title, color = c.text)
    Text(stringResource(R.string.hazard_pin_sub), style = TetTheme.type.bodySmall, color = c.sand)
    Spacer(Modifier.height(10.dp))
    SecondaryButton(if (photo == null) stringResource(R.string.with_photo) else stringResource(R.string.photo_taken), capture, Modifier.fillMaxWidth(), icon = TetIcons.Camera)
    Spacer(Modifier.height(10.dp))
    val icons = mapOf(HazardType.FORD to TetIcons.Ford, HazardType.LANDSLIDE to TetIcons.Landslide, HazardType.GATE to TetIcons.Gate, HazardType.CLOSURE to TetIcons.Blocked, HazardType.OTHER to TetIcons.Hazard)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HazardType.all.forEach { t ->
            PrimaryButton(t, { vm.addHazard(t, "", photo); onDone() }, Modifier.fillMaxWidth(0.48f), icon = icons[t])
        }
    }
}

@Composable
private fun NoteContent(vm: DriveViewModel, onDone: () -> Unit) {
    val c = TetTheme.colors
    var text by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    val capture = rememberPhotoCapture { photo = it }
    Text(stringResource(R.string.note).uppercase(), style = TetTheme.type.title, color = c.text)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
        textStyle = TetTheme.type.body, placeholder = { Text(stringResource(R.string.note_hint)) },
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, focusedTextColor = c.text, unfocusedTextColor = c.text),
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(if (photo == null) stringResource(R.string.photo) else stringResource(R.string.photo_taken), capture, Modifier.weight(1f), icon = TetIcons.Camera)
        PrimaryButton(stringResource(R.string.save), { vm.addNote(text, photo); onDone() }, Modifier.weight(1f), enabled = text.isNotBlank() || photo != null)
    }
}

