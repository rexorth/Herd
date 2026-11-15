package app.herd.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.herd.android.R
import app.herd.shared.format.formatDistance
import app.herd.shared.format.formatDuration
import app.herd.shared.planner.PlannerState
import app.herd.shared.planner.RouteStatus
import app.herd.shared.routing.RouteOptions

/**
 * The picked destination, the route's distance and time, the route preferences, Start, and Add
 * stop (which turns the route into a plan).
 *
 * [onSimulate] is null except in debug builds. [startError] is why the last Start failed.
 */
@Composable
fun RouteCard(
  state: PlannerState,
  onOptionsChange: (RouteOptions) -> Unit,
  onRetry: () -> Unit,
  onClose: () -> Unit,
  onStart: () -> Unit,
  onSimulate: (() -> Unit)?,
  onAddStop: () -> Unit,
  isStarting: Boolean,
  startError: String?,
  modifier: Modifier = Modifier,
) {
  val destination = state.destination ?: return
  Card(modifier) {
    Column(
      Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
          Text(
            destination.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          placeContext(destination)?.let {
            Text(
              it,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        IconButton(onClick = onClose) {
          Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.route_close))
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { RouteSummary(state.route, onRetry) }
        if (state.route is RouteStatus.Ready) {
          TextButton(onClick = onAddStop) {
            Icon(painterResource(R.drawable.ic_add_location), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.route_add_stop))
          }
        }
      }

      RouteOptionChips(state.options, onOptionsChange)

      startError?.let { StartError(it) }
      StartButtons(
        onStart = onStart,
        onSimulate = onSimulate,
        enabled = state.route is RouteStatus.Ready,
        isStarting = isStarting,
      )
    }
  }
}

@Composable
internal fun RouteOptionChips(options: RouteOptions, onOptionsChange: (RouteOptions) -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterChip(
      selected = options.avoidHighways,
      onClick = { onOptionsChange(options.copy(avoidHighways = !options.avoidHighways)) },
      label = { Text(stringResource(R.string.avoid_highways)) },
    )
    FilterChip(
      selected = options.avoidTolls,
      onClick = { onOptionsChange(options.copy(avoidTolls = !options.avoidTolls)) },
      label = { Text(stringResource(R.string.avoid_tolls)) },
    )
  }
}

@Composable
internal fun StartError(message: String) {
  Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/** Start, and Simulate in debug builds ([onSimulate] is null otherwise). */
@Composable
internal fun StartButtons(onStart: () -> Unit, onSimulate: (() -> Unit)?, enabled: Boolean, isStarting: Boolean) {
  val canStart = enabled && !isStarting
  Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = onStart, enabled = canStart, modifier = Modifier.weight(1f)) {
      if (isStarting) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.nav_starting))
      } else {
        Icon(painterResource(R.drawable.ic_navigation), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.nav_start))
      }
    }
    if (onSimulate != null) {
      OutlinedButton(onClick = onSimulate, enabled = canStart) { Text(stringResource(R.string.nav_simulate)) }
    }
  }
}

@Composable
internal fun RouteSummary(status: RouteStatus, onRetry: () -> Unit) {
  when (status) {
    is RouteStatus.Ready ->
      Text(
        "${formatDistance(status.route.distanceMeters)} · ${formatDuration(status.route.durationSeconds)}",
        style = MaterialTheme.typography.headlineSmall,
      )

    RouteStatus.Loading ->
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.route_finding), style = MaterialTheme.typography.bodyLarge)
      }

    RouteStatus.NeedsLocation ->
      Text(stringResource(R.string.route_needs_location), style = MaterialTheme.typography.bodyLarge)

    is RouteStatus.Failed ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          status.message,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.route_retry)) }
      }

    else -> Unit
  }
}
