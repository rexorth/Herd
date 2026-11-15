package app.herd.shared.plan

import app.herd.shared.routing.MAX_LOCATIONS
import app.herd.shared.routing.Route
import app.herd.shared.routing.RouteOptions
import app.herd.shared.routing.RoutePoint
import app.herd.shared.search.Place

/**
 * One point of a planned route: a stop, or (with [passThrough]) a point that only keeps the route
 * on a particular road. The first and last points are always stops.
 */
data class PlanPoint(val place: Place, val passThrough: Boolean = false)

/**
 * A route planned ahead: the points it visits in order, and the line through them as routed with
 * [options]. [id] is null until it's saved.
 */
data class PlannedRoute(
  val id: Long? = null,
  val name: String,
  val points: List<PlanPoint>,
  val options: RouteOptions,
  val route: Route,
)

/**
 * The most points a plan can have. The free routing server takes 10 locations per request, and
 * navigating a plan needs one of them for the driver's own position.
 */
const val MAX_PLAN_POINTS = MAX_LOCATIONS - 1

/** The plan's points as the router takes them. */
fun List<PlanPoint>.toRoutePoints(): List<RoutePoint> =
  mapIndexed { i, point -> RoutePoint(point.place.position, passThrough = point.passThrough && i != 0 && i != lastIndex) }
