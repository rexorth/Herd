package app.herd.shared.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import org.maplibre.spatialk.geojson.Position

private const val EARTH_RADIUS_M = 6_371_000.0
private const val METERS_PER_DEGREE = EARTH_RADIUS_M * PI / 180.0

/**
 * Shortest distance in meters from [point] to the polyline [line] (a single point counts as a line
 * of no length; an empty one is infinitely far).
 *
 * Works in a flat projection centered on [point], which is accurate to well under a meter for
 * anything within a few kilometers: the only distances that matter for staying on a route.
 */
fun distanceToLineMeters(point: Position, line: List<Position>): Double {
  if (line.isEmpty()) return Double.POSITIVE_INFINITY
  val metersPerLongitudeDegree = METERS_PER_DEGREE * cos(point.latitude * PI / 180.0)
  fun x(p: Position) = wrapDegrees(p.longitude - point.longitude) * metersPerLongitudeDegree
  fun y(p: Position) = (p.latitude - point.latitude) * METERS_PER_DEGREE

  var best = hypot(x(line[0]), y(line[0]))
  for (i in 1 until line.size) {
    best = minOf(best, distanceToSegment(x(line[i - 1]), y(line[i - 1]), x(line[i]), y(line[i])))
  }
  return best
}

/** Distance from the origin to the segment (ax, ay)–(bx, by). */
private fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
  val dx = bx - ax
  val dy = by - ay
  val lengthSquared = dx * dx + dy * dy
  if (lengthSquared == 0.0) return hypot(ax, ay)
  // How far along the segment the closest point is, from 0 (at a) to 1 (at b).
  val t = (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
  return hypot(ax + t * dx, ay + t * dy)
}

/** A longitude difference brought into -180..180, so lines across the 180th meridian measure right. */
private fun wrapDegrees(delta: Double): Double = ((delta + 540.0) % 360.0) - 180.0
