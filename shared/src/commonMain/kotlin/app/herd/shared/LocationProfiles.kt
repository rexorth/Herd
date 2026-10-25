package app.herd.shared

import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationRequest
import org.maplibre.spatialk.units.extensions.meters

/**
 * Location request settings per app mode, kept in one place so battery tuning happens here.
 *
 * v0.1 only shows the user's position while the map is on screen. Later modes (navigating,
 * sharing in the background, parked) get their own entries.
 */
object LocationProfiles {
  /** Map in the foreground: GPS-level accuracy, at most one fix per second, ignore jitter under 5 m. */
  val mapVisible: LocationRequest = LocationRequest {
    accuracy = LocationAccuracy.High
    minimumInterval = 1.seconds
    minimumDistance = 5.meters
  }
}
