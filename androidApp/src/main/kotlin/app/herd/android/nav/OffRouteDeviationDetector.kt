package app.herd.android.nav

import app.herd.shared.navigation.OffRouteDetector
import app.herd.shared.navigation.RouteFit
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.DeviationKind
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteDeviationDetector
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.TripState

/**
 * Ferrostar asks this on every fix whether the car has left the route; the decision itself is the
 * shared [OffRouteDetector]. Off the route, Ferrostar shows the real position and fetches a new
 * route.
 */
internal class OffRouteDeviationDetector : RouteDeviationDetector {
  private val detector = OffRouteDetector()

  override fun checkRouteDeviation(route: Route, tripState: TripState): RouteDeviation {
    val trip = tripState as? TripState.Navigating ?: return RouteDeviation.NoDeviation
    val fix = trip.userLocation
    val fit =
      detector.check(
        fix = fix.coordinates.toPosition(),
        accuracyMeters = fix.horizontalAccuracy,
        fixId = fix.timestamp.toEpochMilli(),
        remainingSteps = StepLines(trip.remainingSteps),
      )
    return when (fit) {
      RouteFit.OnRoute -> RouteDeviation.NoDeviation
      is RouteFit.OffRoute ->
        RouteDeviation.Deviation(
          if (fit.aheadOnRoute) DeviationKind.OffStepOnRoute(fit.metersFromStep)
          else DeviationKind.CompletelyOffRoute(fit.metersFromStep)
        )
    }
  }
}

/** Each step's line, converted only when the detector reads it (usually just the first two). */
private class StepLines(private val steps: List<RouteStep>) : AbstractList<List<Position>>() {
  override val size: Int
    get() = steps.size

  override fun get(index: Int): List<Position> = steps[index].geometry.map { it.toPosition() }
}

private fun GeographicCoordinate.toPosition() = Position(longitude = lng, latitude = lat)
