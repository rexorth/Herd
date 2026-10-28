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
 * Where navigation should send the driver from where they are: [waypoints] in order. [joinAt] is
 * where that route meets the plan, so the stretch before it (the join leg) can be drawn apart from
 * the plan; null when the driver starts on the plan.
 */
data class TripLeg(val waypoints: List<TripWaypoint>, val joinAt: Position?)

/**
 * Following a planned route: how far along it the driver has got, and where to send them to get
 * on it or back onto it.
 *
 * Getting back on goes to the nearest part of the plan that's still ahead, a little further along
 * so the new route doesn't ask for a U-turn there, then through the plan's remaining points. It
 * never skips a plan point that's still ahead.
 *
 * Not thread-safe: call it from one place at a time.
 */
class PlanTrip(val plan: PlannedRoute) {
  private val line = MeasuredLine(plan.route.geometry)

  /** How far along the line each plan point is, in order. */
  private val pointAlong: List<Double> = run {
    var from = 0.0
    plan.points.map { point ->
      (line.nearest(point.place.position, fromAlong = from)?.along ?: from).also { from = it }
    }
  }

  /** The furthest distance along the plan the driver has been seen on it, in meters. */
  var progressMeters: Double = 0.0
    private set

  /**
   * Plan points up to this far along count as reached: where the driver has been, or where a
   * "Join nearest" start skipped to. Nothing is reached before the driver gets onto the plan.
   */
  private var reachedAlong = Double.NEGATIVE_INFINITY

  /** Moves progress forward when [here] is on the plan ahead of it. Never moves it back. */
  fun update(here: Position, accuracyMeters: Double) {
    if (accuracyMeters > MAX_USABLE_ACCURACY_M) return
    // Look just ahead first, so a route that loops back past itself counts the nearer pass; then
    // anywhere ahead, for a driver who rejoined further on.
    val match =
      line.nearest(here, fromAlong = progressMeters, toAlong = progressMeters + NEARBY_AHEAD_M)?.takeIf { it.distance <= ON_PLAN_M }
        ?: line.nearest(here, fromAlong = progressMeters)?.takeIf { it.distance <= ON_PLAN_M }
        ?: return
    progressMeters = maxOf(progressMeters, match.along)
    reachedAlong = maxOf(reachedAlong, match.along)
  }

  /** Where to go to begin the plan from [here]. */
  fun start(here: Position, mode: JoinMode): TripLeg =
    when (mode) {
      JoinMode.FromStart -> {
        val first = plan.points.first().place.position
        if (distanceMeters(here, first) <= AT_START_M) {
          reachedAlong = pointAlong.first()
          TripLeg(waypointsFrom(1), joinAt = null)
        } else {
          TripLeg(waypointsFrom(0), joinAt = first)
        }
      }
      JoinMode.Nearest -> {
        val along = line.nearest(here)?.along ?: 0.0
        reachedAlong = along
        joinFrom(along)
      }
    }

  /**
   * Where to go to get back on the plan from [here], having left it. Points the driver hasn't
   * reached yet are kept, even if they've since driven past one on another road.
   */
  fun rejoin(here: Position): TripLeg {
    val along = line.nearest(here, fromAlong = progressMeters)?.along ?: progressMeters
    return joinFrom(along)
  }

  /** The part of [route] (a navigation route) before it reaches [joinAt]. */
  fun joinLeg(route: List<Position>, joinAt: Position): List<Position> {
    if (route.size < 2) return emptyList()
    val measured = MeasuredLine(route)
    val along = measured.nearest(joinAt)?.along ?: return emptyList()
    return measured.slice(0.0, along)
  }

  /** Joins the plan near [along] meters, going on through every point not yet reached. */
  private fun joinFrom(along: Double): TripLeg {
    val next = pointAlong.indexOfFirst { it > reachedAlong + SAME_SPOT_M }
    val points = plan.points
    if (next == -1) {
      // Past the last point's spot: just go there.
      val last = points.last().place.position
      return TripLeg(listOf(TripWaypoint(last, passThrough = false)), joinAt = last)
    }
    val rejoinAlong = along + REJOIN_AHEAD_M
    // The next point comes first (or there's nothing before it to join): head for the point itself.
    if (next == 0 || rejoinAlong >= pointAlong[next] - SAME_SPOT_M) {
      return TripLeg(waypointsFrom(next), joinAt = points[next].place.position)
    }
    val rejoinAt = line.pointAt(rejoinAlong)
    return TripLeg(listOf(TripWaypoint(rejoinAt, passThrough = true)) + waypointsFrom(next), joinAt = rejoinAt)
  }

  private fun waypointsFrom(index: Int): List<TripWaypoint> {
    val points = plan.points
    return (index..points.lastIndex).map { i ->
      TripWaypoint(points[i].place.position, passThrough = points[i].passThrough && i != points.lastIndex)
    }
  }

  private companion object {
    /** Fixes vaguer than this can't tell which road the car is on. */
    const val MAX_USABLE_ACCURACY_M = 30.0
    /** Within this of the plan line counts as on it. */
    const val ON_PLAN_M = 50.0
    /** How far ahead of progress to look first. */
    const val NEARBY_AHEAD_M = 3_000.0
    /** Starting within this of the first point counts as starting there. */
    const val AT_START_M = 150.0
    /** How far past the nearest point of the plan to rejoin it, so the new route doesn't turn around there. */
    const val REJOIN_AHEAD_M = 300.0
    /** A plan point this little ahead of where the driver has reached counts as reached. */
    const val SAME_SPOT_M = 25.0
  }
}
