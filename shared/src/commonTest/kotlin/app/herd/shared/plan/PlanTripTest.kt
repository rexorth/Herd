package app.herd.shared.plan

import app.herd.shared.routing.Route
import app.herd.shared.routing.RouteOptions
import app.herd.shared.search.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

class PlanTripTest {
  // A plan running 5 km due north, with a vertex every 100 m.
  private val metersPerLatDegree = 111_195.0
  private val lon = -119.70

  private fun onPlan(meters: Double) = Position(lon, 34.40 + meters / metersPerLatDegree)

  /** [meters] along, [east] meters to the side of the plan. */
  private fun beside(meters: Double, east: Double) =
    Position(lon + east / (metersPerLatDegree * 0.8251), 34.40 + meters / metersPerLatDegree)

  private val geometry = (0..50).map { onPlan(it * 100.0) }

  private fun point(name: String, meters: Double, passThrough: Boolean = false) =
    PlanPoint(Place(name, null, onPlan(meters)), passThrough)

  private val plan =
    PlannedRoute(
      name = "North",
      points = listOf(point("Start", 0.0), point("Via", 1_500.0, passThrough = true), point("Stop", 3_000.0), point("End", 5_000.0)),
      options = RouteOptions(),
      route = Route(geometry, 5_000.0, 300.0, BoundingBox(lon, 34.40, lon, 34.45)),
    )

  private fun names(leg: TripLeg) =
    leg.waypoints.map { w -> plan.points.firstOrNull { it.place.position == w.position }?.place?.name ?: "rejoin" }

  @Test
  fun fromTheStartGoesToTheFirstPointThenEverything() {
    val leg = PlanTrip(plan).start(beside(-2_000.0, 0.0), JoinMode.FromStart)

    assertEquals(listOf("Start", "Via", "Stop", "End"), names(leg))
    assertEquals(listOf(false, true, false, false), leg.waypoints.map { it.passThrough })
    assertEquals(plan.points.first().place.position, leg.joinAt)
  }

  @Test
  fun alreadyAtTheStartSkipsIt() {
    val leg = PlanTrip(plan).start(beside(0.0, 100.0), JoinMode.FromStart)

    assertEquals(listOf("Via", "Stop", "End"), names(leg))
    assertNull(leg.joinAt)
  }

  @Test
  fun joinNearestRejoinsAheadOfTheClosestSpot() {
    // 200 m east of the plan, 2 km along: join 300 m further on, then the stop and the end.
    val leg = PlanTrip(plan).start(beside(2_000.0, 200.0), JoinMode.Nearest)

    assertEquals(listOf("rejoin", "Stop", "End"), names(leg))
    assertTrue(leg.waypoints.first().passThrough)
    assertEquals(2_300.0, (leg.joinAt!!.latitude - 34.40) * metersPerLatDegree, absoluteTolerance = 2.0)
  }

  @Test
  fun aPointJustAheadIsJoinedDirectly() {
    // 200 m before the stop: rejoining 300 m on would pass it, so go to the stop itself.
    val leg = PlanTrip(plan).start(beside(2_800.0, 150.0), JoinMode.Nearest)

    assertEquals(listOf("Stop", "End"), names(leg))
    assertEquals(plan.points[2].place.position, leg.joinAt)
  }

  @Test
  fun progressOnlyMovesForwardAndOnlyOnThePlan() {
    val trip = PlanTrip(plan)
    trip.update(beside(1_000.0, 10.0), accuracyMeters = 8.0)
    assertEquals(1_000.0, trip.progressMeters, absoluteTolerance = 1.0)

    // Behind, far off the plan, or too vague to tell: no change.
    trip.update(beside(500.0, 0.0), 8.0)
    trip.update(beside(2_000.0, 300.0), 8.0)
    trip.update(beside(4_000.0, 0.0), accuracyMeters = 60.0)
    assertEquals(1_000.0, trip.progressMeters, absoluteTolerance = 1.0)

    trip.update(beside(1_800.0, -20.0), 8.0)
    assertEquals(1_800.0, trip.progressMeters, absoluteTolerance = 1.0)
  }

  @Test
  fun rejoinGoesBackToThePlanAheadOfProgress() {
    val trip = PlanTrip(plan)
    trip.update(onPlan(2_000.0), 5.0)

    // Turned off 100 m east just past 2 km.
    val leg = trip.rejoin(beside(2_100.0, 100.0))

    assertEquals(listOf("rejoin", "Stop", "End"), names(leg))
    assertEquals(2_400.0, (leg.joinAt!!.latitude - 34.40) * metersPerLatDegree, absoluteTolerance = 2.0)
  }

  @Test
  fun rejoinNeverSkipsAPointNotYetReached() {
    val trip = PlanTrip(plan)
    trip.update(onPlan(1_000.0), 5.0)

    // Drove a parallel road past the via point and the stop.
    val leg = trip.rejoin(beside(3_500.0, 200.0))

    assertEquals(listOf("Via", "Stop", "End"), names(leg))
  }

  @Test
  fun aDetourOnTheWayToTheStartStillGoesToTheStart() {
    val trip = PlanTrip(plan)
    trip.start(beside(-2_000.0, 0.0), JoinMode.FromStart)

    // Lost on the way, nearer the middle of the plan than its start.
    val leg = trip.rejoin(beside(2_500.0, 400.0))

    assertEquals(listOf("Start", "Via", "Stop", "End"), names(leg))
  }

  @Test
  fun pastTheLastPointGoesStraightThere() {
    val trip = PlanTrip(plan)
    trip.update(onPlan(4_990.0), 5.0)

    assertEquals(listOf("End"), names(trip.rejoin(beside(4_990.0, 100.0))))
  }

  @Test
  fun aFullPlanStillFitsOneRequest() {
    val full =
      plan.copy(points = (0 until MAX_PLAN_POINTS).map { point("P$it", it * 600.0, passThrough = it % 2 == 1) })
    val trip = PlanTrip(full)

    assertTrue(trip.start(beside(-1_000.0, 0.0), JoinMode.FromStart).waypoints.size <= MAX_PLAN_POINTS)
    assertTrue(trip.start(beside(100.0, 300.0), JoinMode.Nearest).waypoints.size <= MAX_PLAN_POINTS)
    trip.update(onPlan(700.0), 5.0)
    assertTrue(trip.rejoin(beside(800.0, 200.0)).waypoints.size <= MAX_PLAN_POINTS)
  }

  @Test
  fun joinLegIsTheRouteUpToWhereItMeetsThePlan() {
    val trip = PlanTrip(plan)
    // A navigation route: 500 m east to the plan at 2 km, then up the plan.
    val route = listOf(beside(2_000.0, 500.0), beside(2_000.0, 0.0), onPlan(3_000.0))

    val leg = trip.joinLeg(route, joinAt = onPlan(2_000.0))

    assertEquals(2, leg.size)
    assertEquals(route.first(), leg.first())
    assertNotNull(leg.last())
    assertEquals(lon, leg.last().longitude, absoluteTolerance = 1e-6)
  }
}
