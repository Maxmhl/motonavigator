package de.tetnavigator.core.offlinedata

import de.tetnavigator.core.common.LatLon

/** Weg-Tags (surface/tracktype/highway) an Punkten entlang einer Linie. */
interface SurfaceTagSource {
    val available: Boolean

    /** Liefert pro Eingabepunkt den Belag (oder UNKNOWN). */
    suspend fun classify(points: List<LatLon>): List<SurfaceClass>
}
