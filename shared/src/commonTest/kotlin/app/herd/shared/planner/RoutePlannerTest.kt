package app.herd.shared.planner

import app.herd.shared.routing.Route
import app.herd.shared.routing.RouteOptions
import app.herd.shared.routing.Router
import app.herd.shared.routing.RoutingException
import app.herd.shared.search.Place
import app.herd.shared.search.PlaceSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlannerTest {
  private val here = Position(longitude = -119.6982, latitude = 34.4208)
  private val morroBay =
    Place("Morro Bay", "San Luis Obispo, California", Position(longitude = -120.8499, latitude = 35.3658))

  private class FakeSearch : PlaceSearch {
    val queries = mutableListOf<Pair<String, Position?>>()
    var results: List<Place> = emptyList()
    var reverseResult: Place? = null
    var reverseGate: CompletableDeferred<Unit>? = null

    override suspend fun search(query: String, near: Position?, limit: Int): List<Place> {
      queries += query to near
      return results
    }

    override suspend fun reverse(position: Position): Place? {
      reverseGate?.await()
      return reverseResult
    }
  }

  private class FakeRouter : Router {
    val calls = mutableListOf<Triple<Position, Position, RouteOptions>>()
    var failure: Exception? = null

    override suspend fun route(from: Position, to: Position, options: RouteOptions): Route {
      calls += Triple(from, to, options)
      failure?.let { throw it }
      return Route(
        geometry = listOf(from, to),
        distanceMeters = 1000.0,
        durationSeconds = 60.0,
        bounds = BoundingBox(southwest = from, northeast = to),
      )
    }
  }

  private val search = FakeSearch()
  private val router = FakeRouter()

  /**
   * Runs [block] with a planner on the test dispatcher. Not backgroundScope: advanceUntilIdle()
   * skips background work, and the planner's search collector never completes on its own.
   */
  private fun plannerTest(block: suspend TestScope.(RoutePlanner) -> Unit) = runTest {
    val job = Job()
    try {
      block(RoutePlanner(this + job, search, router))
    } finally {
      job.cancel()
    }
  }

  @Test
  fun typingQuicklySendsOneSearch() = plannerTest { planner ->
    planner.updateOrigin(here)
    search.results = listOf(morroBay)

    planner.onQueryChanged("mor")
    advanceTimeBy(100)
    planner.onQueryChanged("morr")
    advanceTimeBy(100)
    planner.onQueryChanged("morro")
    advanceUntilIdle()

    assertEquals(listOf<Pair<String, Position?>>("morro" to here), search.queries)
    assertEquals(listOf(morroBay), planner.state.value.suggestions)
    assertFalse(planner.state.value.isSearching)
  }

  @Test
  fun shortQueriesDontSearch() = plannerTest { planner ->
    search.results = listOf(morroBay)
    planner.onQueryChanged("morro")
    advanceUntilIdle()

    planner.onQueryChanged("mo")
    advanceUntilIdle()

    assertEquals(1, search.queries.size)
    assertTrue(planner.state.value.suggestions.isEmpty())
  }

  @Test
  fun selectingAPlaceRoutesFromHere() = plannerTest { planner ->
    planner.updateOrigin(here)
    search.results = listOf(morroBay)
    planner.onQueryChanged("morro")
    advanceUntilIdle()

    planner.selectPlace(morroBay)
    advanceUntilIdle()

    val state = planner.state.value
    assertEquals("Morro Bay", state.query)
    assertTrue(state.suggestions.isEmpty())
    assertEquals(morroBay, state.destination)
    assertTrue(state.route is RouteStatus.Ready)
    assertEquals(listOf(Triple(here, morroBay.position, RouteOptions())), router.calls)
  }

  @Test
  fun waitsForLocationThenRoutesOnce() = plannerTest { planner ->

    planner.selectPlace(morroBay)
    advanceUntilIdle()
    assertEquals(RouteStatus.NeedsLocation, planner.state.value.route)

    planner.updateOrigin(here)
    advanceUntilIdle()
    assertTrue(planner.state.value.route is RouteStatus.Ready)

    // Later fixes don't reroute: the public server shouldn't get a request per GPS update.
    planner.updateOrigin(Position(longitude = -119.7, latitude = 34.43))
    advanceUntilIdle()
    assertEquals(1, router.calls.size)
  }

  @Test
  fun changingOptionsReroutes() = plannerTest { planner ->
    planner.updateOrigin(here)
    planner.selectPlace(morroBay)
    advanceUntilIdle()

    planner.setOptions(RouteOptions(avoidHighways = true))
    advanceUntilIdle()

    assertEquals(RouteOptions(avoidHighways = true), router.calls.last().third)
    assertEquals(2, router.calls.size)
  }

  @Test
  fun routingErrorShowsServerMessage() = plannerTest { planner ->
    planner.updateOrigin(here)
    router.failure = RoutingException("No path could be found for input")

    planner.selectPlace(morroBay)
    advanceUntilIdle()

    assertEquals(RouteStatus.Failed("No path could be found for input"), planner.state.value.route)
  }

  @Test
  fun droppedPinGetsItsNameFromReverseGeocoding() = plannerTest { planner ->
    planner.updateOrigin(here)
    search.reverseGate = CompletableDeferred()
    search.reverseResult = Place("738 Anacapa Street", "Santa Barbara, California", here)
    // A longitude reported one world-copy east of California.
    val pressed = Position(longitude = 240.3, latitude = 35.0)

    planner.pickPoint(pressed)
    runCurrent()
    val pin = planner.state.value.destination!!
    assertEquals(RoutePlanner.DROPPED_PIN_NAME, pin.name)
    assertEquals(-119.7, pin.position.longitude, absoluteTolerance = 1e-9)

    search.reverseGate!!.complete(Unit)
    advanceUntilIdle()
    val named = planner.state.value.destination!!
    assertEquals("738 Anacapa Street", named.name)
    assertEquals("Santa Barbara, California", named.subtitle)
    // The route still goes to the exact pressed point, not the geocoded address.
    assertEquals(pin.position, named.position)
    assertEquals(pin.position, router.calls.single().second)
  }

  @Test
  fun clearResetsButKeepsOptions() = plannerTest { planner ->
    planner.updateOrigin(here)
    planner.setOptions(RouteOptions(avoidTolls = true))
    planner.selectPlace(morroBay)
    advanceUntilIdle()

    planner.clear()

    assertEquals(PlannerState(options = RouteOptions(avoidTolls = true)), planner.state.value)
  }

  @Test
  fun longitudesWrapIntoRange() {
    assertEquals(-120.0, normalizeLongitude(240.0), 1e-9)
    assertEquals(170.0, normalizeLongitude(-190.0), 1e-9)
    assertEquals(180.0, normalizeLongitude(180.0), 1e-9)
    assertEquals(-180.0, normalizeLongitude(-180.0), 1e-9)
    assertEquals(-119.7, normalizeLongitude(-119.7), 1e-9)
  }
}
