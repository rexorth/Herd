package app.herd.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/** Search field with a suggestion list underneath while it's focused. */
@Composable
fun SearchBar(
  state: PlannerState,
  onQueryChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onSelect: (Place) -> Unit,
  onClear: () -> Unit,
  modifier: Modifier = Modifier,
  fieldModifier: Modifier = Modifier,
) {
  val focusManager = LocalFocusManager.current
  var focused by remember { mutableStateOf(false) }

  // A new destination, from a tap, Enter or a long-press, closes the keyboard and suggestions.
  // Keyed on position, so a dropped pin's name filling in later doesn't take focus away again.
  LaunchedEffect(state.destination?.position) {
    if (state.destination != null) focusManager.clearFocus()
  }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, modifier = fieldModifier) {
      TextField(
        value = state.query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
          if (state.query.isNotEmpty()) {
            IconButton(onClick = onClear) {
              Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.search_clear),
              )
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

    val hasContent = state.suggestions.isNotEmpty() || state.isSearching || state.searchError != null
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
        state.suggestions.forEachIndexed { index, place ->
          if (index > 0) HorizontalDivider()
          SuggestionRow(
            place,
            onClick = { onSelect(place) },
          )
        }
      }
    }
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
    place.subtitle?.let {
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
