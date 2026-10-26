package app.herd.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.herd.android.BuildConfig
import app.herd.android.R
import app.herd.android.nav.NavUiState
import app.herd.shared.LocationProfiles
import app.herd.shared.MapDefaults
import app.herd.shared.planner.RouteStatus
import app.herd.shared.smoothingDuration
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.camera.CubicBezier
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.location.BearingUpdate
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.location.updateCamera
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.material3.LocationIndicatorLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import uniffi.ferrostar.UserLocation

/**
 * The map, the user's position, search and route preview, and turn-by-turn navigation.
 *
 * Browsing: the camera follows the user until they pan the map or a route appears; the button
 * brings it back. Location updates run only while this screen is visible (rememberLocationState is
 * lifecycle-aware), so GPS turns off when the app goes to the background.
 *
 * Navigating: Ferrostar's own location stream drives the trip and keeps running in the background.
 * The dot sits on the route, the map turns with the car and tilts, and panning pauses following.
 */
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
  val planner = viewModel.planner
  val navigator = viewModel.navigator
  val plan by planner.state.collectAsState()
  val nav by navigator.state.collectAsState()
  val navigating = nav as? NavUiState.Navigating
  val arrived = nav as? NavUiState.Arrived
  val isNavigating = navigating != null
  val inTrip = isNavigating || arrived != null
  val previewRoute = (plan.route as? RouteStatus.Ready)?.route

  val locationState =
    rememberLocationState(
      request = LocationProfiles.mapVisible,
      headingProvider = rememberDefaultHeadingProvider(),
    )

  // How long the dot (and the camera following it) glides to each new fix: see smoothingDuration.
  var browseSmoothing by remember { mutableStateOf(smoothingDuration(null)) }
  val carLocation = navigating?.trip?.snappedUserLocation
  val navSmoothing = rememberSmoothing(carLocation)

  // Geometry objects are remembered so the map sources only rebuild when the line changes.
  val lineGeometry = navigating?.routeGeometry ?: previewRoute?.geometry
  val routeLine = remember(lineGeometry) { lineGeometry?.let { LineString(it) } }
  val destination = navigating?.destination ?: arrived?.destination ?: plan.destination
  val destinationPoint = remember(destination?.position) { destination?.let { Point(it.position) } }
  val routeColor = MaterialTheme.colorScheme.primary
  val destinationColor = MaterialTheme.colorScheme.error

  val mapState =
    rememberMapState(
      baseStyle = BaseStyle.Uri(MapDefaults.STYLE_URL),
      initialCameraPosition =
        CameraPosition(center = MapDefaults.initialCenter, zoom = MapDefaults.INITIAL_ZOOM),
    ) {
      // Declared bottom to top: route, destination, then the user's dot over everything.
      if (routeLine != null) {
        val source = rememberGeoJsonSource(GeoJsonData.Features(routeLine))
        LineLayer(
          id = "route-casing",
          source = source,
          color = const(Color(0xFF1B3A5C)),
          width = const(9.dp),
          cap = const(LineCap.Round),
          join = const(LineJoin.Round),
        )
        LineLayer(
          id = "route",
          source = source,
          color = const(routeColor),
          width = const(6.dp),
          cap = const(LineCap.Round),
          join = const(LineJoin.Round),
        )
      }
      if (destinationPoint != null) {
        CircleLayer(
          id = "destination",
          source = rememberGeoJsonSource(GeoJsonData.Features(destinationPoint)),
          color = const(destinationColor),
          radius = const(8.dp),
          strokeColor = const(Color.White),
          strokeWidth = const(3.dp),
        )
      }
      if (carLocation != null) {
        // While navigating, the dot is the car's position snapped onto the route, pointing along it.
        LocationIndicatorLayer(
          id = "nav-location",
          location = carLocation.position,
          bearing = carLocation.courseOverGround?.let { Bearing.North + it.degrees.toInt().degrees },
          locationTransition = TransitionOptions(duration = navSmoothing),
        )
      } else {
        LocationIndicatorLayer(
          id = "user-location",
          locationState = locationState,
          locationTransition = TransitionOptions(duration = browseSmoothing),
        )
      }
    }

  var following by rememberSaveable { mutableStateOf(true) }
  val scope = rememberCoroutineScope()

  // Space taken by the panels over the map, so map controls and the camera stay clear of them.
  // Only the search field counts at the top: the suggestion list comes and goes.
  val density = LocalDensity.current
  var searchFieldHeight by remember { mutableStateOf(0.dp) }
  var routeCardHeight by remember { mutableStateOf(0.dp) }
  var navTopHeight by remember { mutableStateOf(0.dp) }
  var navBottomHeight by remember { mutableStateOf(0.dp) }
  var arrivedCardHeight by remember { mutableStateOf(0.dp) }
  val cardShown = plan.destination != null && !inTrip
  val topInset = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
  val screenHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
  val viewportInsets =
    when {
      // The car sits in the lower part of the screen so more of the road ahead shows. The cap
      // keeps it on screen while the full step list is open.
      isNavigating ->
        PaddingValues(
          top =
            maxOf(topInset + 16.dp + navTopHeight, screenHeight * NAV_CAR_FROM_TOP)
              .coerceAtMost(screenHeight * NAV_CAR_MAX_FROM_TOP),
          bottom = navBottomHeight,
        )
      arrived != null -> PaddingValues(top = topInset, bottom = arrivedCardHeight)
      else -> PaddingValues(top = topInset + 16.dp + searchFieldHeight + 8.dp, bottom = if (cardShown) routeCardHeight else 0.dp)
    }

  // Ask for location once on first launch. After that, the banner offers it again.
  var askedForPermission by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(locationState.permission) {
    val permission = locationState.permission
    if (!askedForPermission && permission is LocationPermission.NotGranted && permission.canRequest != false) {
      askedForPermission = true
      locationState.requestPermission()
    }
  }

  // Panning, zooming or rotating by hand stops following the user.
  LaunchedEffect(mapState) {
    snapshotFlow { mapState.isCameraMoving && mapState.cameraMoveReason == CameraMoveReason.Gesture }
      .collect { movedByUser -> if (movedByUser) following = false }
  }

  // The planner routes from, and biases search toward, the latest fix. It never reroutes on its own.
  LocationTrackingEffect(locationState, trackBearing = false) {
    browseSmoothing = smoothingDuration(previousLocation?.let { currentLocation.measuredAt - it.measuredAt })
    planner.updateOrigin(currentLocation.position)
  }

  LocationTrackingEffect(locationState, enabled = following && !isNavigating, trackBearing = false) {
    if (previousLocation == null) {
      // First fix: jump from the California overview down to street level.
      mapState.animateCamera(
        CameraUpdate(center = currentLocation.position, zoom = MapDefaults.USER_ZOOM)
      )
    } else {
      val gap = previousLocation?.let { currentLocation.measuredAt - it.measuredAt }
      updateCamera(mapState, animation = glide(smoothingDuration(gap)), updateBearing = BearingUpdate.Ignore)
    }
  }

  // Starting a trip follows the car; ending one levels the map and faces north again.
  var wasNavigating by rememberSaveable { mutableStateOf(isNavigating) }
  LaunchedEffect(isNavigating) {
    if (isNavigating == wasNavigating) return@LaunchedEffect
    wasNavigating = isNavigating
    following = true
    if (!isNavigating) mapState.animateCamera(CameraUpdate(bearing = 0.0, pitch = 0.0, zoom = MapDefaults.USER_ZOOM))
  }

  LaunchedEffect(carLocation, following) {
    val car = carLocation ?: return@LaunchedEffect
    if (!following) return@LaunchedEffect
    mapState.animateCamera(
      CameraUpdate(
        center = car.position,
        zoom = NAV_ZOOM,
        bearing = car.courseOverGround?.degrees?.toDouble(),
        pitch = NAV_PITCH,
      ),
      glide(navSmoothing),
    )
  }

  // Picking another destination (or none) cancels a trip that's still starting, or clears why it
  // failed to. Compared by position: a dropped pin's name filling in is the same destination.
  val pendingTrip = (nav as? NavUiState.Starting)?.destination ?: (nav as? NavUiState.Failed)?.destination
  LaunchedEffect(plan.destination?.position) {
    if (pendingTrip != null && pendingTrip.position != plan.destination?.position) navigator.stop()
  }

  // Show the whole route when it arrives.
  LaunchedEffect(previewRoute) {
    if (previewRoute == null || isNavigating) return@LaunchedEffect
    following = false
    mapState.animateCameraToBounds(
      previewRoute.bounds,
      CameraFit(fitPadding = DpPadding(left = 48.dp, top = 48.dp, right = 48.dp, bottom = 48.dp)),
    )
  }

  // Back during a trip leaves it running, like going home, instead of closing the app and ending it.
  val activity = LocalActivity.current
  BackHandler(enabled = isNavigating) { activity?.moveTaskToBack(true) }

  // The navigation notification needs permission on Android 13+. Navigation runs either way.
  val context = LocalContext.current
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  fun startTrip(simulate: Boolean) {
    val target = plan.destination ?: return
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    navigator.start(target, plan.options, fallbackOrigin = locationState.lastLocation?.position, simulate = simulate)
  }

  val interactions =
    remember(planner, inTrip) {
      MapInteractions {
        callbacks {
          longClick {
            onEvent { event ->
              // A long-press mid-trip shouldn't silently replace the destination.
              if (!inTrip) event.position?.let(planner::pickPoint)
              ClickResult.Consume
            }
          }
        }
      }
    }

  Box(Modifier.fillMaxSize()) {
    MaplibreMap(
      modifier = Modifier.fillMaxSize(),
      state = mapState,
      viewportInsets = viewportInsets,
      interactions = interactions,
    )

    when {
      navigating != null -> NavigationOverlay(
        state = navigating,
        following = following,
        onRecenter = { following = true },
        onMuteChange = navigator::setMuted,
        onStop = navigator::stop,
        topModifier = Modifier.onSizeChanged { navTopHeight = with(density) { it.height.toDp() } },
        bottomModifier = Modifier.onSizeChanged { navBottomHeight = with(density) { it.height.toDp() } },
      )

      arrived != null -> ArrivedCard(
        destination = arrived.destination,
        onDone = {
          navigator.stop()
          planner.clear()
        },
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .onSizeChanged { arrivedCardHeight = with(density) { it.height.toDp() } }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp)
            .fillMaxWidth(),
      )

      else -> {
        Column(
          Modifier.align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(16.dp)
            .fillMaxWidth()
        ) {
          SearchBar(
            state = plan,
            onQueryChange = planner::onQueryChanged,
            onSubmit = planner::submitQuery,
            onSelect = planner::selectPlace,
            onClear = planner::clear,
            onRemoveRecent = planner::removeRecent,
            fieldModifier = Modifier.onSizeChanged { searchFieldHeight = with(density) { it.height.toDp() } },
          )
          LocationBanner(locationState = locationState, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
        }

        if (cardShown) {
          val starting = nav as? NavUiState.Starting
          val failed = nav as? NavUiState.Failed
          RouteCard(
            state = plan,
            onOptionsChange = planner::setOptions,
            onRetry = planner::retry,
            onClose = {
              navigator.stop()
              planner.clear()
            },
            onStart = { startTrip(simulate = false) },
            onSimulate = if (BuildConfig.DEBUG) ({ startTrip(simulate = true) }) else null,
            isStarting = starting != null,
            startError = failed?.message,
            modifier =
              Modifier.align(Alignment.BottomCenter)
                .onSizeChanged { routeCardHeight = with(density) { it.height.toDp() } }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp)
                .fillMaxWidth(),
          )
        }

        FloatingActionButton(
          onClick = {
            if (locationState.permission !is LocationPermission.Granted) {
              locationState.requestPermission()
              return@FloatingActionButton
            }
            following = true
            val location = locationState.lastLocation ?: return@FloatingActionButton
            scope.launch {
              mapState.animateCamera(
                CameraUpdate(
                  center = location.position,
                  zoom = maxOf(mapState.cameraPosition.zoom, MapDefaults.USER_ZOOM),
                )
              )
            }
          },
          containerColor =
            if (following) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
          modifier =
            Modifier.align(Alignment.BottomEnd)
              .then(
                // Above the route card when it shows (its height includes the navigation bar),
                // otherwise clear of the attribution button.
                if (cardShown) Modifier.padding(end = 16.dp, bottom = routeCardHeight)
                else Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(end = 16.dp, bottom = 56.dp)
              ),
        ) {
          Icon(
            painter = painterResource(R.drawable.ic_my_location),
            contentDescription = stringResource(R.string.recenter),
          )
        }
      }
    }
  }
}

private const val NAV_ZOOM = 16.5
private const val NAV_PITCH = 45.0
/** While navigating, the car is drawn at least this far down the screen, and at most this far. */
private const val NAV_CAR_FROM_TOP = 0.45f
private const val NAV_CAR_MAX_FROM_TOP = 0.6f

/** A steady camera move that ends as the next fix arrives, so following never stops and starts. */
private fun glide(duration: Duration) =
  CameraAnimation.Ease {
    this.duration = duration
    easing = CubicBezier.Linear
  }

/**
 * The glide time for navigation fixes, from when each one arrives. (Browsing uses the fixes' own
 * timestamps instead; simulated drives don't have meaningful ones.)
 */
@Composable
private fun rememberSmoothing(fix: Any?): Duration {
  val clock = remember { FixClock() }
  return remember(fix) { smoothingDuration(clock.sinceLastFix()) }
}

private class FixClock {
  private var lastFix: TimeSource.Monotonic.ValueTimeMark? = null

  fun sinceLastFix(): Duration? {
    val now = TimeSource.Monotonic.markNow()
    return lastFix?.let { now - it }.also { lastFix = now }
  }
}

private val UserLocation.position: Position
  get() = Position(longitude = coordinates.lng, latitude = coordinates.lat)
