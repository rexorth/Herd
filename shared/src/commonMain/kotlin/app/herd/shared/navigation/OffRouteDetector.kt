package app.herd.shared.navigation

import app.herd.shared.geo.distanceToLineMeters
import org.maplibre.spatialk.geojson.Position

/** Whether the car is following the route. */
sealed interface RouteFit {
  data object OnRoute : RouteFit

  /**
   * Off the route by [metersFromStep] from the step being driven. [aheadOnRoute] means it's near a
   * later part of the route instead (a shortcut, or the route doubling back).
   */
  data class OffRoute(val metersFromStep: Double, val aheadOnRoute: Boolean) : RouteFit
}

/**
 * Decides, fix by fix, when the car has left the route.
 *
 * How far off counts depends on how good the fix is: [offRouteThresholdMeters]. With good GPS,
 * turning onto another street is noticed within about 25 m, instead of the 50 m a fixed threshold
 * needs to stay safe with poor GPS. Two fixes in a row must agree, so one stray fix doesn't
 * reroute; a fix too vague to judge ([MAX_USABLE_ACCURACY_M]) changes nothing.
 *
 * The step being driven and the one after it both count as on the route: just after a turn,
 * the car is on the next step before navigation has moved on to it.
 *
 * Keeps state between calls, so use one per navigation session (or one for the app: the first fix
 * on a new route resets it).
 */
class OffRouteDetector(private val confirmingFixes: Int = 2) {
  private var lastFix: Long? = null
  private var offFixes = 0
  private var last: RouteFit = RouteFit.OnRoute

  /**
   * [fixId] identifies the GPS fix (its timestamp), since the same fix can be checked more than
   * once. [remainingSteps] are the lines of the steps still ahead, starting with the one being
   * driven; later steps are only read if the car seems to be off the first two.
   */
  fun check(
    fix: Position,
    accuracyMeters: Double,
    fixId: Long,
    remainingSteps: List<List<Position>>,
  ): RouteFit {
    if (accuracyMeters > MAX_USABLE_ACCURACY_M || remainingSteps.isEmpty()) return last
    val newFix = fixId != lastFix
    lastFix = fixId

    val threshold = offRouteThresholdMeters(accuracyMeters)
    val fromStep = distanceToLineMeters(fix, remainingSteps[0])
    val onRoute =
      fromStep <= threshold ||
        (remainingSteps.size > 1 && distanceToLineMeters(fix, remainingSteps[1]) <= threshold)
    if (onRoute) {
      offFixes = 0
      last = RouteFit.OnRoute
      return last
    }

    if (newFix) offFixes++
    if (offFixes < confirmingFixes) return RouteFit.OnRoute
    val ahead = (2 until remainingSteps.size).any { distanceToLineMeters(fix, remainingSteps[it]) <= threshold }
    last = RouteFit.OffRoute(fromStep, aheadOnRoute = ahead)
    return last
  }

  companion object {
    /** Fixes vaguer than this (a tunnel, a deep urban canyon) can't tell on-route from off. */
    const val MAX_USABLE_ACCURACY_M = 30.0
  }
}

/**
 * How far from the route line a fix must be to count as off it: twice the fix's accuracy, but at
 * least 25 m (half a wide boulevard plus normal GPS error) and at most 50 m.
 */
fun offRouteThresholdMeters(accuracyMeters: Double): Double = (accuracyMeters * 2).coerceIn(25.0, 50.0)
