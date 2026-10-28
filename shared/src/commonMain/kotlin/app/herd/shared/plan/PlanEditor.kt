package app.herd.shared.plan

import app.herd.shared.geo.MeasuredLine
import app.herd.shared.planner.RouteStatus
import app.herd.shared.planner.normalizeLongitude
import app.herd.shared.routing.RouteOptions
import app.herd.shared.routing.Router
import app.herd.shared.routing.RoutingException
import app.herd.shared.search.Place
import app.herd.shared.search.PlaceSearch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position

/** Everything the plan editor shows. */
data class PlanEditorState(
  /** Null until the plan is saved. */
  val id: Long? = null,
  /** What the user typed; blank means [displayName] uses a default. */
  val name: String = "",
  val points: List<PlanPoint> = emptyList(),
  val options: RouteOptions = RouteOptions(),
  /** The route through [points]; [RouteStatus.None] while there are fewer than two. */
  val route: RouteStatus = RouteStatus.None,
  /** Something to tell the user ("A plan holds up to 9 points…"), ready to show. */
  val notice: String? = null,
  val hasUnsavedChanges: Boolean = true,
) {
  val displayName: String
    get() = name.ifBlank { points.lastOrNull()?.let { "To ${it.place.name}" } ?: "New route" }

  /** The plan as it stands, ready to save or drive; null until its route is in. */
  val plan: PlannedRoute?
    get() = (route as? RouteStatus.Ready)?.let { PlannedRoute(id, displayName, points, options, it.route) }
}

/**
 * Plans a route ahead: an ordered list of points, routed through as they change, and saved on the
 * phone. Shared by the Android and iOS apps; the UI forwards user actions here and draws [state]
 * (null while the editor is closed).
 *
 * Stops come from search and go before the destination. Pass-through points come from
 * long-presses and go into the stretch of the route nearest to them, which is how a route gets
 * pulled onto a particular road.
 */
class PlanEditor(
  private val scope: CoroutineScope,
  private val router: Router,
  private val store: PlannedRouteStore,
  private val search: PlaceSearch,
) {
  private val mutableState = MutableStateFlow<PlanEditorState?>(null)
  val state: StateFlow<PlanEditorState?> = mutableState.asStateFlow()

  /** Saved routes, newest first, for picking one to open. */
  val saved: Flow<List<PlannedRouteSummary>> = store.summaries

  private var routeJob: Job? = null
  private var loadJob: Job? = null

  /** A new plan from [from] (the driver's position) to [to], with their current route options. */
  fun startNew(from: Position, to: Place, options: RouteOptions) {
    close()
    val start = PlanPoint(Place(START_NAME, null, from))
    mutableState.value = PlanEditorState(points = listOf(start, PlanPoint(to)), options = options)
    nameFromAddress(from)
    reroute(immediately = true)
  }

  /** Opens a saved plan, with its saved route. */
  fun open(id: Long) {
    close()
    loadJob =
      scope.launch {
        val plan =
          try {
            store.load(id)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            null
          } ?: return@launch
        mutableState.value =
          PlanEditorState(
            id = plan.id,
            name = plan.name,
            points = plan.points,
            options = plan.options,
            route = RouteStatus.Ready(plan.route),
            hasUnsavedChanges = false,
          )
      }
  }

  fun close() {
    routeJob?.cancel()
    loadJob?.cancel()
    mutableState.value = null
  }

  /** Adds a stop just before the destination. */
  fun addStop(place: Place) {
    edit { points -> insert(points, if (points.size < 2) points.size else points.lastIndex, PlanPoint(place)) }
  }

  /**
   * Adds a pass-through point at [position], in the stretch of the route nearest to it. Its name
   * fills in once reverse geocoding answers. A plan with fewer than two points has no stretch to
   * put it in, so it becomes the new end instead.
   */
  fun addPassThrough(position: Position) {
    val state = current() ?: return
    val point = Position(longitude = normalizeLongitude(position.longitude), latitude = position.latitude)
    val between = state.points.size >= 2
    val index = insertionIndex(state, point)
    edit { insert(state.points, index, PlanPoint(Place(PASS_THROUGH_NAME, null, point), passThrough = between)) }
    nameFromAddress(point)
  }

  fun remove(index: Int) = edit { points -> points.filterIndexed { i, _ -> i != index } }

  /** Moves the point at [index] by [by] places (-1 is earlier). */
  fun move(index: Int, by: Int) = edit { points ->
    val to = index + by
    if (index !in points.indices || to !in points.indices) points
    else points.toMutableList().apply { add(to, removeAt(index)) }
  }

  /** Switches a point between a stop and pass-through. The first and last stay stops. */
  fun togglePassThrough(index: Int) = edit { points ->
    if (index <= 0 || index >= points.lastIndex) points
    else points.mapIndexed { i, point -> if (i == index) point.copy(passThrough = !point.passThrough) else point }
  }

  fun rename(name: String) {
    mutableState.update { it?.copy(name = name, hasUnsavedChanges = true) }
  }

  fun setOptions(options: RouteOptions) {
    mutableState.update { it?.copy(options = options, hasUnsavedChanges = true) }
    reroute(immediately = true)
  }

  fun retry() = reroute(immediately = true)

  /** Saves the plan (once its route is in). */
  fun save() {
    val plan = current()?.plan ?: return
    scope.launch {
      try {
        val id = store.save(plan)
        mutableState.update { it?.copy(id = id, hasUnsavedChanges = false, notice = null) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        mutableState.update { it?.copy(notice = "Couldn't save the route.") }
      }
    }
  }

  /** Deletes the saved plan and closes the editor. */
  fun delete() {
    val id = current()?.id
    close()
    if (id != null) scope.launch { runCatching { store.delete(id) } }
  }

  private fun current(): PlanEditorState? = mutableState.value

  /** Applies [change] to the points and reroutes; a no-op change does nothing. */
  private fun edit(change: (List<PlanPoint>) -> List<PlanPoint>?) {
    val state = current() ?: return
    val points = change(state.points) ?: return
    if (points == state.points) return
    mutableState.value = state.copy(points = points, notice = null, hasUnsavedChanges = true)
    reroute(immediately = false)
  }

  /** [points] with [point] added at [index], or null (and a notice) if the plan is full. */
  private fun insert(points: List<PlanPoint>, index: Int, point: PlanPoint): List<PlanPoint>? {
    if (points.size >= MAX_PLAN_POINTS) {
      mutableState.update { it?.copy(notice = "A route can have up to $MAX_PLAN_POINTS points on the free routing server.") }
      return null
    }
    return points.toMutableList().apply { add(index.coerceIn(0, size), point) }
  }

  /** Where a pass-through point at [position] belongs: in the stretch of the route nearest to it. */
  private fun insertionIndex(state: PlanEditorState, position: Position): Int {
    val points = state.points
    if (points.size < 2) return points.size
    val geometry = (state.route as? RouteStatus.Ready)?.route?.geometry ?: return points.lastIndex
    val line = MeasuredLine(geometry)
    var from = 0.0
    val pointAlong = points.map { (line.nearest(it.place.position, fromAlong = from)?.along ?: from).also { a -> from = a } }
    val along = line.nearest(position)?.along ?: return points.lastIndex
    return pointAlong.count { it <= along }.coerceIn(1, points.lastIndex)
  }

  /** Names the point at [position] after its address, if it still has its placeholder name. */
  private fun nameFromAddress(position: Position) {
    scope.launch {
      val found =
        try {
          search.reverse(position)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          null
        } ?: return@launch
      mutableState.update { state ->
        state?.copy(
          points =
            state.points.map { point ->
              val place = point.place
              if (place.position == position && place.name in PLACEHOLDER_NAMES) {
                point.copy(place = place.copy(name = found.name, subtitle = found.subtitle))
              } else point
            }
        )
      }
    }
  }

  private fun reroute(immediately: Boolean) {
    routeJob?.cancel()
    val state = current() ?: return
    if (state.points.size < 2) {
      mutableState.update { it?.copy(route = RouteStatus.None) }
      return
    }
    mutableState.update { it?.copy(route = RouteStatus.Loading) }
    routeJob =
      scope.launch {
        // Several quick edits (reordering, toggling) make one request.
        if (!immediately) delay(ROUTE_DEBOUNCE_MS)
        val points = current()?.points ?: return@launch
        val options = current()?.options ?: return@launch
        val status =
          try {
            RouteStatus.Ready(router.route(points.toRoutePoints(), options))
          } catch (e: CancellationException) {
            throw e
          } catch (e: RoutingException) {
            RouteStatus.Failed(e.message ?: GENERIC_ROUTE_ERROR)
          } catch (e: Exception) {
            RouteStatus.Failed(GENERIC_ROUTE_ERROR)
          }
        ensureActive()
        mutableState.update { it?.copy(route = status) }
      }
  }

  companion object {
    const val ROUTE_DEBOUNCE_MS = 400L
    const val START_NAME = "Start"
    const val PASS_THROUGH_NAME = "Pass-through point"
    private val PLACEHOLDER_NAMES = setOf(START_NAME, PASS_THROUGH_NAME)
    private const val GENERIC_ROUTE_ERROR =
      "Couldn't reach the routing server. Check your connection and try again."
  }
}
