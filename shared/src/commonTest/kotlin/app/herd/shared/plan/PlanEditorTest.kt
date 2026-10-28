package app.herd.shared.plan

import app.herd.shared.planner.RouteStatus
import app.herd.shared.routing.Route
import app.herd.shared.routing.RouteOptions
import app.herd.shared.routing.RoutePoint
import app.herd.shared.routing.Router
import app.herd.shared.routing.RoutingException
import app.herd.shared.search.Place
import app.herd.shared.search.PlaceSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalCoroutinesApi::class)
class PlanEditorTest {
  /** Routes straight from point to point, so the line goes through every point. */
  private class FakeRouter : Router {
    val calls = mutableListOf<List<RoutePoint>>()
    var failure: Exception? = null

    override suspend fun route(points: List<RoutePoint>, options: RouteOptions): Route {
      calls += points
      failure?.let { throw it }
      val line = points.map { it.position }
      return Route(line, 1_000.0 * points.size, 60.0 * points.size, BoundingBox(line.first(), line.last()))
    }
  }

  private class FakeStore : PlannedRouteStore {
    val plans = MutableStateFlow<Map<Long, PlannedRoute>>(emptyMap())
    private var nextId = 1L

    override val summaries: Flow<List<PlannedRouteSummary>> =
      plans.map { all -> all.values.map { PlannedRouteSummary(it.id!!, it.name, it.route.distanceMeters, it.route.durationSeconds, it.points.size) } }

    override suspend fun save(plan: PlannedRoute): Long {
      val id = plan.id ?: nextId++
      plans.value = plans.value + (id to plan.copy(id = id))
      return id
    }

    override suspend fun load(id: Long) = plans.value[id]

    override suspend fun delete(id: Long) {
      plans.value = plans.value - id
    }
  }

  private class FakeSearch : PlaceSearch {
    override suspend fun search(query: String, near: Position?, limit: Int) = emptyList<Place>()

    override suspend fun reverse(position: Position) = Place("100 Main St", "Ventura, CA", position)
  }

  private val router = FakeRouter()
  private val store = FakeStore()

  // Due north from Ventura, a point every 2 km.
  private fun north(km: Double) = Position(-119.29, 34.28 + km / 111.195)

  private val beach = Place("Ventura Beach", null, north(4.0))
  private val pier = Place("Ventura Pier", null, north(2.0))

  private fun editorTest(block: suspend TestScope.(PlanEditor) -> Unit) = runTest {
    val job = Job()
    try {
      block(PlanEditor(this + job, router, store, FakeSearch()))
    } finally {
      job.cancel()
    }
  }

  private val PlanEditor.now: PlanEditorState
    get() = assertNotNull(state.value)

  @Test
  fun newPlanStartsHereAndRoutesRightAway() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions(avoidTolls = true))
    advanceUntilIdle()

    val state = editor.now
    assertEquals(listOf("100 Main St", "Ventura Beach"), state.points.map { it.place.name })
    assertIs<RouteStatus.Ready>(state.route)
    assertEquals("To Ventura Beach", state.displayName)
    assertEquals(RouteOptions(avoidTolls = true), state.options)
  }

  @Test
  fun stopsGoBeforeTheDestinationAndQuickEditsRouteOnce() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions())
    advanceUntilIdle()
    router.calls.clear()

    editor.addStop(pier)
    advanceTimeBy(100)
    editor.togglePassThrough(1)
    advanceUntilIdle()

    assertEquals(listOf("100 Main St", "Ventura Pier", "Ventura Beach"), editor.now.points.map { it.place.name })
    assertEquals(1, router.calls.size)
    assertEquals(listOf(false, true, false), router.calls.single().map { it.passThrough })
  }

  @Test
  fun passThroughPointsGoIntoTheNearestStretch() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions())
    editor.addStop(pier)
    advanceUntilIdle()

    // Pressed beside the road at 3 km: between the pier (2 km) and the beach (4 km).
    editor.addPassThrough(Position(-119.289, 34.28 + 3.0 / 111.195))
    advanceUntilIdle()

    val points = editor.now.points
    assertEquals(4, points.size)
    assertTrue(points[2].passThrough)
    assertEquals("100 Main St", points[2].place.name)
  }

  @Test
  fun aLongPressOnAOnePointPlanAddsItsEnd() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions())
    editor.remove(1)
    advanceUntilIdle()

    editor.addPassThrough(north(6.0))
    advanceUntilIdle()

    val points = editor.now.points
    assertEquals(listOf(north(0.0), north(6.0)), points.map { it.place.position })
    assertFalse(points.last().passThrough)
    assertIs<RouteStatus.Ready>(editor.now.route)
  }

  @Test
  fun fullPlansSayWhy() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions())
    repeat(MAX_PLAN_POINTS - 2) { editor.addStop(Place("Stop $it", null, north(0.1 * (it + 1)))) }
    advanceUntilIdle()
    assertNull(editor.now.notice)

    editor.addStop(pier)

    assertEquals(MAX_PLAN_POINTS, editor.now.points.size)
    assertEquals("A route can have up to 9 points on the free routing server.", editor.now.notice)
  }

  @Test
  fun reorderingAndTheEndsStayingStops() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions())
    editor.addStop(pier)
    advanceUntilIdle()

    editor.move(1, by = 1)
    assertEquals(listOf("100 Main St", "Ventura Beach", "Ventura Pier"), editor.now.points.map { it.place.name })
    editor.togglePassThrough(0)
    editor.togglePassThrough(2)
    assertFalse(editor.now.points.any { it.passThrough })
    editor.move(0, by = -1)
    assertEquals("100 Main St", editor.now.points.first().place.name)
  }

  @Test
  fun fewerThanTwoPointsHasNoRoute() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions())
    advanceUntilIdle()

    editor.remove(0)
    advanceUntilIdle()

    assertEquals(RouteStatus.None, editor.now.route)
    assertNull(editor.now.plan)
  }

  @Test
  fun savingKeepsTheIdAndTracksChanges() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions())
    editor.rename("Beach run")
    advanceUntilIdle()

    editor.save()
    advanceUntilIdle()

    val id = assertNotNull(editor.now.id)
    assertFalse(editor.now.hasUnsavedChanges)
    assertEquals("Beach run", store.plans.value[id]?.name)

    editor.addStop(pier)
    assertTrue(editor.now.hasUnsavedChanges)
    advanceUntilIdle()
    editor.save()
    advanceUntilIdle()
    assertEquals(3, store.plans.value[id]?.points?.size)
    assertEquals(1, store.plans.value.size)
  }

  @Test
  fun openingASavedPlanUsesItsSavedRoute() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions())
    advanceUntilIdle()
    editor.save()
    advanceUntilIdle()
    val id = editor.now.id!!
    editor.close()
    router.calls.clear()

    editor.open(id)
    advanceUntilIdle()

    assertIs<RouteStatus.Ready>(editor.now.route)
    assertFalse(editor.now.hasUnsavedChanges)
    assertTrue(router.calls.isEmpty())
  }

  @Test
  fun deletingClosesAndForgets() = editorTest { editor ->
    editor.startNew(north(0.0), beach, RouteOptions())
    advanceUntilIdle()
    editor.save()
    advanceUntilIdle()

    editor.delete()
    advanceUntilIdle()

    assertNull(editor.state.value)
    assertTrue(store.plans.value.isEmpty())
  }

  @Test
  fun routingErrorsShowTheServersMessage() = editorTest { editor ->
    router.failure = RoutingException("Exceeded max locations: 10")
    editor.startNew(north(0.0), beach, RouteOptions())
    advanceUntilIdle()

    assertEquals(RouteStatus.Failed("Exceeded max locations: 10"), editor.now.route)
  }
}
