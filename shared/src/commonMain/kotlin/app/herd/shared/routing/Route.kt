package app.herd.shared.routing

import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** A driving route as drawn on the map: its line, how long it is, and the area it covers. */
data class Route(
  val geometry: List<Position>,
  val distanceMeters: Double,
  val durationSeconds: Double,
  val bounds: BoundingBox,
)

/** Route preferences the user can toggle. Valhalla treats them as strong preferences, not hard rules. */
data class RouteOptions(val avoidHighways: Boolean = false, val avoidTolls: Boolean = false)
