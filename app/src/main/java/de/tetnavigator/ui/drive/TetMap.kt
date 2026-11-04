package de.tetnavigator.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.tetnavigator.core.designsystem.TetColors
import de.tetnavigator.map.MapController
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * MapLibre-Karte als Compose-Baustein. [styleJson] wird bei Änderung neu
 * gesetzt; [onReady] liefert den Controller für die Overlays.
 */
@Composable
fun TetMap(
    styleJson: String,
    colors: TetColors,
    gestures: Boolean,
    onUserPan: () -> Unit,
    onReady: (MapController?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(ctx).apply { onCreate(null) } }
    var controller by remember { mutableStateOf<MapController?>(null) }
    var map by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            onReady(null)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserPan()
            }
        }
    }

    LaunchedEffect(map, styleJson, colors) {
        val m = map ?: return@LaunchedEffect
        onReady(null)
        m.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            val c = MapController(m, density)
            c.setGestures(gestures)
            c.onStyle(style, colors)
            controller = c
            onReady(c)
        }
    }

    LaunchedEffect(controller, gestures) { controller?.setGestures(gestures) }

    AndroidView(factory = { mapView }, modifier = modifier)
}
