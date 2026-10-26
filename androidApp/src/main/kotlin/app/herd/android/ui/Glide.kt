package app.herd.android.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.herd.shared.interpolateBearing
import app.herd.shared.interpolatePosition
import app.herd.shared.smoothingDuration
import kotlin.time.Duration
import kotlin.time.TimeSource
import org.maplibre.spatialk.geojson.Position

/** Where the location dot is drawn and which way it points (degrees from north), if known. */
data class Pose(val position: Position, val bearing: Double?)

/**
 * Moves the location dot from fix to fix one frame at a time.
 *
 * The dot and the camera following it are both placed from [current] on every frame, so they move
 * together and never pause between fixes. (Animating them separately with MapLibre's own
 * transitions showed as jerks.) Each glide lasts as long as the gap since the previous fix, so the
 * dot reaches a fix about when the next one arrives; it trails the car by about one fix.
 */
@Stable
class Glide {
  var current: Pose? by mutableStateOf(null)
    private set

  private var lastFix: TimeSource.Monotonic.ValueTimeMark? = null

  /**
   * Glides from wherever the dot is now to [target], calling [onFrame] with each pose drawn.
   * Cancelling (a newer fix arrived) leaves the dot mid-way, and the next glide starts from there.
   */
  suspend fun to(target: Pose, onFrame: (Pose) -> Unit) {
    val now = TimeSource.Monotonic.markNow()
    val duration = smoothingDuration(lastFix?.let { now - it })
    lastFix = now
    val from = current
    if (from == null) {
      show(target, onFrame)
      return
    }
    val start = withFrameNanos { it }
    var fraction: Double
    do {
      fraction = withFrameNanos { frame -> fractionOf(frame - start, duration) }
      show(between(from, target, fraction), onFrame)
    } while (fraction < 1.0)
  }

  /** Forgets the last pose, so the next fix is drawn where it is instead of glided to. */
  fun reset() {
    current = null
    lastFix = null
  }

  private fun show(pose: Pose, onFrame: (Pose) -> Unit) {
    current = pose
    onFrame(pose)
  }
}

private fun fractionOf(elapsedNanos: Long, duration: Duration): Double =
  (elapsedNanos.toDouble() / duration.inWholeNanoseconds).coerceIn(0.0, 1.0)

private fun between(from: Pose, to: Pose, fraction: Double): Pose =
  Pose(
    position = interpolatePosition(from.position, to.position, fraction),
    bearing =
      when {
        from.bearing == null -> to.bearing
        to.bearing == null -> from.bearing
        else -> interpolateBearing(from.bearing, to.bearing, fraction)
      },
  )
