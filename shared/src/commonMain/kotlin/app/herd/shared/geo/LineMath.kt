package app.herd.shared.geo

import app.herd.shared.search.distanceKm
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

/** Where a point lands on a [MeasuredLine]: [along] meters from its start, [distance] meters away. */
data class LineMatch(val along: Double, val distance: Double, val position: Position)

/**
 * A polyline that knows how far along it each vertex is, for following progress along a route
 * and finding points on it by distance.
 */
class MeasuredLine(val points: List<Position>) {
  /** Meters from the start to each vertex. */
  private val cumulative = DoubleArray(points.size)

  init {
    require(points.isNotEmpty()) { "A line needs at least one point" }
    for (i in 1 until points.size) {
      cumulative[i] = cumulative[i - 1] + distanceMeters(points[i - 1], points[i])
    }
  }

  val length: Double
    get() = cumulative.last()

  /**
   * The point of the line closest to [point] between [fromAlong] and [toAlong] meters along it,
   * or null if that stretch is empty.
   */
  fun nearest(point: Position, fromAlong: Double = 0.0, toAlong: Double = length): LineMatch? {
    val from = fromAlong.coerceIn(0.0, length)
    val to = toAlong.coerceIn(0.0, length)
    if (from > to) return null
    if (points.size == 1) return LineMatch(0.0, distanceMeters(point, points[0]), points[0])

    val metersPerLongitudeDegree = METERS_PER_DEGREE * cos(point.latitude * PI / 180.0)
    fun x(p: Position) = wrapDegrees(p.longitude - point.longitude) * metersPerLongitudeDegree
    fun y(p: Position) = (p.latitude - point.latitude) * METERS_PER_DEGREE

    var best: LineMatch? = null
    for (i in 1 until points.size) {
      val start = cumulative[i - 1]
      val end = cumulative[i]
      if (end < from || start > to) continue
      val segment = end - start
      // The part of this segment inside [from, to], as fractions of it.
      val minT = if (segment > 0) ((from - start) / segment).coerceIn(0.0, 1.0) else 0.0
      val maxT = if (segment > 0) ((to - start) / segment).coerceIn(0.0, 1.0) else 0.0
      val ax = x(points[i - 1])
      val ay = y(points[i - 1])
      val dx = x(points[i]) - ax
      val dy = y(points[i]) - ay
      val lengthSquared = dx * dx + dy * dy
      val t =
        (if (lengthSquared == 0.0) 0.0 else -(ax * dx + ay * dy) / lengthSquared).coerceIn(minT, maxT)
      val distance = hypot(ax + t * dx, ay + t * dy)
      if (best == null || distance < best.distance) {
        best = LineMatch(start + t * segment, distance, interpolate(points[i - 1], points[i], t))
      }
    }
    return best
  }

  /** The point [along] meters from the start (clamped to the line). */
  fun pointAt(along: Double): Position {
    if (along >= length) return points.last()
    val target = along.coerceAtLeast(0.0)
    val i = segmentEndingAfter(target)
    if (i == 0) return points[0]
    val segment = cumulative[i] - cumulative[i - 1]
    val t = if (segment > 0) (target - cumulative[i - 1]) / segment else 0.0
    return interpolate(points[i - 1], points[i], t)
  }

  /** The part of the line from [fromAlong] to [toAlong] meters, as a list of points. */
  fun slice(fromAlong: Double, toAlong: Double): List<Position> {
    val from = fromAlong.coerceIn(0.0, length)
    val to = toAlong.coerceIn(from, length)
    return buildList {
      add(pointAt(from))
      for (i in points.indices) if (cumulative[i] > from && cumulative[i] < to) add(points[i])
      add(pointAt(to))
    }
  }

  /** Index of the first vertex at or past [along] (0 only for along = 0). */
  private fun segmentEndingAfter(along: Double): Int {
    var low = 0
    var high = points.lastIndex
    while (low < high) {
      val mid = (low + high) / 2
      if (cumulative[mid] < along) low = mid + 1 else high = mid
    }
    return low
  }
}

/** Great-circle distance in meters. */
fun distanceMeters(a: Position, b: Position): Double = distanceKm(a, b) * 1000.0

private fun interpolate(a: Position, b: Position, t: Double): Position =
  Position(
    longitude = a.longitude + wrapDegrees(b.longitude - a.longitude) * t,
    latitude = a.latitude + (b.latitude - a.latitude) * t,
  )
