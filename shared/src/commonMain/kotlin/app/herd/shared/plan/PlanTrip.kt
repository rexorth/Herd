package app.herd.shared.plan

import app.herd.shared.geo.MeasuredLine
import app.herd.shared.geo.distanceMeters
import org.maplibre.spatialk.geojson.Position

/** How to begin a planned route when the driver isn't at its first point. */
enum class JoinMode {
  /** Drive to the first point, then the whole plan (a group meeting spot). */
  FromStart,

  /** Drive to the closest part of the plan and carry on from there, skipping what's behind. */
  Nearest,
}

/** A point navigation should visit: one of the plan's own, or a spot on the plan line to rejoin it at. */
data class TripWaypoint(val position: Position, val passThrough: Boolean)

/**
 * The next thing to navigate: [waypoints] in order, ending at the stop at [targetIndex]. [joinAt]
 * is where that route meets the plan again, so the stretch before it (the join leg) can be drawn
 * apart; null when the driver is already on the plan.
 */
data class TripLeg(val waypoints: List<TripWaypoint>, val targetIndex: Int, val joinAt: Position?)

/**
 * Following a planned route, one leg at a time: a leg runs from where the driver is to the next
 * stop, routed through any pass-through points between them. Herd, not the navigation engine, owns
 * which stops are done, so the trip can't end early and can't skip a stop.
 *
 * Progress is only ever measured within the current leg's stretch of the plan, so a route that
 * loops back or runs beside itself can't jump progress forward past a stop the driver hasn't
 * reached. Arrival at the leg's stop is reported by the navigation engine (the leg's last
 * waypoint); getting back on after leaving goes to the nearest part of the leg still ahead, a
 * little further on so the new route doesn't ask for a U-turn.
 *
 * Not thread-safe: call it from one place at a time.
 */
class PlanTrip(val plan: PlannedRoute) {
  private val points = plan.points
  private val line = MeasuredLine(plan.route.geometry)

  /** How far along the line each plan point is, in order. */
  private val pointAlong: List<Double> = run {
    var from = 0.0
    points.map { point -> (line.nearest(point.place.position, fromAlong = from)?.along ?: from).also { from = it } }
  }

  /** The last plan point confirmed reached (driven to, or skipped past at a "Join nearest" start). */
  var reachedIndex: Int = -1
    private set

  /** The stop the current leg is heading for. */
  var targetIndex: Int = -1
    private set

  /** How far along the plan line (meters) the driver has got on the current leg. */
  private var legAlong = 0.0

  /** True once the final point has been reached. */
  val isComplete: Boolean
    get() = reachedIndex >= points.lastIndex

  /** The next stop to head for: the first non-pass-through point after the last one reached. */
  private fun nextStop(): Int = (reachedIndex + 1..points.lastIndex).first { !points[it].passThrough }

  /** Where to go to begin the plan from [here]. */
  fun start(here: Position, mode: JoinMode): TripLeg {
    reachedIndex =
      when (mode) {
        JoinMode.FromStart ->
          if (distanceMeters(here, points.first().place.position) <= AT_START_M) 0 else -1
        JoinMode.Nearest -> {
          val along = line.nearest(here)?.along ?: 0.0
          // Everything behind the join counts as done, so the first leg heads to the next stop
          // ahead. The final point is never skipped this way.
          points.indices.lastOrNull { it < points.lastIndex && pointAlong[it] <= along } ?: -1
        }
      }
    return legTo(nextStop(), here)
  }

  /** Marks the current target reached. Call when navigation arrives at the leg's stop. */
  fun reachTarget() {
    reachedIndex = maxOf(reachedIndex, targetIndex)
    legAlong = if (reachedIndex >= 0) pointAlong[reachedIndex] else 0.0
  }

  /** The stop the next leg would head for (for naming it), or null if the plan is done. */
  fun upcomingStop(): Int? = if (isComplete) null else nextStop()

  /** The leg to the next stop from [here], or null if the plan is done. */
  fun nextLeg(here: Position): TripLeg? = if (isComplete) null else legTo(nextStop(), here)

  /** Where to go to get back onto the current leg from [here], having left it. */
  fun rejoin(here: Position): TripLeg = legTo(targetIndex, here)

  /**
   * Moves leg progress forward from a fix and returns the pass-through points newly driven past,
   * in order. Fixes off the leg (a detour) or too vague to place don't move progress, so a loop or
   * a parallel road can't tick a point off.
   */
  fun advance(here: Position, accuracyMeters: Double): List<Int> {
    if (targetIndex < 0 || accuracyMeters > MAX_USABLE_ACCURACY_M) return emptyList()
    val match =
      line.nearest(here, fromAlong = legAlong, toAlong = pointAlong[targetIndex])?.takeIf { it.distance <= ON_PLAN_M }
        ?: return emptyList()
    if (match.along <= legAlong) return emptyList()
    val crossed = (reachedIndex + 1 until targetIndex).filter { points[it].passThrough && pointAlong[it] <= match.along }
    legAlong = match.along
    if (crossed.isNotEmpty()) reachedIndex = maxOf(reachedIndex, crossed.max())
    return crossed
  }

  /** The stretch of the plan already behind the driver, for drawing it as done. */
  fun completedGeometry(): List<Position> =
    if (reachedIndex < 0 || pointAlong[reachedIndex] <= 0.0) emptyList() else line.slice(0.0, pointAlong[reachedIndex])

  /** The part of [route] (a navigation route) before it reaches [joinAt]. */
  fun joinLeg(route: List<Position>, joinAt: Position): List<Position> {
    if (route.size < 2) return emptyList()
    val measured = MeasuredLine(route)
    val along = measured.nearest(joinAt)?.along ?: return emptyList()
    return measured.slice(0.0, along)
  }

  /** Builds the leg to [target] from [here], through every pass-through point still ahead of it. */
  private fun legTo(target: Int, here: Position): TripLeg {
    targetIndex = target
    val startAlong = if (reachedIndex >= 0) pointAlong[reachedIndex] else 0.0
    legAlong = startAlong
    val targetPos = points[target].place.position
    // Every pass-through between the last point reached and the target is kept, in order, so a
    // reroute can never skip one.
    val vias =
      (reachedIndex + 1 until target).filter { points[it].passThrough }.map { TripWaypoint(points[it].place.position, true) }
    val here0 = line.nearest(here, fromAlong = startAlong, toAlong = pointAlong[target])
    val onPlan = here0 != null && here0.distance <= ON_PLAN_M
    // When off the plan with nothing but the target stop ahead, rejoin a little past the nearest
    // point so the new route doesn't double back; a pass-through ahead already anchors the rejoin.
    val rejoinAt =
      if (onPlan || vias.isNotEmpty()) null
      else
        line
          .pointAt(((here0?.along ?: startAlong) + REJOIN_AHEAD_M).coerceAtMost(pointAlong[target]))
          .takeIf { distanceMeters(it, targetPos) > SAME_SPOT_M }
    val waypoints =
      listOfNotNull(rejoinAt?.let { TripWaypoint(it, passThrough = true) }) + vias + TripWaypoint(targetPos, passThrough = false)
    val joinAt = if (onPlan) null else waypoints.first().position
    return TripLeg(waypoints, target, joinAt)
  }

  private companion object {
    /** Fixes vaguer than this can't tell which road the car is on. */
    const val MAX_USABLE_ACCURACY_M = 30.0
    /** Within this of the plan line counts as on it. */
    const val ON_PLAN_M = 50.0
    /** Starting within this of the first point counts as starting there. */
    const val AT_START_M = 150.0
    /** How far past the nearest point of the plan to rejoin it, so the new route doesn't turn around there. */
    const val REJOIN_AHEAD_M = 300.0
    /** Two points this close count as the same spot. */
    const val SAME_SPOT_M = 25.0
  }
}
