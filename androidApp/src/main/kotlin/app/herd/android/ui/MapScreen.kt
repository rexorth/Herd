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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import app.herd.shared.isStationaryWander
import app.herd.shared.geo.distanceMeters
import app.herd.shared.plan.JoinMode
import app.herd.shared.plan.PlanPoint
import app.herd.shared.plan.PlannedRoute
import app.herd.shared.planner.RouteStatus
import app.herd.shared.routing.Route
import com.stadiamaps.ferrostar.core.extensions.preferredUserLocation
import kotlin.time.Duration
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
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.mostAccurateBearing
import org.maplibre.compose.location.mostAccurateBearingAccuracy
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.material3.LocationIndicatorLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inMeters
import uniffi.ferrostar.UserLocation

/**
 * The map, the user's position, search and route preview, planned routes, and turn-by-turn
 * navigation.
 *
 * Browsing: the camera follows the user until they pan the map or a route appears; the button
 * brings it back. Location updates run only while this screen is visible (rememberLocationState is
 * lifecycle-aware), so GPS turns off when the app goes to the background.
 *
 * Planning: the plan editor replaces the route card. Search adds stops, a long-press adds a
 * pass-through point, and the map shows the plan's route and points.
 *
 * Navigating: Ferrostar's own location stream drives the trip and keeps running in the background.
 * The dot sits on the route, the map turns with the car and tilts, and panning pauses following.
 * On a plan, the plan stays drawn underneath and the stretch that gets onto it is orange.
 *
 * Either way the dot glides from fix to fix one frame at a time ([Glide]), and a following camera
 * is placed on it every frame, so the two move together.
 */
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
  val planner = viewModel.planner
  val planEditor = viewModel.planEditor
  val navigator = viewModel.navigator
  val plan by planner.state.collectAsState()
  val editor by planEditor.state.collectAsState()
  val savedRoutes by planEditor.saved.collectAsState(initial = emptyList())
  val nav by navigator.state.collectAsState()
  val navigating = nav as? NavUiState.Navigating
  val arrived = nav as? NavUiState.Arrived
  val isNavigating = navigating != null
  val inTrip = isNavigating || arrived != null
  val previewRoute = (plan.route as? RouteStatus.Ready)?.route
  val editorRoute = (editor?.route as? RouteStatus.Ready)?.route
  // While an edited plan reroutes, its previous route stays drawn rather than blinking off.
  var settledPlanRoute by remember { mutableStateOf<Route?>(null) }
  LaunchedEffect(editorRoute, editor == null) {
    if (editorRoute != null || editor == null) settledPlanRoute = editorRoute
  }
  val planRouteShown = editorRoute ?: settledPlanRoute.takeIf { editor?.route == RouteStatus.Loading }

  val locationState =
    rememberLocationState(
      request = LocationProfiles.mapVisible,
      headingProvider = rememberDefaultHeadingProvider(),
    )

  // Where the dot is headed. While navigating: the car snapped onto the route, or its real GPS
  // position once it leaves the route (snapped, it would sit on the old line until a new route
  // arrives, however long that takes). Otherwise: the latest GPS fix that's a real move (see the
  // LocationTrackingEffect below), with the direction from the compass (see LocationDot).
  var browseTarget by remember { mutableStateOf<Pose?>(null) }
  val dotTarget =
    navigating?.trip?.preferredUserLocation()?.let { Pose(it.position, it.courseOverGround?.degrees?.toDouble()) }
      ?: browseTarget
  val glide = remember { Glide() }

  // Geometry objects are remembered so the map sources only rebuild when the line changes.
  val lineGeometry = navigating?.routeGeometry ?: planRouteShown?.geometry ?: previewRoute?.geometry
  val routeLine = remember(lineGeometry) { lineGeometry?.let { LineString(it) } }
  val planGeometry = navigating?.plan?.route?.geometry
  val planLine = remember(planGeometry) { planGeometry?.let { LineString(it) } }
  val joinGeometry = navigating?.joinLeg
  val joinLine = remember(joinGeometry) { joinGeometry?.takeIf { it.size >= 2 }?.let { LineString(it) } }
  val planPoints = navigating?.plan?.points ?: editor?.points
  val planMarkers = remember(planPoints) { planPoints?.let(::PlanMarkers) }
  val destination =
    navigating?.destination ?: arrived?.destination ?: editor?.points?.lastOrNull()?.place ?: plan.destination
  val destinationPoint = remember(destination?.position) { destination?.let { Point(it.position) } }
  val routeColor = MaterialTheme.colorScheme.primary
  val destinationColor = MaterialTheme.colorScheme.error

  val mapState =
    rememberMapState(
      baseStyle = BaseStyle.Uri(MapDefaults.STYLE_URL),
      initialCameraPosition =
        CameraPosition(center = MapDefaults.initialCenter, zoom = MapDefaults.INITIAL_ZOOM),
    ) {
      // Declared bottom to top: the plan under a trip on it, the route, the leg that joins the plan,
      // the plan's points, the destination, then the user's dot over everything.
      if (planLine != null) {
        LineLayer(
          id = "plan",
          source = rememberGeoJsonSource(GeoJsonData.Features(planLine)),
          color = const(routeColor),
          opacity = const(PLAN_UNDER_TRIP_OPACITY),
          width = const(6.dp),
          cap = const(LineCap.Round),
          join = const(LineJoin.Round),
        )
      }
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
      if (joinLine != null) {
        LineLayer(
          id = "join-leg",
          source = rememberGeoJsonSource(GeoJsonData.Features(joinLine)),
          color = const(PlanColors.joinLeg),
          width = const(6.dp),
          cap = const(LineCap.Round),
          join = const(LineJoin.Round),
        )
      }
      planMarkers?.passThrough?.let {
        CircleLayer(
          id = "plan-pass-through",
          source = rememberGeoJsonSource(GeoJsonData.Features(it)),
          color = const(Color.White),
          radius = const(5.dp),
          strokeColor = const(routeColor),
          strokeWidth = const(2.dp),
        )
      }
      planMarkers?.stops?.let {
        CircleLayer(
          id = "plan-stops",
          source = rememberGeoJsonSource(GeoJsonData.Features(it)),
          color = const(routeColor),
          radius = const(7.dp),
          strokeColor = const(Color.White),
          strokeWidth = const(3.dp),
        )
      }
      planMarkers?.start?.let {
        CircleLayer(
          id = "plan-start",
          source = rememberGeoJsonSource(GeoJsonData.Features(it)),
          color = const(PlanColors.start),
          radius = const(7.dp),
          strokeColor = const(Color.White),
          strokeWidth = const(3.dp),
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
      LocationDot(glide, locationState, onRoute = isNavigating)
    }

  var following by rememberSaveable { mutableStateOf(true) }
  val scope = rememberCoroutineScope()

  // Space taken by the panels over the map, so map controls and the camera stay clear of them.
  // Only the search field counts at the top: the suggestion list comes and goes.
  val density = LocalDensity.current
  var searchFieldHeight by remember { mutableStateOf(0.dp) }
  var bottomPanelHeight by remember { mutableStateOf(0.dp) }
  var navTopHeight by remember { mutableStateOf(0.dp) }
  var navBottomHeight by remember { mutableStateOf(0.dp) }
  var arrivedCardHeight by remember { mutableStateOf(0.dp) }
  val editorShown = editor != null && !inTrip
  val cardShown = plan.destination != null && !inTrip && !editorShown
  val panelShown = editorShown || cardShown
  val topInset = with(density) { BarsAndCutout.getTop(this).toDp() }
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
      else -> PaddingValues(top = topInset + 16.dp + searchFieldHeight + 8.dp, bottom = if (panelShown) bottomPanelHeight else 0.dp)
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
  // The dot only moves for real movement: GPS wander around a phone that's sitting still would
  // wobble it and keep the map redrawing (and the keyboard stuttering) for nothing.
  LocationTrackingEffect(locationState, trackBearing = false) {
    planner.updateOrigin(currentLocation.position)
    val shown = browseTarget?.position
    val wander =
      shown != null &&
        isStationaryWander(
          shown,
          currentLocation.position,
          accuracyMeters = currentLocation.horizontalAccuracy?.inMeters,
          speedMetersPerSecond = currentLocation.distancePerSecond?.inMeters,
        )
    if (!wander) browseTarget = Pose(currentLocation.position, bearing = null)
  }

  // While a programmatic camera move (zooming in, recentering) plays, following waits for it.
  var cameraBusy by remember { mutableStateOf(false) }
  suspend fun moveCamera(update: CameraUpdate) {
    cameraBusy = true
    try {
      mapState.animateCamera(update)
    } finally {
      cameraBusy = false
    }
  }

  // A camera move from following takes the camera away from any gesture that has started, so
  // following holds off while a finger is on the map (a pan or pinch may be starting) and stops
  // once a gesture moves the camera.
  var touchingMap by remember { mutableStateOf(false) }

  // Glide the dot to each new fix, and keep a following camera on it every frame: centered, and
  // turned with the car while navigating. Between GPS and the route (a trip starting or ending)
  // the dot jumps instead.
  val navigatingNow by rememberUpdatedState(isNavigating)
  var dotOnRoute by remember { mutableStateOf(isNavigating) }
  LaunchedEffect(dotTarget) {
    val target = dotTarget ?: return@LaunchedEffect
    if (dotOnRoute != isNavigating) {
      dotOnRoute = isNavigating
      glide.reset()
    }
    glide.to(target) { pose ->
      if (mapState.isCameraMoving && mapState.cameraMoveReason == CameraMoveReason.Gesture) following = false
      if (!following || cameraBusy || touchingMap) return@to
      val camera = mapState.cameraPosition
      val bearing = if (navigatingNow) pose.bearing ?: camera.bearing else camera.bearing
      mapState.setCameraPosition(camera.copy(center = pose.position, bearing = bearing))
    }
  }

  // First fix: zoom from the California overview down to street level.
  var zoomedToUser by rememberSaveable { mutableStateOf(false) }
  val hasFix = locationState.lastLocation != null
  LaunchedEffect(hasFix) {
    val fix = locationState.lastLocation ?: return@LaunchedEffect
    if (zoomedToUser || !following || isNavigating) return@LaunchedEffect
    zoomedToUser = true
    moveCamera(CameraUpdate(center = fix.position, zoom = MapDefaults.USER_ZOOM))
  }

  // Starting a trip tilts the map behind the car; ending one levels it and faces north again.
  fun navigationView(pose: Pose?) =
    CameraUpdate(center = pose?.position, zoom = NAV_ZOOM, bearing = pose?.bearing, pitch = NAV_PITCH)
  var wasNavigating by rememberSaveable { mutableStateOf(isNavigating) }
  LaunchedEffect(isNavigating) {
    if (isNavigating == wasNavigating) return@LaunchedEffect
    wasNavigating = isNavigating
    following = true
    if (isNavigating) moveCamera(navigationView(dotTarget))
    else moveCamera(CameraUpdate(center = dotTarget?.position, bearing = 0.0, pitch = 0.0, zoom = MapDefaults.USER_ZOOM))
  }

  // Picking another destination (or none) cancels a trip that's still starting, or clears why it
  // failed to. Compared by position: a dropped pin's name filling in is the same destination. A
  // plan's trip has no destination here; closing the plan does the same for it.
  val pendingTrip = (nav as? NavUiState.Starting)?.destination ?: (nav as? NavUiState.Failed)?.destination
  LaunchedEffect(plan.destination?.position) {
    if (editor == null && pendingTrip != null && pendingTrip.position != plan.destination?.position) navigator.stop()
  }

  // Show the whole route when it arrives.
  suspend fun showWhole(route: Route) {
    following = false
    mapState.animateCameraToBounds(
      route.bounds,
      CameraFit(fitPadding = DpPadding(left = 48.dp, top = 48.dp, right = 48.dp, bottom = 48.dp)),
    )
  }
  LaunchedEffect(previewRoute) {
    if (previewRoute == null || isNavigating) return@LaunchedEffect
    showWhole(previewRoute)
  }
  // A plan's route too, when the plan opens or its stops change. Not for pass-through points: they
  // go where the user pressed, on the part of the map they're looking at.
  var fittedStops by remember { mutableStateOf<List<Position>?>(null) }
  LaunchedEffect(editor == null) { if (editor == null) fittedStops = null }
  LaunchedEffect(editorRoute) {
    val stops = editor?.points?.filter { !it.passThrough }?.map { it.place.position }
    if (editorRoute == null || inTrip || stops == fittedStops) return@LaunchedEffect
    fittedStops = stops
    showWhole(editorRoute)
  }

  // Closing a plan with changes worth keeping asks first.
  var confirmingDiscard by remember { mutableStateOf(false) }
  fun closeEditor(force: Boolean = false) {
    val state = editor ?: return
    if (!force && state.hasUnsavedChanges && (state.id != null || state.points.size > 2)) {
      confirmingDiscard = true
      return
    }
    if (pendingTrip != null) navigator.stop()
    planEditor.close()
  }

  // Back during a trip leaves it running, like going home, instead of closing the app and ending it.
  val activity = LocalActivity.current
  BackHandler(enabled = isNavigating) { activity?.moveTaskToBack(true) }
  BackHandler(enabled = editorShown) { closeEditor() }

  // The navigation notification needs permission on Android 13+. Navigation runs either way.
  val context = LocalContext.current
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  fun askForNotifications() {
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
  fun startTrip(simulate: Boolean) {
    val target = plan.destination ?: return
    askForNotifications()
    navigator.start(target, plan.options, fallbackOrigin = locationState.lastLocation?.position, simulate = simulate)
  }

  // Starting a plan away from its first point asks whether to drive there or join where it's closest.
  var joinPrompt by remember { mutableStateOf<JoinPrompt?>(null) }
  fun startPlan(trip: PlannedRoute, simulate: Boolean, mode: JoinMode?) {
    val here = locationState.lastLocation?.position
    val away = here?.let { distanceMeters(it, trip.points.first().place.position) }
    if (mode == null && away != null && away > JOIN_PROMPT_M) {
      joinPrompt = JoinPrompt(trip, simulate, away)
      return
    }
    askForNotifications()
    navigator.startPlan(trip, mode ?: JoinMode.FromStart, fallbackOrigin = here, simulate = simulate)
  }

  var showSaved by rememberSaveable { mutableStateOf(false) }

  // Mid-trip a long-press shouldn't silently change where the trip goes. Simulating (debug builds),
  // it sends the simulated car off route there, to try rerouting at a desk.
  val onLongPress by rememberUpdatedState { position: Position ->
    when {
      navigating?.isSimulating == true -> navigator.simulateDetour(position)
      inTrip -> Unit
      editor != null -> planEditor.addPassThrough(position)
      else -> planner.pickPoint(position)
    }
  }
  val interactions = remember {
    MapInteractions {
      callbacks {
        longClick {
          onEvent { event ->
            event.position?.let(onLongPress)
            ClickResult.Consume
          }
        }
      }
    }
  }

  Box(Modifier.fillMaxSize()) {
    MaplibreMap(
      modifier =
        Modifier.fillMaxSize().pointerInput(Unit) {
          // Watches without consuming, ahead of the map's own gesture handling.
          awaitPointerEventScope {
            while (true) {
              touchingMap = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
            }
          }
        },
      state = mapState,
      viewportInsets = viewportInsets,
      interactions = interactions,
    )

    when {
      navigating != null -> NavigationOverlay(
        state = navigating,
        following = following,
        onRecenter = {
          following = true
          scope.launch { moveCamera(navigationView(glide.current)) }
        },
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
          // A plan driven to the end closes too, unless it has changes to save.
          if (editor?.hasUnsavedChanges == false) planEditor.close()
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
            .windowInsetsPadding(BarsAndCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
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
            hint = stringResource(if (editorShown) R.string.plan_search_hint else R.string.search_hint),
            onShowSaved = if (editorShown) null else ({ showSaved = true }),
            fieldModifier = Modifier.onSizeChanged { searchFieldHeight = with(density) { it.height.toDp() } },
            pickKey = editor?.points?.size,
          )
          LocationBanner(locationState = locationState, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
        }

        val starting = nav as? NavUiState.Starting
        val failed = nav as? NavUiState.Failed
        val panelModifier =
          Modifier.align(Alignment.BottomCenter)
            .onSizeChanged { bottomPanelHeight = with(density) { it.height.toDp() } }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp)
            .fillMaxWidth()
        if (editorShown) {
          val state = editor ?: return@Box
          PlanEditorPanel(
            state = state,
            onRename = planEditor::rename,
            onMove = planEditor::move,
            onTogglePassThrough = planEditor::togglePassThrough,
            onRemove = planEditor::remove,
            onOptionsChange = planEditor::setOptions,
            onRetry = planEditor::retry,
            onSave = planEditor::save,
            onDelete = planEditor::delete,
            onClose = { closeEditor() },
            onStart = { state.plan?.let { startPlan(it, simulate = false, mode = null) } },
            onSimulate = if (BuildConfig.DEBUG) ({ state.plan?.let { startPlan(it, simulate = true, mode = null) } }) else null,
            isStarting = starting != null,
            startError = failed?.message,
            modifier = panelModifier,
          )
        }
        if (cardShown) {
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
            onAddStop = {
              val to = plan.destination
              val from = planner.origin
              if (to != null && from != null) {
                planEditor.startNew(from, to, plan.options)
                planner.clear()
              }
            },
            isStarting = starting != null,
            startError = failed?.message,
            modifier = panelModifier,
          )
        }

        FloatingActionButton(
          onClick = {
            if (locationState.permission !is LocationPermission.Granted) {
              locationState.requestPermission()
              return@FloatingActionButton
            }
            following = true
            val dot = glide.current ?: return@FloatingActionButton
            scope.launch {
              moveCamera(
                CameraUpdate(center = dot.position, zoom = maxOf(mapState.cameraPosition.zoom, MapDefaults.USER_ZOOM))
              )
            }
          },
          containerColor =
            if (following) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
          modifier =
            Modifier.align(Alignment.BottomEnd)
              .then(
                // Above the route card or plan when one shows (its height includes the navigation
                // bar), otherwise clear of the attribution button.
                if (panelShown) Modifier.padding(end = 16.dp, bottom = bottomPanelHeight)
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

  if (showSaved) {
    SavedRoutesSheet(
      routes = savedRoutes,
      onOpen = { id ->
        showSaved = false
        navigator.stop()
        planner.clear()
        planEditor.open(id)
      },
      onDismiss = { showSaved = false },
    )
  }
  joinPrompt?.let { prompt ->
    JoinPlanDialog(
      distanceMeters = prompt.distanceMeters,
      onFromStart = {
        joinPrompt = null
        startPlan(prompt.plan, prompt.simulate, JoinMode.FromStart)
      },
      onNearest = {
        joinPrompt = null
        startPlan(prompt.plan, prompt.simulate, JoinMode.Nearest)
      },
      onDismiss = { joinPrompt = null },
    )
  }
  if (confirmingDiscard) {
    DiscardPlanDialog(
      onDiscard = {
        confirmingDiscard = false
        closeEditor(force = true)
      },
      onDismiss = { confirmingDiscard = false },
    )
  }
}

/** A plan's start, its stops along the way, and its pass-through points, ready to draw. */
private class PlanMarkers(points: List<PlanPoint>) {
  val start: Point? = points.takeIf { it.size >= 2 }?.let { Point(it.first().place.position) }
  private val middle = points.drop(1).dropLast(1)
  val stops: MultiPoint? = middle.filter { !it.passThrough }.toMultiPoint()
  val passThrough: MultiPoint? = middle.filter { it.passThrough }.toMultiPoint()

  private fun List<PlanPoint>.toMultiPoint() = takeIf { it.isNotEmpty() }?.let { MultiPoint(it.map { p -> p.place.position }) }
}

/** Starting [plan] [distanceMeters] from its first point, waiting on the user's choice. */
private data class JoinPrompt(val plan: PlannedRoute, val simulate: Boolean, val distanceMeters: Double)

/**
 * The status and navigation bars and any display cutout: what panels at the screen edges stay
 * clear of. Unlike `WindowInsets.safeDrawing` it leaves out the keyboard, which only ever covers
 * the map. Reading keyboard insets would re-lay out the screen on every frame of the keyboard's
 * slide-in, and the keyboard animates in step with those frames.
 */
internal val BarsAndCutout: WindowInsets
  @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** Starting a plan further than this from its first point asks how to join it. */
private const val JOIN_PROMPT_M = 500.0
/** A plan under a trip on it shows faintly, where the trip's route leaves it. */
private const val PLAN_UNDER_TRIP_OPACITY = 0.45f
private const val NAV_ZOOM = 16.5
private const val NAV_PITCH = 45.0
/** While navigating, the car is drawn at least this far down the screen, and at most this far. */
private const val NAV_CAR_FROM_TOP = 0.45f
private const val NAV_CAR_MAX_FROM_TOP = 0.6f


/**
 * The user's dot, drawn where [glide] has it this frame. Its own composable so only it, not the
 * whole map, recomposes on every frame. MapLibre's transitions are off for the position, since the
 * glide already animates it.
 *
 * While navigating ([onRoute]) it points the way the car is going. Otherwise it points where the phone faces
 * (or moves), with an accuracy circle.
 */
@Composable
@MaplibreComposable
private fun LocationDot(glide: Glide, locationState: LocationState, onRoute: Boolean) {
  val pose = glide.current ?: return
  val noTransition = TransitionOptions(duration = Duration.ZERO)
  if (onRoute) {
    LocationIndicatorLayer(
      id = "nav-location",
      location = pose.position,
      bearing = pose.bearing?.let { Bearing.North + it.degrees },
      locationTransition = noTransition,
    )
  } else {
    LocationIndicatorLayer(
      id = "user-location",
      location = pose.position,
      bearing = locationState.mostAccurateBearing(),
      bearingAccuracy = locationState.mostAccurateBearingAccuracy(),
      accuracyRadius = locationState.lastLocation?.horizontalAccuracy,
      locationTransition = noTransition,
      // The compass turns the dot many times a second; a short transition keeps that smooth.
      bearingTransition = TransitionOptions(),
      accuracyRadiusTransition = TransitionOptions(),
    )
  }
}

private val UserLocation.position: Position
  get() = Position(longitude = coordinates.lng, latitude = coordinates.lat)
