package app.herd.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

/** The picked destination, the route's distance and time, and the route preferences. */
@Composable
fun RouteCard(
  state: PlannerState,
  onOptionsChange: (RouteOptions) -> Unit,
  onRetry: () -> Unit,
  onClose: () -> Unit,
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

      RouteSummary(state.route, onRetry)

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = state.options
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
  }
}

@Composable
private fun RouteSummary(status: RouteStatus, onRetry: () -> Unit) {
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
