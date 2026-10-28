package app.herd.shared

import app.herd.shared.search.distanceKm
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.maplibre.spatialk.geojson.Position

/**
 * How long the location dot (and the camera following it) takes to glide to a new fix.
 *
 * Matching the time since the previous fix keeps the dot moving steadily: it reaches each fix just
 * as the next one arrives, instead of jumping there and then waiting. The clamp stops a long gap
 * (a tunnel, a GPS dropout) from dragging the dot slowly across the map.
 */
fun smoothingDuration(sinceLastFix: Duration?): Duration =
  sinceLastFix?.coerceIn(MIN_SMOOTHING, MAX_SMOOTHING) ?: DEFAULT_SMOOTHING

private val MIN_SMOOTHING = 250.milliseconds
private val MAX_SMOOTHING = 1500.milliseconds
/** The usual gap between fixes (LocationProfiles.mapVisible asks for one per second). */
private val DEFAULT_SMOOTHING = 1.seconds

/**
 * The point [fraction] (0 to 1) of the way from [from] to [to]. Consecutive fixes are meters apart,
 * so a straight line in degrees is exact enough. Crossing the 180th meridian takes the short way.
 */
fun interpolatePosition(from: Position, to: Position, fraction: Double): Position {
  val longitudeDelta = ((to.longitude - from.longitude + 540.0) % 360.0) - 180.0
  val longitude = from.longitude + longitudeDelta * fraction
  return Position(
    longitude = ((longitude + 540.0) % 360.0) - 180.0,
    latitude = from.latitude + (to.latitude - from.latitude) * fraction,
  )
}

/**
 * The bearing (degrees clockwise from north, 0 to 360) [fraction] of the way from [from] to [to],
 * turning the short way: 350° to 10° passes through 0°, not 180°.
 */
fun interpolateBearing(from: Double, to: Double, fraction: Double): Double {
  val delta = ((to - from + 540.0) % 360.0) - 180.0
  return ((from + delta * fraction) % 360.0 + 360.0) % 360.0
}

/**
 * Whether [next] is only GPS wander around a phone that isn't going anywhere: closer to the
 * position on screen ([shown]) than the fix's own accuracy, at walking pace or slower. Indoors or
 * parked, fixes drift by several meters; following them would wobble the dot and keep the map
 * redrawing for nothing. Unknown accuracy counts as [MIN_WANDER_METERS] and unknown speed as slow.
 */
fun isStationaryWander(
  shown: Position,
  next: Position,
  accuracyMeters: Double?,
  speedMetersPerSecond: Double?,
): Boolean {
  if (speedMetersPerSecond != null && speedMetersPerSecond > WALKING_SPEED_MPS) return false
  val radius = maxOf(accuracyMeters ?: 0.0, MIN_WANDER_METERS)
  return distanceKm(shown, next) * 1000.0 < radius
}

private const val WALKING_SPEED_MPS = 1.0
private const val MIN_WANDER_METERS = 5.0
