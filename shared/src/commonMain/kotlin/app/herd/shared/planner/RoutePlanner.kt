package app.herd.shared.planner

import app.herd.shared.routing.Route
import app.herd.shared.routing.RouteOptions
import app.herd.shared.routing.Router
import app.herd.shared.routing.RoutingException
import app.herd.shared.search.AddressLocator
import app.herd.shared.search.Place
import app.herd.shared.search.PlaceSearch
import app.herd.shared.search.TypedAddress
import app.herd.shared.search.distanceKm
import app.herd.shared.search.hasLocality
import app.herd.shared.search.isAddress
import app.herd.shared.search.isStreetAddress
import app.herd.shared.search.parseStreetAddress
import app.herd.shared.search.typedTown
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
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
  /**
   * The text is a street address and no suggestion is that exact house, so the UI offers a
   * "Find" row that looks the address up more thoroughly (same as pressing Enter).
   */
  val offerExactAddress: Boolean = false,
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
  /** Finds street addresses [search] doesn't have; used only on Enter or the "Find" row. Null turns it off. */
  private val addressLocator: AddressLocator? = null,
) {
  private val mutableState = MutableStateFlow(PlannerState())
  val state: StateFlow<PlannerState> = mutableState.asStateFlow()

  private var origin: Position? = null
  /** The pending or running search. Every user action that changes what to search cancels it. */
  private var searchJob: Job? = null
  private var routeJob: Job? = null
  private var reverseJob: Job? = null
  /** The user's town, looked up once per area to complete addresses typed without one. */
  private var localityCache: Pair<Position, String>? = null

  /** Updates the text and searches it after a short pause in typing. */
  fun onQueryChanged(text: String) {
    searchJob?.cancel()
    mutableState.update { it.copy(query = text, searchError = null) }
    val query = text.trim()
    if (!isStreetAddress(query)) mutableState.update { it.copy(offerExactAddress = false) }
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
   * The user pressed Enter (or the "Find" row): search exactly what's typed now, without waiting
   * for the debounce. Suggestions on screen may be for older text, so they aren't used.
   *
   * A place name goes to the top result. A street address goes to that exact house: from search
   * if it has it, otherwise from [addressLocator]. When the address could be on more than one
   * street, the candidates become the suggestions for the user to pick.
   */
  fun submitQuery() {
    val query = mutableState.value.query.trim()
    if (query.length < MIN_QUERY_LENGTH) return
    searchJob?.cancel()
    searchJob =
      scope.launch {
        val results = runSearch(query) ?: return@launch
        val typed = parseStreetAddress(query)
        val locator = addressLocator
        if (typed == null || locator == null) {
          val top = results.firstOrNull()
          if (top != null) goTo(top)
          else mutableState.update { it.copy(searchError = "No places found for \u201c$query\u201d.") }
          return@launch
        }
        results.firstOrNull { isTypedHouse(it, typed, query) }?.let { goTo(it); return@launch }

        val matches = lookUpAddress(locator, query)
        when {
          matches == null ->
            mutableState.update { it.copy(searchError = "Couldn't look up that address right now.") }
          matches.size == 1 -> goTo(matches.single())
          matches.isNotEmpty() ->
            mutableState.update { it.copy(suggestions = matches, offerExactAddress = false) }
          else ->
            mutableState.update {
              it.copy(
                // Search's results for an address it doesn't know are other streets entirely.
                suggestions = emptyList(),
                offerExactAddress = false,
                searchError =
                  "Couldn't find \u201c$query\u201d. Try adding the city, or long-press the spot on the map.",
              )
            }
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
        offerExactAddress = false,
        destination = place,
      )
    }
    requestRoute()
  }

  private fun goTo(place: Place) {
    reverseJob?.cancel()
    setDestination(place)
  }

  /** Searches [query] and shows the results as suggestions. Returns them, or null if the search failed. */
  private suspend fun runSearch(query: String): List<Place>? {
    mutableState.update { it.copy(isSearching = true) }
    return try {
      search.search(query, near = origin).also { results ->
        val typed = parseStreetAddress(query)
        val offer =
          addressLocator != null && typed != null && results.none { isTypedHouse(it, typed, query) }
        mutableState.update { it.copy(suggestions = results, offerExactAddress = offer) }
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

  /**
   * Whether [place] is the house the user typed: same number and street, and in the town they
   * typed, or near them if they typed none. "123 Main St" in a city 100 km away is a different
   * house, even though search ranked it first.
   */
  private fun isTypedHouse(place: Place, typed: TypedAddress, query: String): Boolean {
    if (!place.isAddress(typed)) return false
    val town = typedTown(query)
    if (town != null) return place.locality?.contains(town, ignoreCase = true) == true
    if (hasLocality(query)) return true // a ZIP code: search already narrowed by it
    val here = origin ?: return true
    return distanceKm(here, place.position) <= NEARBY_ADDRESS_KM
  }

  /**
   * Asks [locator] for [query], adding the user's town when the query names none (the Census
   * geocoder finds nothing without one). Returns null if the lookup failed.
   */
  private suspend fun lookUpAddress(locator: AddressLocator, query: String): List<Place>? {
    mutableState.update { it.copy(isSearching = true) }
    return try {
      val address = if (hasLocality(query)) query else localityHere()?.let { "$query, $it" } ?: query
      locator.locate(address)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } finally {
      mutableState.update { it.copy(isSearching = false) }
    }
  }

  /** "Santa Barbara, California" for the user's position, reused while they stay within a few km. */
  private suspend fun localityHere(): String? {
    val here = origin ?: return null
    localityCache?.let { (at, locality) -> if (isNear(at, here)) return locality }
    val locality =
      try {
        search.reverse(here)?.locality
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      } ?: return null
    localityCache = here to locality
    return locality
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
    /** An address typed without a town counts as "here" within this distance. */
    private const val NEARBY_ADDRESS_KM = 30.0
    private const val GENERIC_ROUTE_ERROR =
      "Couldn't reach the routing server. Check your connection and try again."
  }
}

/** Within about 5 km: close enough to be in the same town. */
private fun isNear(a: Position, b: Position): Boolean =
  abs(a.latitude - b.latitude) < 0.05 && abs(a.longitude - b.longitude) < 0.05

/** Map gestures can report longitudes past ±180° after panning across the antimeridian. */
internal fun normalizeLongitude(longitude: Double): Double {
  val wrapped = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
  return if (wrapped == -180.0 && longitude > 0) 180.0 else wrapped
}
