package app.herd.android.ui

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.herd.android.R
import app.herd.shared.LocationProfiles
import app.herd.shared.MapDefaults
import app.herd.shared.planner.RouteStatus
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
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
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point

/**
 * The map, the user's position, and v0.2's search and route preview.
 *
 * The camera follows the user until they pan the map or a route appears; the button brings it back.
 * Location updates run only while this screen is visible (rememberLocationState is
 * lifecycle-aware), so GPS turns off when the app goes to the background.
 */
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
  val planner = viewModel.planner
  val plan by planner.state.collectAsState()
  val route = (plan.route as? RouteStatus.Ready)?.route

  val locationState =
    rememberLocationState(
      request = LocationProfiles.mapVisible,
      headingProvider = rememberDefaultHeadingProvider(),
    )

  // Geometry objects are remembered so the map sources only rebuild when the route changes.
  val routeLine = remember(route) { route?.let { LineString(it.geometry) } }
  val destinationPoint = remember(plan.destination) { plan.destination?.let { Point(it.position) } }
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
      LocationIndicatorLayer(id = "user-location", locationState = locationState)
    }

  var following by rememberSaveable { mutableStateOf(true) }
  val scope = rememberCoroutineScope()

  // Space taken by the search field and the route card, so map controls and camera fits stay
  // clear of them. Only the field counts at the top: the suggestion list comes and goes.
  val density = LocalDensity.current
  var searchFieldHeight by remember { mutableStateOf(0.dp) }
  var routeCardHeight by remember { mutableStateOf(0.dp) }
  val cardShown = plan.destination != null
  val topInset = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
  val topOverlay = topInset + 16.dp + searchFieldHeight + 8.dp
  val bottomOverlay = if (cardShown) routeCardHeight else 0.dp

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
    planner.updateOrigin(currentLocation.position)
  }

  LocationTrackingEffect(locationState, enabled = following, trackBearing = false) {
    if (previousLocation == null) {
      // First fix: jump from the California overview down to street level.
      mapState.animateCamera(
        CameraUpdate(center = currentLocation.position, zoom = MapDefaults.USER_ZOOM)
      )
    } else {
      updateCamera(mapState, updateBearing = BearingUpdate.Ignore)
    }
  }

  // Show the whole route when it arrives.
  LaunchedEffect(route) {
    if (route == null) return@LaunchedEffect
    following = false
    mapState.animateCameraToBounds(
      route.bounds,
      CameraFit(fitPadding = DpPadding(left = 48.dp, top = 48.dp, right = 48.dp, bottom = 48.dp)),
    )
  }

  val interactions =
    remember(planner) {
      MapInteractions {
        callbacks {
          longClick {
            onEvent { event ->
              event.position?.let(planner::pickPoint)
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
      viewportInsets = PaddingValues(top = topOverlay, bottom = bottomOverlay),
      interactions = interactions,
    )

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
        fieldModifier = Modifier.onSizeChanged { searchFieldHeight = with(density) { it.height.toDp() } },
      )
      LocationBanner(locationState = locationState, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
    }

    if (cardShown) {
      RouteCard(
        state = plan,
        onOptionsChange = planner::setOptions,
        onRetry = planner::retry,
        onClose = planner::clear,
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
