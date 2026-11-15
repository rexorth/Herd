package app.herd.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.herd.android.R
import app.herd.shared.format.formatDistance
import app.herd.shared.format.formatDuration
import app.herd.shared.plan.PlannedRouteSummary

/** The routes saved on this phone, newest first. Tapping one opens it in the editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesSheet(routes: List<PlannedRouteSummary>, onOpen: (Long) -> Unit, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Text(
      stringResource(R.string.saved_routes),
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (routes.isEmpty()) {
      Text(
        stringResource(R.string.saved_routes_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
      )
    } else {
      LazyColumn(Modifier.padding(top = 8.dp)) {
        items(routes, key = { it.id }) { route -> SavedRouteRow(route, onClick = { onOpen(route.id) }) }
      }
    }
    Spacer(Modifier.height(16.dp))
  }
}

@Composable
private fun SavedRouteRow(route: PlannedRouteSummary, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(painterResource(R.drawable.ic_route), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Column(Modifier.weight(1f)) {
      Text(route.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        pluralStringResource(
          R.plurals.saved_route_points,
          route.pointCount,
          formatDistance(route.distanceMeters),
          formatDuration(route.durationSeconds),
          route.pointCount,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}
