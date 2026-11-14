package app.herd.android.nav

import android.content.Context
import android.location.Location
import android.speech.tts.TextToSpeech
import app.herd.shared.geo.distanceMeters
import app.herd.shared.net.HerdServices
import app.herd.shared.plan.JoinMode
import app.herd.shared.plan.PlanTrip
import app.herd.shared.plan.PlannedRoute
import app.herd.shared.plan.TripLeg
import app.herd.shared.plan.TripWaypoint
import app.herd.shared.routing.RouteOptions
import app.herd.shared.routing.RoutingException
import app.herd.shared.routing.distanceUnitsFor
import app.herd.shared.routing.valhallaErrorMessage
import app.herd.shared.routing.valhallaNavigationOptions
import app.herd.shared.search.Place
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AlternativeRouteProcessor
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import com.stadiamaps.ferrostar.core.CorrectiveAction
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.RouteDeviationHandler
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
import uniffi.ferrostar.ValhallaWaypointProperties
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.WellKnownRouteProvider
import uniffi.ferrostar.createWaypointWithValhallaProperties
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
    /** The line being followed (the current leg). Changes when the trip reroutes or a leg begins. */
    val routeGeometry: List<Position>,
    /** From the moment the car is found off the route until the new route replaces it. */
    val isRerouting: Boolean,
    val isMuted: Boolean,
    val isSimulating: Boolean,
    /** The planned route being driven, if this trip is one. */
    val plan: PlannedRoute? = null,
    /** The start of [routeGeometry] that gets onto (or back onto) [plan]; empty when there's none. */
    val joinLeg: List<Position> = emptyList(),
    /** The stretch of [plan] already driven, to draw as done. */
    val completedGeometry: List<Position> = emptyList(),
    /** The last plan point reached, so earlier markers can be drawn as done. */
    val reachedIndex: Int = -1,
    /** The stop this leg is heading for. */
    val targetPlace: Place? = null,
    /** A drive-through point just passed, shown briefly then gone. */
    val driveThrough: Place? = null,
    /** Where a simulated detour is heading (debug builds). */
    val detourTarget: Position? = null,
    val simSpeed: Int = DEFAULT_SIM_SPEED,
  ) : NavUiState

  /** Paused at a stop on a plan, waiting for the driver to carry on to [next]. */
  data class AtStop(
    val reached: Place,
    val next: Place,
    val stopsLeft: Int,
    val isSimulating: Boolean,
    val plan: PlannedRoute,
    val completedGeometry: List<Position>,
    val reachedIndex: Int,
    val detourTarget: Position? = null,
    val simSpeed: Int = DEFAULT_SIM_SPEED,
    /** Set to the reason the last attempt to carry on failed, if any. */
    val error: String? = null,
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
 * A planned route is driven one leg at a time — current stop to next stop — so Herd, not Ferrostar,
 * decides which stops are done. Arriving at a stop pauses the trip ([NavUiState.AtStop]); the next
 * leg is fetched when the driver carries on. That way the trip can't end early or skip a stop, even
 * on a route that loops back on itself.
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
  /** Drives the route in Simulate (debug builds); its speed is adjustable while a trip runs. */
  private val simulated = SimulatedLocationProvider().apply { warpFactor = DEFAULT_SIM_SPEED.toUInt() }
  /** Fixes come from GPS, or (Simulate, debug builds) from a car driving the route. */
  private val locations = NavigationLocationProvider(liveLocation, simulated)

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

  // A planned route being driven. Ferrostar asks for reroutes on its own thread, so the plan's
  // progress and the join point are shared under planLock.
  private val planLock = Any()
  private var planTrip: PlanTrip? = null
  private var lastFix: Position? = null
  /** Where the route being followed meets the plan: the end of the join leg. */
  private var joinAt: Position? = null
  /** Where the route a reroute or a new leg is fetching will meet the plan. */
  private var pendingJoinAt: Position? = null
  private var joinLegSource: List<GeographicCoordinate>? = null
  private var joinLeg: List<Position> = emptyList()
  /** A drive-through point just passed and when its notice should clear. */
  private var driveThroughNotice: Pair<Place, Long>? = null
  private var tripPlan: PlannedRoute? = null

  /** True while the trip is a simulation, so a leg can be re-simulated after a stop. */
  @Volatile private var tripSimulating = false
  /** True while paused at a stop, so the engine going idle isn't taken for the driver ending the trip. */
  @Volatile private var pausingAtStop = false
  @Volatile private var simSpeed = DEFAULT_SIM_SPEED

  /** The route being followed, for the simulator to drive. */
  @Volatile private var currentRoute: Route? = null
  /** Where a simulated detour ends (debug builds). */
  private var detourEnd: Position? = null

  init {
    // Off a planned route, go back onto the plan rather than straight to the destination.
    core.deviationHandler = RouteDeviationHandler { _, _, remaining ->
      val leg =
        synchronized(planLock) {
          val trip = planTrip ?: return@synchronized null
          val here = lastFix ?: return@synchronized null
          trip.rejoin(here).also { pendingJoinAt = it.joinAt }
        }
      CorrectiveAction.GetNewRoutes(leg?.waypoints?.map { it.toFerrostar() } ?: remaining)
    }
    // Ferrostar's own behavior (take the first new route), keeping hold of it for the simulator.
    core.alternativeRouteProcessor = AlternativeRouteProcessor { core, routes ->
      val route = routes.firstOrNull() ?: return@AlternativeRouteProcessor
      currentRoute = route
      core.replaceRoute(route)
    }
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
  fun start(destination: Place, options: RouteOptions, fallbackOrigin: Position?, simulate: Boolean) =
    begin(destination, options, fallbackOrigin, simulate, plan = null) {
      listOf(Waypoint(destination.position.toCoordinate(), WaypointKind.BREAK))
    }

  /**
   * Starts navigating [plan]. Away from its first point, [mode] says whether to drive there first
   * or join the plan where it's closest. Leaving the route later leads back onto the plan.
   */
  fun startPlan(plan: PlannedRoute, mode: JoinMode, fallbackOrigin: Position?, simulate: Boolean) =
    begin(plan.points.last().place, plan.options, fallbackOrigin, simulate, plan) { here ->
      val trip = PlanTrip(plan)
      val leg = trip.start(here.coordinates.toPosition(), mode)
      synchronized(planLock) {
        planTrip = trip
        applyLeg(leg)
      }
      leg.waypoints.map { it.toFerrostar() }
    }

  /** Carries on from a stop to the next leg of the plan. */
  fun continueToNextStop() {
    val paused = mutableState.value as? NavUiState.AtStop ?: return
    val trip = planTrip ?: return
    val here = synchronized(planLock) { lastFix } ?: return
    mutableState.value = paused.copy(error = null)
    tripJob?.cancel()
    tripJob =
      scope.launch {
        pausingAtStop = false
        val leg = trip.nextLeg(here) ?: return@launch
        synchronized(planLock) { applyLeg(leg) }
        try {
          val route =
            core.getRoutes(here.toUserLocation(), leg.waypoints.map { it.toFerrostar() }).firstOrNull()
              ?: throw RoutingException(NO_ROUTE)
          currentRoute = route
          if (tripSimulating) locations.enableSimulationOn(route) else locations.disableSimulation()
          core.startNavigation(route)
        } catch (e: CancellationException) {
          throw e
        } catch (e: RoutingException) {
          atStopError(e.message ?: NO_ROUTE)
        } catch (e: Exception) {
          atStopError(CONNECTION_ERROR)
        }
      }
  }

  /** The speed the simulated car drives at, 1–10× (debug builds). Takes effect right away. */
  fun setSimSpeed(speed: Int) {
    val clamped = speed.coerceIn(MIN_SIM_SPEED, MAX_SIM_SPEED)
    simSpeed = clamped
    simulated.warpFactor = clamped.toUInt()
    // Reflect it in the current state so the slider stays put.
    mutableState.value =
      when (val s = mutableState.value) {
        is NavUiState.Navigating -> s.copy(simSpeed = clamped)
        is NavUiState.AtStop -> s.copy(simSpeed = clamped)
        else -> s
      }
  }

  /**
   * Debug builds, while simulating: the simulated car leaves the route and drives to [to], then
   * follows navigation's route again. For trying out rerouting without a car.
   */
  fun simulateDetour(to: Position) {
    if (!locations.isSimulating.value || mutableState.value !is NavUiState.Navigating) return
    val from = synchronized(planLock) { lastFix } ?: return
    scope.launch {
      val here = UserLocation(from.toCoordinate(), 5.0, null, Instant.now(), null)
      val detour =
        runCatching { core.getRoutes(here, listOf(Waypoint(to.toCoordinate(), WaypointKind.BREAK))) }.getOrNull()?.firstOrNull()
          ?: return@launch
      detourEnd = to
      locations.enableSimulationOn(detour)
    }
  }

  /** Fetches the first route (to the [waypoints] for the driver's position) and starts following it. */
  private fun begin(
    destination: Place,
    options: RouteOptions,
    fallbackOrigin: Position?,
    simulate: Boolean,
    plan: PlannedRoute? = null,
    waypoints: (UserLocation) -> List<Waypoint>,
  ) {
    stop()
    tripOptions = valhallaNavigationOptions(options, distanceUnitsFor(Locale.getDefault().country))
    tripPlan = plan
    tripSimulating = simulate
    mutableState.value = NavUiState.Starting(destination)
    // The speech engine takes a moment to start; do it while the route loads.
    voice.start()
    tripJob =
      scope.launch {
        try {
          val here = currentLocation(fallbackOrigin) ?: throw RoutingException(NO_LOCATION)
          val route = core.getRoutes(here, waypoints(here)).firstOrNull() ?: throw RoutingException(NO_ROUTE)
          currentRoute = route
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

  /** Records the join point for a new leg and clears the old one so it's recomputed. */
  private fun applyLeg(leg: TripLeg) {
    joinAt = null
    pendingJoinAt = leg.joinAt
    joinLegSource = null
    joinLeg = emptyList()
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
    // While paused at a stop, the engine is idle on purpose; don't read that as the trip ending.
    if (pausingAtStop && nav.tripState is TripState.Idle) return
    val destination =
      tripPlan?.points?.lastOrNull()?.place
        ?: (current as? NavUiState.Starting)?.destination
        ?: (current as? NavUiState.Navigating)?.destination
        ?: return
    when (val trip = nav.tripState) {
      is TripState.Navigating -> {
        pausingAtStop = false
        // Ferrostar flags the deviation as soon as it sees it, but only reports fetching while the
        // request is out, and it waits a few seconds between attempts. The new route clears both.
        val rerouting = nav.isCalculatingNewRoute || trip.deviation is RouteDeviation.Deviation
        val routeLine = positions(nav.routeGeometry)
        val here = trip.userLocation.coordinates.toPosition()
        val follow = followPlan(here, trip.userLocation.horizontalAccuracy, nav.routeGeometry, routeLine)
        endSimulatedDetour(here)
        mutableState.value =
          NavUiState.Navigating(
            destination = destination,
            trip = trip,
            routeGeometry = routeLine,
            isRerouting = rerouting,
            isMuted = muted,
            isSimulating = simulating,
            plan = tripPlan,
            joinLeg = follow.joinLeg,
            completedGeometry = follow.completed,
            reachedIndex = follow.reachedIndex,
            targetPlace = follow.targetPlace,
            driveThrough = follow.driveThrough,
            detourTarget = detourEnd,
            simSpeed = simSpeed,
          )
      }

      is TripState.Complete -> {
        val planned = planTrip
        if (planned == null) {
          arriveFinal(destination)
        } else {
          synchronized(planLock) { planned.reachTarget() }
          if (planned.isComplete) arriveFinal(planned.plan.points.last().place) else pauseAtStop(planned)
        }
      }

      // Stopped from the notification's button.
      is TripState.Idle -> if (current is NavUiState.Navigating) stop()
    }
  }

  /** Shows the arrival message, then tears the trip down after a moment. */
  private fun arriveFinal(destination: Place) {
    mutableState.value = NavUiState.Arrived(destination)
    tripJob =
      scope.launch {
        delay(ARRIVAL_LINGER_MS)
        endTrip()
      }
  }

  /** Pauses the trip at the stop just reached, until the driver carries on. */
  private fun pauseAtStop(trip: PlanTrip) {
    pausingAtStop = true
    val plan = trip.plan
    val reachedIdx = trip.reachedIndex
    val nextIdx = trip.upcomingStop()
    if (nextIdx == null) {
      arriveFinal(plan.points.last().place)
      return
    }
    val stopsLeft = (reachedIdx + 1..plan.points.lastIndex).count { !plan.points[it].passThrough }
    mutableState.value =
      NavUiState.AtStop(
        reached = plan.points[reachedIdx].place,
        next = plan.points[nextIdx].place,
        stopsLeft = stopsLeft,
        isSimulating = tripSimulating,
        plan = plan,
        completedGeometry = trip.completedGeometry(),
        reachedIndex = reachedIdx,
        detourTarget = detourEnd,
        simSpeed = simSpeed,
      )
    // End the leg (keeping the voice for the next one). Launched so it doesn't re-enter this handler.
    scope.launch {
      core.stopNavigation()
      locations.disableSimulation()
    }
  }

  private fun atStopError(message: String) {
    mutableState.value = (mutableState.value as? NavUiState.AtStop)?.copy(error = message) ?: return
  }

  /**
   * Records the driver's progress along a planned route and returns what to draw: the join leg, the
   * stretch already done, which point was last reached, the stop being headed for, and a
   * drive-through just passed.
   */
  private fun followPlan(
    here: Position,
    accuracy: Double,
    source: List<GeographicCoordinate>,
    routeLine: List<Position>,
  ): PlanFollow =
    synchronized(planLock) {
      lastFix = here
      val trip = planTrip ?: return@synchronized PlanFollow()
      val plan = trip.plan
      val crossed = trip.advance(here, accuracy)
      if (crossed.isNotEmpty()) {
        driveThroughNotice = plan.points[crossed.max()].place to (System.currentTimeMillis() + DRIVE_THROUGH_MS)
      }
      if (source !== joinLegSource) {
        joinLegSource = source
        pendingJoinAt?.let {
          joinAt = it
          pendingJoinAt = null
        }
        joinLeg = joinAt?.let { trip.joinLeg(routeLine, it) }.orEmpty()
      }
      val notice = driveThroughNotice?.takeIf { it.second > System.currentTimeMillis() }?.first
      PlanFollow(
        joinLeg = joinLeg,
        completed = trip.completedGeometry(),
        reachedIndex = trip.reachedIndex,
        targetPlace = plan.points.getOrNull(trip.targetIndex)?.place,
        driveThrough = notice,
      )
    }

  /** At the end of a simulated detour, the simulated car goes back to following navigation. */
  private fun endSimulatedDetour(here: Position) {
    val end = detourEnd ?: return
    if (distanceMeters(here, end) > DETOUR_DONE_M) return
    detourEnd = null
    currentRoute?.let { locations.enableSimulationOn(it) }
  }

  private fun fail(destination: Place, message: String) {
    endTrip()
    mutableState.value = NavUiState.Failed(destination, message)
  }

  private fun endTrip() {
    core.stopNavigation() // also stops the foreground service and any speech in progress
    locations.disableSimulation()
    voice.shutdown()
    pausingAtStop = false
    tripSimulating = false
    synchronized(planLock) {
      planTrip = null
      lastFix = null
      joinAt = null
      pendingJoinAt = null
      joinLegSource = null
      joinLeg = emptyList()
      driveThroughNotice = null
    }
    tripPlan = null
    currentRoute = null
    detourEnd = null
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

  /** What [followPlan] works out for the map and the overlay. */
  private data class PlanFollow(
    val joinLeg: List<Position> = emptyList(),
    val completed: List<Position> = emptyList(),
    val reachedIndex: Int = -1,
    val targetPlace: Place? = null,
    val driveThrough: Place? = null,
  )

  private companion object {
    val ROUTE_URL = "${HerdServices.VALHALLA_BASE_URL}/route"
    const val DEFAULT_SIM_SPEED = 2
    const val MIN_SIM_SPEED = 1
    const val MAX_SIM_SPEED = 10
    const val FRESH_FIX_MS = 30_000L
    const val ARRIVAL_LINGER_MS = 5_000L
    /** How long a "passing through X" notice stays up. */
    const val DRIVE_THROUGH_MS = 5_000L
    /** A simulated detour is over once the car is this close to its end. */
    const val DETOUR_DONE_M = 25.0
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

private fun GeographicCoordinate.toPosition() = Position(longitude = lng, latitude = lat)

private fun Position.toUserLocation() = UserLocation(toCoordinate(), 10.0, null, Instant.now(), null)

/** A plan point or rejoin spot as Ferrostar's waypoint. Pass-through points don't allow a U-turn. */
private fun TripWaypoint.toFerrostar(): Waypoint =
  if (passThrough) {
    createWaypointWithValhallaProperties(
      position.toCoordinate(),
      WaypointKind.VIA,
      ValhallaWaypointProperties(allowUturns = false),
    )
  } else {
    Waypoint(position.toCoordinate(), WaypointKind.BREAK)
  }
