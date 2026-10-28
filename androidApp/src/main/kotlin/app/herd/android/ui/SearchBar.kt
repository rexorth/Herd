package app.herd.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.herd.android.R
import app.herd.shared.planner.PlannerState
import app.herd.shared.search.Place

/**
 * Search field with a list underneath while it's focused: suggestions for what's typed, or recent
 * destinations while it's empty.
 *
 * [onShowSaved], when set, puts a saved-routes button in the empty field. A change of [pickKey]
 * means a place was picked without becoming the destination (a plan's new stop), which closes the
 * keyboard and list the same way.
 */
@Composable
fun SearchBar(
  state: PlannerState,
  onQueryChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onSelect: (Place) -> Unit,
  onClear: () -> Unit,
  onRemoveRecent: (Place) -> Unit,
  hint: String,
  onShowSaved: (() -> Unit)?,
  modifier: Modifier = Modifier,
  fieldModifier: Modifier = Modifier,
  pickKey: Any? = null,
) {
  val focusManager = LocalFocusManager.current
  var focused by remember { mutableStateOf(false) }

  // A new destination, from a tap, Enter or a long-press, closes the keyboard and suggestions.
  // Keyed on position, so a dropped pin's name filling in later doesn't take focus away again.
  LaunchedEffect(state.destination?.position) {
    if (state.destination != null) focusManager.clearFocus()
  }
  LaunchedEffect(pickKey) { focusManager.clearFocus() }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, modifier = fieldModifier) {
      TextField(
        value = state.query,
        onValueChange = onQueryChange,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
          if (state.query.isNotEmpty()) {
            IconButton(onClick = onClear) {
              Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.search_clear),
              )
            }
          } else if (onShowSaved != null) {
            IconButton(onClick = onShowSaved) {
              Icon(painterResource(R.drawable.ic_route), contentDescription = stringResource(R.string.saved_routes))
            }
          }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Enter searches exactly what's typed and goes to the top result; the field keeps focus
        // so "no results" can show, and lets go once a destination is picked (below).
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        shape = RoundedCornerShape(28.dp),
        colors =
          TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
          ),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
      )
    }

    val hasContent =
      state.suggestions.isNotEmpty() || state.isSearching || state.searchError != null ||
        state.offerExactAddress
    if (focused && hasContent) {
      Card(Modifier.fillMaxWidth()) {
        if (state.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.searchError?.let {
          Text(
            it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
          )
        }
        // Search may not have this house; this row looks the address up properly (same as Enter).
        if (state.offerExactAddress) {
          FindAddressRow(state.query.trim(), onClick = onSubmit)
          if (state.suggestions.isNotEmpty()) HorizontalDivider()
        }
        state.suggestions.forEachIndexed { index, place ->
          if (index > 0) HorizontalDivider()
          SuggestionRow(
            place,
            onClick = { onSelect(place) },
          )
        }
      }
    } else if (focused && state.query.isEmpty() && state.recents.isNotEmpty()) {
      Card(Modifier.fillMaxWidth()) {
        Text(
          stringResource(R.string.recent_searches),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        )
        state.recents.forEach { place ->
          RecentRow(place, onClick = { onSelect(place) }, onRemove = { onRemoveRecent(place) })
        }
      }
    }
  }
}

@Composable
private fun RecentRow(place: Place, onClick: () -> Unit, onRemove: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painterResource(R.drawable.ic_history),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
      Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      placeContext(place)?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    IconButton(onClick = onRemove) {
      Icon(
        painterResource(R.drawable.ic_close),
        contentDescription = stringResource(R.string.recent_remove, place.name),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun FindAddressRow(address: String, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painterResource(R.drawable.ic_search),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      stringResource(R.string.search_exact_address, address),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.primary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun SuggestionRow(place: Place, onClick: () -> Unit) {
  Column(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Text(
      place.name,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    placeContext(place)?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** The line under a place's name: its area, plus "approximate location" for estimated addresses. */
@Composable
internal fun placeContext(place: Place): String? {
  if (!place.isApproximate) return place.subtitle
  val approximate = stringResource(R.string.approximate_location)
  return place.subtitle?.let { "$it · $approximate" } ?: approximate
}
