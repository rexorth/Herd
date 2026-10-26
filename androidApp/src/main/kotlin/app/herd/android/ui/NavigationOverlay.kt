package app.herd.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.herd.android.R
import app.herd.android.nav.NavUiState
import app.herd.shared.search.Place
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import com.stadiamaps.ferrostar.composeui.views.components.TripProgressView

/**
 * Turn-by-turn controls over the map: the next maneuver at the top (tap it for the full list), and
 * the arrival time, distance left, voice and recenter buttons at the bottom. The X in the progress
 * bar ends the trip.
 *
 * [topModifier] and [bottomModifier] let the map measure both panels to keep the car between them.
 */
@Composable
fun NavigationOverlay(
  state: NavUiState.Navigating,
  following: Boolean,
  onRecenter: () -> Unit,
  onMuteChange: (Boolean) -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
  topModifier: Modifier = Modifier,
  bottomModifier: Modifier = Modifier,
) {
  KeepScreenOnDisposableEffect()
  val trip = state.trip

  Box(modifier.fillMaxSize()) {
    Column(
      Modifier.align(Alignment.TopCenter)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(16.dp)
        .fillMaxWidth()
        .then(topModifier),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      trip.visualInstruction?.let {
        InstructionsView(
          instructions = it,
          distanceToNextManeuver = trip.progress.distanceToNextManeuver,
          remainingSteps = trip.remainingSteps,
        )
      }
      if (state.isRerouting) StatusPill(stringResource(R.string.nav_rerouting))
      else if (state.isSimulating) StatusPill(stringResource(R.string.nav_simulated))
    }

    Column(
      Modifier.align(Alignment.BottomCenter)
        .then(bottomModifier)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(16.dp)
        .fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.End,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallFloatingActionButton(onClick = { onMuteChange(!state.isMuted) }) {
          Icon(
            painterResource(if (state.isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
            contentDescription = stringResource(if (state.isMuted) R.string.nav_unmute else R.string.nav_mute),
          )
        }
        if (!following) {
          SmallFloatingActionButton(onClick = onRecenter) {
            Icon(painterResource(R.drawable.ic_navigation), contentDescription = stringResource(R.string.recenter))
          }
        }
      }
      TripProgressView(progress = trip.progress, onTapExit = onStop, modifier = Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun StatusPill(text: String) {
  Surface(
    shape = RoundedCornerShape(50),
    color = MaterialTheme.colorScheme.secondaryContainer,
    shadowElevation = 4.dp,
  ) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
  }
}

/** Shown when the trip ends at the destination. */
@Composable
fun ArrivedCard(destination: Place, onDone: () -> Unit, modifier: Modifier = Modifier) {
  Card(modifier) {
    Row(
      Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(stringResource(R.string.nav_arrived), style = MaterialTheme.typography.titleMedium)
        Text(
          destination.name,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Button(onClick = onDone) { Text(stringResource(R.string.nav_done)) }
    }
  }
}
