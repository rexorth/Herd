package app.herd.shared.plan

import app.herd.shared.routing.MAX_LOCATIONS
import app.herd.shared.routing.Route
import app.herd.shared.routing.RouteOptions
import app.herd.shared.search.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

  // Start, a drive-through at 1.5 km, a stop at 3 km, the end at 5 km.
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
  fun fromStartFarHeadsToTheStartFirst() {
    val leg = PlanTrip(plan).start(beside(-2_000.0, 0.0), JoinMode.FromStart)

    // Leg by leg: the first leg only goes to the start; the rest follows once it's reached.
    assertEquals(listOf("Start"), names(leg))
    assertEquals(0, leg.targetIndex)
    assertEquals(plan.points.first().place.position, leg.joinAt)
  }

  @Test
  fun fromStartNearBeginsTheFirstLegThroughTheDriveThrough() {
    val leg = PlanTrip(plan).start(beside(0.0, 30.0), JoinMode.FromStart)

    assertEquals(listOf("Via", "Stop"), names(leg))
    assertEquals(listOf(true, false), leg.waypoints.map { it.passThrough })
    assertEquals(2, leg.targetIndex)
    assertNull(leg.joinAt)
  }

  @Test
  fun legsRunStopToStopAndCannotFinishEarly() {
    val trip = PlanTrip(plan)
    val leg1 = trip.start(onPlan(0.0), JoinMode.FromStart)
    assertEquals(listOf("Via", "Stop"), names(leg1))
    assertFalse(trip.isComplete)

    // Drive up to and past the drive-through: it's reported once, in order, and marked reached.
    assertEquals(listOf(1), trip.advance(onPlan(1_600.0), 5.0))
    assertEquals(1, trip.reachedIndex)
    assertEquals(emptyList(), trip.advance(onPlan(2_000.0), 5.0))

    // Arrive at the stop (the navigation engine reports it); then the next leg is to the end.
    trip.reachTarget()
    assertEquals(2, trip.reachedIndex)
    assertFalse(trip.isComplete)
    val leg2 = trip.nextLeg(onPlan(3_000.0))
    assertNotNull(leg2)
    assertEquals(listOf("End"), names(leg2))
    assertEquals(3, leg2.targetIndex)

    trip.reachTarget()
    assertTrue(trip.isComplete)
    assertNull(trip.nextLeg(onPlan(5_000.0)))
  }

  @Test
  fun aDetourPastTheFinishNeitherEndsTheTripNorSkipsStops() {
    val trip = PlanTrip(plan)
    trip.start(onPlan(0.0), JoinMode.FromStart) // heading to the stop at 3 km

    // The detour wanders up by the finish (5 km), far past the current leg. Progress is measured
    // only within the leg, so it can't leap forward and the trip can't end.
    assertEquals(emptyList(), trip.advance(onPlan(5_000.0), 5.0))
    assertEquals(0, trip.reachedIndex)
    assertFalse(trip.isComplete)

    // Rejoining still heads to the stop, through the drive-through not yet reached.
    val leg = trip.rejoin(beside(4_000.0, 300.0))
    assertEquals(listOf("Via", "Stop"), names(leg))
    assertEquals(2, leg.targetIndex)
  }

  @Test
  fun joinNearestRejoinsAheadOfTheClosestSpot() {
    // 200 m east of the plan, 2 km along: the drive-through is behind, so join 300 m on, then the stop.
    val leg = PlanTrip(plan).start(beside(2_000.0, 200.0), JoinMode.Nearest)

    assertEquals(listOf("rejoin", "Stop"), names(leg))
    assertTrue(leg.waypoints.first().passThrough)
    assertEquals(2_300.0, (leg.joinAt!!.latitude - 34.40) * metersPerLatDegree, absoluteTolerance = 2.0)
  }

  @Test
  fun aStopJustAheadIsJoinedDirectly() {
    // 200 m before the stop: rejoining 300 m on would pass it, so go to the stop itself.
    val leg = PlanTrip(plan).start(beside(2_800.0, 150.0), JoinMode.Nearest)

    assertEquals(listOf("Stop"), names(leg))
    assertEquals(plan.points[2].place.position, leg.joinAt)
  }

  @Test
  fun rejoinWithinALegGoesBackAheadOfTheDriver() {
    val trip = PlanTrip(plan)
    trip.start(onPlan(0.0), JoinMode.FromStart)
    trip.advance(onPlan(2_000.0), 5.0) // past the drive-through, on the way to the stop

    // Turned off 100 m east just past 2 km: back onto the plan 300 m on, then the stop.
    val leg = trip.rejoin(beside(2_100.0, 100.0))
    assertEquals(listOf("rejoin", "Stop"), names(leg))
    assertEquals(2_400.0, (leg.joinAt!!.latitude - 34.40) * metersPerLatDegree, absoluteTolerance = 2.0)
  }

  @Test
  fun rejoinNeverSkipsADriveThroughNotYetReached() {
    val trip = PlanTrip(plan)
    trip.start(onPlan(0.0), JoinMode.FromStart)
    trip.advance(onPlan(1_000.0), 5.0) // not yet at the drive-through

    // Drove a parallel road past the drive-through: it's still kept.
    val leg = trip.rejoin(beside(2_500.0, 200.0))
    assertEquals(listOf("Via", "Stop"), names(leg))
  }

  @Test
  fun completedGeometryGrowsAsStopsAreReached() {
    val trip = PlanTrip(plan)
    assertTrue(trip.completedGeometry().isEmpty())

    trip.start(onPlan(0.0), JoinMode.FromStart)
    trip.reachTarget() // reached the stop at 3 km
    val done = trip.completedGeometry()
    assertTrue(done.size > 2)
    assertEquals(3_000.0, (done.last().latitude - 34.40) * metersPerLatDegree, absoluteTolerance = 1.0)
  }

  @Test
  fun eachLegFitsOneRequest() {
    // A full plan of consecutive drive-throughs: the longest leg plus the driver's position still
    // fits the server's location limit.
    val full =
      plan.copy(
        points =
          (0 until MAX_PLAN_POINTS).map { point("P$it", it * 600.0, passThrough = it != 0 && it != MAX_PLAN_POINTS - 1) }
      )
    val trip = PlanTrip(full)
    assertTrue(trip.start(beside(0.0, 20.0), JoinMode.FromStart).waypoints.size <= MAX_LOCATIONS - 1)
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
