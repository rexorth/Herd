package app.herd.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.herd.android.R
import app.herd.shared.format.formatDistance
import app.herd.shared.plan.MAX_PLAN_POINTS
import app.herd.shared.plan.PlanEditorState
import app.herd.shared.plan.PlanPoint
import app.herd.shared.routing.RouteOptions

/** Colors that mark a planned route, the same on the map and in the editor. */
internal object PlanColors {
  val start = Color(0xFF2E7D32)
  /** The stretch of a trip that gets onto (or back onto) the plan. */
  val joinLeg = Color(0xFFF57C00)
}

internal enum class PointKind {
  Start,
  Stop,
  PassThrough,
  End,
}

internal fun pointKind(points: List<PlanPoint>, index: Int): PointKind =
  when {
    index == 0 -> PointKind.Start
    index == points.lastIndex -> PointKind.End
    points[index].passThrough -> PointKind.PassThrough
    else -> PointKind.Stop
  }

/**
 * A planned route being edited: its points in order (each with a menu to move, switch between stop
 * and pass-through, or remove it), its distance and time, its route preferences, Save and Start.
 * Points are added from the search bar and by long-pressing the map, outside this panel.
 *
 * [onSimulate] is null except in debug builds. [startError] is why the last Start failed.
 */
@Composable
fun PlanEditorPanel(
  state: PlanEditorState,
  onRename: (String) -> Unit,
  onMove: (index: Int, by: Int) -> Unit,
  onTogglePassThrough: (index: Int) -> Unit,
  onRemove: (index: Int) -> Unit,
  onOptionsChange: (RouteOptions) -> Unit,
  onRetry: () -> Unit,
  onSave: () -> Unit,
  onDelete: () -> Unit,
  onClose: () -> Unit,
  onStart: () -> Unit,
  onSimulate: (() -> Unit)?,
  isStarting: Boolean,
  startError: String?,
  modifier: Modifier = Modifier,
) {
  var renaming by remember { mutableStateOf(false) }
  var confirmingDelete by remember { mutableStateOf(false) }

  Card(modifier) {
    Column(
      Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          state.displayName,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Box {
          var menuOpen by remember { mutableStateOf(false) }
          IconButton(onClick = { menuOpen = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.plan_options))
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.plan_rename)) },
              onClick = {
                menuOpen = false
                renaming = true
              },
            )
            if (state.id != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.plan_delete)) },
                onClick = {
                  menuOpen = false
                  confirmingDelete = true
                },
              )
            }
          }
        }
        IconButton(onClick = onClose) {
          Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.plan_close))
        }
      }

      // A full plan is taller than the space it gets; the list scrolls instead of the map shrinking.
      Column(Modifier.heightIn(max = 232.dp).verticalScroll(rememberScrollState())) {
        state.points.forEachIndexed { index, point ->
          val kind = pointKind(state.points, index)
          PointRow(
            point = point,
            kind = kind,
            onMoveUp = if (index > 0) ({ onMove(index, -1) }) else null,
            onMoveDown = if (index < state.points.lastIndex) ({ onMove(index, 1) }) else null,
            onTogglePassThrough =
              if (kind == PointKind.Stop || kind == PointKind.PassThrough) ({ onTogglePassThrough(index) }) else null,
            onRemove = { onRemove(index) },
          )
        }
      }

      if (state.points.size < MAX_PLAN_POINTS) {
        Text(
          stringResource(R.string.plan_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(end = 8.dp),
        )
      }
      state.notice?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { RouteSummary(state.route, onRetry) }
        val saved = state.id != null && !state.hasUnsavedChanges
        TextButton(onClick = onSave, enabled = !saved && state.plan != null) {
          Text(stringResource(if (saved) R.string.plan_saved else R.string.plan_save))
        }
      }

      RouteOptionChips(state.options, onOptionsChange)

      startError?.let { StartError(it) }
      StartButtons(onStart = onStart, onSimulate = onSimulate, enabled = state.plan != null, isStarting = isStarting)
    }
  }

  if (renaming) {
    RenameDialog(
      name = state.name,
      placeholder = state.displayName,
      onDone = {
        onRename(it)
        renaming = false
      },
      onDismiss = { renaming = false },
    )
  }
  if (confirmingDelete) {
    AlertDialog(
      onDismissRequest = { confirmingDelete = false },
      title = { Text(stringResource(R.string.plan_delete_title)) },
      text = { Text(stringResource(R.string.plan_delete_body, state.displayName)) },
      confirmButton = {
        TextButton(
          onClick = {
            confirmingDelete = false
            onDelete()
          }
        ) {
          Text(stringResource(R.string.plan_delete))
        }
      },
      dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

/** A null action leaves its menu item out. */
@Composable
private fun PointRow(
  point: PlanPoint,
  kind: PointKind,
  onMoveUp: (() -> Unit)?,
  onMoveDown: (() -> Unit)?,
  onTogglePassThrough: (() -> Unit)?,
  onRemove: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PointMarker(kind)
    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
      Text(point.place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      val label =
        stringResource(
          when (kind) {
            PointKind.Start -> R.string.plan_point_start
            PointKind.Stop -> R.string.plan_point_stop
            PointKind.PassThrough -> R.string.plan_point_pass_through
            PointKind.End -> R.string.plan_point_end
          }
        )
      Text(
        listOfNotNull(label, placeContext(point.place)).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Box {
      var menuOpen by remember { mutableStateOf(false) }
      IconButton(onClick = { menuOpen = true }) {
        Icon(
          painterResource(R.drawable.ic_more_vert),
          contentDescription = stringResource(R.string.plan_point_options, point.place.name),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        @Composable
        fun item(text: Int, action: (() -> Unit)?) {
          if (action == null) return
          DropdownMenuItem(
            text = { Text(stringResource(text)) },
            onClick = {
              menuOpen = false
              action()
            },
          )
        }
        item(R.string.plan_move_up, onMoveUp)
        item(R.string.plan_move_down, onMoveDown)
        item(
          if (kind == PointKind.PassThrough) R.string.plan_make_stop else R.string.plan_make_pass_through,
          onTogglePassThrough,
        )
        item(R.string.plan_remove, onRemove)
      }
    }
  }
}

/** The point's marker as the map draws it. */
@Composable
private fun PointMarker(kind: PointKind) {
  val primary = MaterialTheme.colorScheme.primary
  val fill =
    when (kind) {
      PointKind.Start -> PlanColors.start
      PointKind.Stop -> primary
      PointKind.PassThrough -> Color.White
      PointKind.End -> MaterialTheme.colorScheme.error
    }
  val stroke = if (kind == PointKind.PassThrough) primary else Color.White
  val size = if (kind == PointKind.PassThrough) 12.dp else 16.dp
  Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
    Box(Modifier.size(size).background(fill, CircleShape).border(2.dp, stroke, CircleShape))
  }
}

/** A dialog sits above the keyboard, which would cover a name field in the panel at the bottom. */
@Composable
private fun RenameDialog(name: String, placeholder: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
  var text by remember { mutableStateOf(name) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.plan_rename)) },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.plan_name)) },
        placeholder = { Text(placeholder) },
        singleLine = true,
      )
    },
    confirmButton = { TextButton(onClick = { onDone(text.trim()) }) { Text(stringResource(R.string.nav_done)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

/** Starting a plan away from its first point: drive there first, or join where it's closest. */
@Composable
internal fun JoinPlanDialog(distanceMeters: Double, onFromStart: () -> Unit, onNearest: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.join_title, formatDistance(distanceMeters))) },
    text = { Text(stringResource(R.string.join_body)) },
    confirmButton = { TextButton(onClick = onFromStart) { Text(stringResource(R.string.join_from_start)) } },
    dismissButton = { TextButton(onClick = onNearest) { Text(stringResource(R.string.join_nearest)) } },
  )
}

@Composable
internal fun DiscardPlanDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.plan_discard_title)) },
    text = { Text(stringResource(R.string.plan_discard_body)) },
    confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.plan_discard)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_keep_editing)) } },
  )
}
