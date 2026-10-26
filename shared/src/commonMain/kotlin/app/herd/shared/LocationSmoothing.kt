package app.herd.shared

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long the location dot and the camera following it take to glide to a new fix.
 *
 * Matching the time since the previous fix keeps the dot moving steadily: it reaches each fix just
 * as the next one arrives, instead of jumping there and then waiting. The clamp stops a long gap
 * (a tunnel, a GPS dropout) from dragging the dot slowly across the map.
 */
fun smoothingDuration(sinceLastFix: Duration?): Duration =
  sinceLastFix?.coerceIn(MIN_SMOOTHING, MAX_SMOOTHING) ?: DEFAULT_SMOOTHING

private val MIN_SMOOTHING = 250.milliseconds
private val MAX_SMOOTHING = 1500.milliseconds
/** The usual gap between fixes (LocationProfiles.mapVisible asks for one per second). */
private val DEFAULT_SMOOTHING = 1.seconds
