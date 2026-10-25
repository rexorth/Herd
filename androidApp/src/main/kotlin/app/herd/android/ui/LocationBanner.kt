package app.herd.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.herd.android.R
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.rememberSystemSettingsLauncher

/** Explains why the location dot is missing and offers the fix. Shows nothing when location works. */
@Composable
fun LocationBanner(locationState: LocationState, modifier: Modifier = Modifier) {
  val settings = rememberSystemSettingsLauncher()
  val permission = locationState.permission
  val status = locationState.status

  when {
    permission is LocationPermission.NotGranted ->
      Banner(
        title = stringResource(R.string.location_off_title),
        body = stringResource(R.string.location_off_body),
        action =
          if (permission.canRequest == false) stringResource(R.string.location_open_settings)
          else stringResource(R.string.location_allow),
        onAction = {
          // After "Don't allow" twice, Android stops showing the prompt; only settings can change it.
          if (permission.canRequest == false) settings.openApplicationSettings()
          else locationState.requestPermission()
        },
        modifier = modifier,
      )

    status is LocationTrackingStatus.Unavailable &&
      status.reason == LocationUnavailableReason.ServicesDisabled ->
      Banner(
        title = stringResource(R.string.location_services_off),
        body = null,
        action = stringResource(R.string.location_services_enable),
        onAction = { settings.openLocationServicesSettings() },
        modifier = modifier,
      )
  }
}

@Composable
private fun Banner(
  title: String,
  body: String?,
  action: String,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(modifier) {
    Column(
      Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
      TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action) }
    }
  }
}
