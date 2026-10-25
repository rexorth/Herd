package app.herd.shared.format

import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val METERS_PER_MILE = 1609.344
private const val FEET_PER_METER = 3.28084

/** "350 ft" under a tenth of a mile, "2.4 mi" under 10 miles, then whole miles ("128 mi"). */
fun formatDistance(meters: Double): String {
  val miles = meters / METERS_PER_MILE
  return when {
    miles < 0.1 -> "${((meters * FEET_PER_METER) / 10).roundToInt() * 10} ft"
    miles < 10 -> "${(miles * 10).roundToInt() / 10.0} mi"
    else -> "${miles.roundToInt()} mi"
  }
}

/** "1 min" minimum, "45 min", then "1 h 5 min" / "3 h". */
fun formatDuration(seconds: Double): String {
  val totalMinutes = (seconds / 60).roundToLong().coerceAtLeast(1)
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return when {
    hours == 0L -> "$minutes min"
    minutes == 0L -> "$hours h"
    else -> "$hours h $minutes min"
  }
}
