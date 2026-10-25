package app.herd.shared.planner

import app.herd.shared.routing.Route
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position

/** Everything the search-and-route UI shows. */
data class PlannerState(
  val query: String = "",
  val suggestions: List<Place> = emptyList(),
  val isSearching: Boolean = false,
  val searchError: String? = null,
  val destination: Place? = null,
  val options: RouteOptions = RouteOptions(),
  val route: RouteStatus = RouteStatus.None,
)

sealed interface RouteStatus {
  /** No destination picked. */
  data object None : RouteStatus

  /** A destination is picked, but there's no location fix to route from yet. */
  data object NeedsLocation : RouteStatus

  data object Loading : RouteStatus

  data class Ready(val route: Route) : RouteStatus

  /** [message] is ready to show the user. */
  data class Failed(val message: String) : RouteStatus
}

/**
 * Search for a destination, then route to it from the user's position. Shared by the Android and
 * iOS apps; the UI forwards user actions here and draws [state].
 *
 * The public servers behind [search] and [router] have fair-use limits, so typing is debounced,
 * superseded requests are cancelled, and location updates never trigger a new route on their own.
 */
class RoutePlanner(
  private val scope: CoroutineScope,
  private val search: PlaceSearch,
  private val router: Router,
) {
  private val mutableState = MutableStateFlow(PlannerState())
  val state: StateFlow<PlannerState> = mutableState.asStateFlow()

  private var origin: Position? = null
  /** The pending or running search. Every user action that changes what to search cancels it. */
  private var searchJob: Job? = null
  private var routeJob: Job? = null
  private var reverseJob: Job? = null

  /** Updates the text and searches it after a short pause in typing. */
  fun onQueryChanged(text: String) {
    searchJob?.cancel()
    mutableState.update { it.copy(query = text, searchError = null) }
    val query = text.trim()
    if (query.length < MIN_QUERY_LENGTH) {
      mutableState.update { it.copy(suggestions = emptyList(), isSearching = false) }
      return
    }
    searchJob =
      scope.launch {
        delay(SEARCH_DEBOUNCE_MS)
        runSearch(query)
      }
  }

  /**
   * The user pressed Enter: search exactly what's typed now, without waiting for the debounce,
   * and go to the top result. Suggestions on screen may be for older text, so they aren't used.
   */
  fun submitQuery() {
    val query = mutableState.value.query.trim()
    if (query.length < MIN_QUERY_LENGTH) return
    searchJob?.cancel()
    searchJob =
      scope.launch {
        val results = runSearch(query) ?: return@launch
        val top = results.firstOrNull()
        if (top == null) {
          mutableState.update { it.copy(searchError = "No places found for \u201c$query\u201d.") }
        } else {
          reverseJob?.cancel()
          setDestination(top)
        }
      }
  }

  /** The user's latest position. Routes from here; also biases search results. */
  fun updateOrigin(position: Position) {
    origin = position
    if (mutableState.value.route == RouteStatus.NeedsLocation) requestRoute()
  }

  fun selectPlace(place: Place) {
    searchJob?.cancel()
    reverseJob?.cancel()
    setDestination(place)
  }

  /** Route to a point picked on the map. Its name fills in once reverse geocoding answers. */
  fun pickPoint(position: Position) {
    val point = Position(longitude = normalizeLongitude(position.longitude), latitude = position.latitude)
    searchJob?.cancel()
    reverseJob?.cancel()
    setDestination(Place(name = DROPPED_PIN_NAME, subtitle = null, position = point))
    reverseJob =
      scope.launch {
        val found =
          try {
            search.reverse(point)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            null
          } ?: return@launch
        // Keep the exact pressed point as the destination; only borrow the name.
        mutableState.update {
          if (it.destination?.position != point) it
          else
            it.copy(
              query = found.name,
              destination = it.destination.copy(name = found.name, subtitle = found.subtitle),
            )
        }
      }
  }

  fun setOptions(options: RouteOptions) {
    mutableState.update { it.copy(options = options) }
    if (mutableState.value.destination != null) requestRoute()
  }

  fun retry() = requestRoute()

  /** Drops the destination and route. Route options stay as the user set them. */
  fun clear() {
    searchJob?.cancel()
    routeJob?.cancel()
    reverseJob?.cancel()
    mutableState.update { PlannerState(options = it.options) }
  }

  private fun setDestination(place: Place) {
    mutableState.update {
      it.copy(
        query = place.name,
        suggestions = emptyList(),
        isSearching = false,
        searchError = null,
        destination = place,
      )
    }
    requestRoute()
  }

  /** Searches [query] and shows the results as suggestions. Returns them, or null if the search failed. */
  private suspend fun runSearch(query: String): List<Place>? {
    mutableState.update { it.copy(isSearching = true) }
    return try {
      search.search(query, near = origin).also { results ->
        mutableState.update { it.copy(suggestions = results) }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      mutableState.update {
        it.copy(suggestions = emptyList(), searchError = "Search isn't available right now.")
      }
      null
    } finally {
      mutableState.update { it.copy(isSearching = false) }
    }
  }

  private fun requestRoute() {
    routeJob?.cancel()
    val destination = mutableState.value.destination ?: return
    val from = origin
    if (from == null) {
      mutableState.update { it.copy(route = RouteStatus.NeedsLocation) }
      return
    }
    mutableState.update { it.copy(route = RouteStatus.Loading) }
    val options = mutableState.value.options
    routeJob =
      scope.launch {
        val status =
          try {
            RouteStatus.Ready(router.route(from, destination.position, options))
          } catch (e: CancellationException) {
            throw e
          } catch (e: RoutingException) {
            RouteStatus.Failed(e.message ?: GENERIC_ROUTE_ERROR)
          } catch (e: Exception) {
            RouteStatus.Failed(GENERIC_ROUTE_ERROR)
          }
        ensureActive()
        mutableState.update { it.copy(route = status) }
      }
  }

  companion object {
    const val SEARCH_DEBOUNCE_MS = 300L
    const val MIN_QUERY_LENGTH = 3
    const val DROPPED_PIN_NAME = "Dropped pin"
    private const val GENERIC_ROUTE_ERROR =
      "Couldn't reach the routing server. Check your connection and try again."
  }
}

/** Map gestures can report longitudes past ±180° after panning across the antimeridian. */
internal fun normalizeLongitude(longitude: Double): Double {
  val wrapped = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
  return if (wrapped == -180.0 && longitude > 0) 180.0 else wrapped
}
