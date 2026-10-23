package app.herd.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.herd.android.R
import app.herd.shared.LocationProfiles
import app.herd.shared.MapDefaults
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.location.BearingUpdate
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.location.updateCamera
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.material3.LocationIndicatorLayer
import org.maplibre.compose.style.BaseStyle

/**
 * v0.1: the map and the user's position on it.
 *
 * The camera follows the user until they pan the map; the button brings it back. Location updates
 * run only while this screen is visible (rememberLocationState is lifecycle-aware), so GPS turns
 * off when the app goes to the background.
 */
@Composable
fun MapScreen() {
  val locationState =
    rememberLocationState(
      request = LocationProfiles.mapVisible,
      headingProvider = rememberDefaultHeadingProvider(),
    )

  val mapState =
    rememberMapState(
      baseStyle = BaseStyle.Uri(MapDefaults.STYLE_URL),
      initialCameraPosition =
        CameraPosition(center = MapDefaults.initialCenter, zoom = MapDefaults.INITIAL_ZOOM),
    ) {
      LocationIndicatorLayer(id = "user-location", locationState = locationState)
    }

  var following by rememberSaveable { mutableStateOf(true) }
  val scope = rememberCoroutineScope()

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

  Box(Modifier.fillMaxSize()) {
    MaplibreMap(modifier = Modifier.fillMaxSize(), state = mapState)

    LocationBanner(
      locationState = locationState,
      modifier =
        Modifier.align(Alignment.TopCenter)
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(16.dp)
          .fillMaxWidth(),
    )

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
          .windowInsetsPadding(WindowInsets.safeDrawing)
          // Clear of the attribution button in the map's bottom-right corner.
          .padding(end = 16.dp, bottom = 56.dp),
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_my_location),
        contentDescription = stringResource(R.string.recenter),
      )
    }
  }
}
