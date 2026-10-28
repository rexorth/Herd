package app.herd.android.nav

import android.content.Context
import android.location.Location
import android.speech.tts.TextToSpeech
import app.herd.shared.net.HerdServices
import app.herd.shared.routing.RouteOptions
import app.herd.shared.routing.RoutingException
import app.herd.shared.routing.distanceUnitsFor
import app.herd.shared.routing.valhallaErrorMessage
import app.herd.shared.routing.valhallaNavigationOptions
import app.herd.shared.search.Place
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.location.toUserLocation
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import com.stadiamaps.ferrostar.googleplayservices.FusedNavigationLocationProvider
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.WellKnownRouteProvider
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep

/** What the navigation screen shows. */
sealed interface NavUiState {
  data object Idle : NavUiState

  /** Fetching the turn-by-turn route. */
  data class Starting(val destination: Place) : NavUiState

  data class Navigating(
    val destination: Place,
    val trip: TripState.Navigating,
    /** The line being followed. Changes when the trip reroutes. */
    val routeGeometry: List<Position>,
    /** From the moment the car is found off the route until the new route replaces it. */
    val isRerouting: Boolean,
    val isMuted: Boolean,
    val isSimulating: Boolean,
  ) : NavUiState

  data class Arrived(val destination: Place) : NavUiState

  /** [message] is ready to show the user. */
  data class Failed(val destination: Place, val message: String) : NavUiState
}

/**
 * Turn-by-turn navigation, one trip at a time, on Ferrostar.
 *
 * Ferrostar takes the route and the stream of location fixes and works out the current step, what
 * to say and when, and when the driver has left the route (it then asks for a new one). Herd
 * supplies the routes (Valhalla, with the trip's avoid settings), the voice, and a foreground
 * service that keeps the trip running with the screen off or another app open.
 *
 * Ferrostar is Android-only, so this lives in the app; the iOS app will wrap Ferrostar's Swift
 * package the same way.
 */
class Navigator(context: Context, private val scope: CoroutineScope) {
  private val appContext = context.applicationContext

  private val http =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", HerdServices.USER_AGENT).build())
      }
      // A reroute that stalls (signal lost mid-request) blocks the next attempt until it times out.
      .callTimeout(Duration.ofSeconds(20))
      .build()
      .toOkHttpClientProvider()

  private val liveLocation = FusedNavigationLocationProvider(appContext)
  /** Fixes come from GPS, or (Simulate, debug builds) from a car driving the route. */
  private val locations =
    NavigationLocationProvider(liveLocation, SimulatedLocationProvider(warpFactor = SIMULATION_SPEEDUP))

  private val voice =
    AndroidTtsObserver(appContext).apply {
      // Directions are English. Speak them with an English voice even on phones set to another language.
      statusObserver =
        object : AndroidTtsStatusListener {
          override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
            if (tts != null && Locale.getDefault().language != Locale.ENGLISH.language) tts.setLanguage(Locale.US)
          }

          override fun onTtsShutdownAndRelease() = Unit

          override fun onTtsSpeakError(utteranceId: String, status: Int) = Unit
        }
    }

  /** The trip's Valhalla options (avoid settings, units). Reroutes reuse them. */
  @Volatile private var tripOptions: String? = null

  private val core =
    FerrostarCore(
        customRouteProvider = CustomRouteProvider { location, waypoints -> fetchRoutes(location, waypoints) },
        httpClient = http,
        locationProvider = locations,
        navigationControllerConfig = navigationConfig(),
        foregroundServiceManager =
          FerrostarForegroundServiceManager(appContext, DefaultForegroundNotificationBuilder(appContext)),
      )
      .apply { spokenInstructionObserver = voice }

  private val mutableState = MutableStateFlow<NavUiState>(NavUiState.Idle)
  val state: StateFlow<NavUiState> = mutableState.asStateFlow()

  private var tripJob: Job? = null
  // The route line only changes on a reroute, so it's converted once per route, not once per fix.
  private var geometrySource: List<GeographicCoordinate>? = null
  private var geometry: List<Position> = emptyList()

  init {
    scope.launch {
      combine(core.state, voice.muteState, locations.isSimulating, ::Triple).collect { (nav, muted, simulating) ->
        onCoreState(nav, muted, simulating)
      }
    }
  }

  /**
   * Starts navigating to [destination] from the user's position, or [fallbackOrigin] if the phone
   * has no recent fix of its own. With [simulate], a simulated car drives the route instead of GPS.
   */
  fun start(destination: Place, options: RouteOptions, fallbackOrigin: Position?, simulate: Boolean) {
    stop()
    tripOptions = valhallaNavigationOptions(options, distanceUnitsFor(Locale.getDefault().country))
    mutableState.value = NavUiState.Starting(destination)
    // The speech engine takes a moment to start; do it while the route loads.
    voice.start()
    tripJob =
      scope.launch {
        try {
          val here = currentLocation(fallbackOrigin) ?: throw RoutingException(NO_LOCATION)
          val target = Waypoint(destination.position.toCoordinate(), WaypointKind.BREAK)
          val route = core.getRoutes(here, listOf(target)).firstOrNull() ?: throw RoutingException(NO_ROUTE)
          if (simulate) locations.enableSimulationOn(route) else locations.disableSimulation()
          core.startNavigation(route)
        } catch (e: CancellationException) {
          throw e
        } catch (e: RoutingException) {
          fail(destination, e.message ?: NO_ROUTE)
        } catch (e: Exception) {
          fail(destination, CONNECTION_ERROR)
        }
      }
  }

  /** Ends the trip (or clears the arrival or error message). */
  fun stop() {
    tripJob?.cancel()
    mutableState.value = NavUiState.Idle
    endTrip()
  }

  fun setMuted(muted: Boolean) = voice.setMuted(muted)

  private fun onCoreState(nav: NavigationState, muted: Boolean, simulating: Boolean) {
    val current = mutableState.value
    val destination =
      when (current) {
        is NavUiState.Starting -> current.destination
        is NavUiState.Navigating -> current.destination
        else -> return
      }
    when (val trip = nav.tripState) {
      is TripState.Navigating -> {
        // Ferrostar flags the deviation as soon as it sees it, but only reports fetching while the
        // request is out, and it waits a few seconds between attempts. The new route clears both.
        val rerouting = nav.isCalculatingNewRoute || trip.deviation is RouteDeviation.Deviation
        mutableState.value =
          NavUiState.Navigating(destination, trip, positions(nav.routeGeometry), rerouting, muted, simulating)
      }

      is TripState.Complete -> {
        mutableState.value = NavUiState.Arrived(destination)
        // Let "You have arrived" finish before the speech engine and the service go away.
        tripJob =
          scope.launch {
            delay(ARRIVAL_LINGER_MS)
            endTrip()
          }
      }

      // Stopped from the notification's button.
      is TripState.Idle -> if (current is NavUiState.Navigating) stop()
    }
  }

  private fun fail(destination: Place, message: String) {
    endTrip()
    mutableState.value = NavUiState.Failed(destination, message)
  }

  private fun endTrip() {
    core.stopNavigation() // also stops the foreground service and any speech in progress
    locations.disableSimulation()
    voice.shutdown()
  }

  /**
   * Ferrostar asks for routes here: at the start and on every reroute. Off the main thread: reading
   * the response body is network I/O, and a long trip's route is megabytes of JSON to parse.
   */
  private suspend fun fetchRoutes(location: UserLocation, waypoints: List<Waypoint>): List<Route> =
    withContext(Dispatchers.IO) {
      val provider = WellKnownRouteProvider.Valhalla(ROUTE_URL, "auto", tripOptions)
      RouteAdapter.fromWellKnownRouteProvider(provider).use { adapter ->
        val response = http.call(adapter.generateRequest(location, waypoints))
        val body = response.bodyBytes()
        if (!response.isSuccessful) {
          throw RoutingException(
            body?.let { valhallaErrorMessage(it.decodeToString()) } ?: "Routing failed (HTTP ${response.code})"
          )
        }
        adapter.parseResponse(body ?: throw RoutingException(NO_ROUTE))
      }
    }

  /** The phone's own fix if it's recent (it carries the heading, so the route starts the right way). */
  private suspend fun currentLocation(fallback: Position?): UserLocation? {
    val fix: Location? = runCatching { liveLocation.lastLocation() }.getOrNull()
    if (fix != null && System.currentTimeMillis() - fix.time < FRESH_FIX_MS) return fix.toUserLocation()
    return fallback?.let { UserLocation(it.toCoordinate(), 50.0, null, Instant.now(), null) }
  }

  private fun positions(coordinates: List<GeographicCoordinate>): List<Position> {
    if (coordinates !== geometrySource) {
      geometrySource = coordinates
      geometry = coordinates.map { Position(longitude = it.lng, latitude = it.lat) }
    }
    return geometry
  }

  private companion object {
    val ROUTE_URL = "${HerdServices.VALHALLA_BASE_URL}/route"
    /** A simulated car covers 10 m per tick; this many ticks a second is about 45 mph. */
    const val SIMULATION_SPEEDUP = 2u
    const val FRESH_FIX_MS = 30_000L
    const val ARRIVAL_LINGER_MS = 5_000L
    const val NO_LOCATION = "Waiting for your location. Try again in a moment."
    const val NO_ROUTE = "No route found."
    const val CONNECTION_ERROR = "Couldn't reach the routing server. Check your connection and try again."

    fun navigationConfig() =
      NavigationControllerConfig(
        // A stop counts as visited within 100 m.
        waypointAdvance = WaypointAdvanceMode.WaypointWithinRange(100.0),
        // Next step once the driver has come within 30 m of the turn and then left it by 5 m,
        // ignoring fixes less accurate than 32 m.
        stepAdvanceCondition =
          stepAdvanceDistanceEntryAndExit(distanceToEndOfStep = 30u, distanceAfterEndOfStep = 5u, minimumHorizontalAccuracy = 32u),
        arrivalStepAdvanceCondition = stepAdvanceDistanceToEndOfStep(distance = 15u, minimumHorizontalAccuracy = 32u),
        // Off route from 25 m away with good GPS, up to 50 m with poor GPS (see OffRouteDetector).
        // Ferrostar's fixed 50 m threshold left the dot on the old route for seconds after a turn.
        routeDeviationTracking = RouteDeviationTracking.Custom(OffRouteDeviationDetector()),
        snappedLocationCourseFiltering = CourseFiltering.SNAP_TO_ROUTE,
      )
  }
}

private fun Position.toCoordinate() = GeographicCoordinate(lat = latitude, lng = longitude)
