package app.herd.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.maplibre.spatialk.geojson.Position

class LocationSmoothingTest {
  @Test
  fun glidesForTheGapBetweenFixes() {
    assertEquals(900.milliseconds, smoothingDuration(900.milliseconds))
  }

  @Test
  fun clampsVeryShortAndVeryLongGaps() {
    assertEquals(250.milliseconds, smoothingDuration(50.milliseconds))
    // Coming out of a tunnel: jump most of the way rather than crawl for 20 seconds.
    assertEquals(1500.milliseconds, smoothingDuration(20.seconds))
  }

  @Test
  fun firstFixUsesTheUsualInterval() {
    assertEquals(1.seconds, smoothingDuration(null))
  }

  @Test
  fun positionMovesInAStraightLine() {
    val from = Position(longitude = -119.70, latitude = 34.40)
    val to = Position(longitude = -119.68, latitude = 34.44)
    val quarter = interpolatePosition(from, to, 0.25)
    assertEquals(-119.695, quarter.longitude, absoluteTolerance = 1e-9)
    assertEquals(34.41, quarter.latitude, absoluteTolerance = 1e-9)
    assertEquals(from.longitude, interpolatePosition(from, to, 0.0).longitude, absoluteTolerance = 1e-9)
    assertEquals(to.latitude, interpolatePosition(from, to, 1.0).latitude, absoluteTolerance = 1e-9)
  }

  @Test
  fun positionCrossesTheAntimeridianTheShortWay() {
    val halfway = interpolatePosition(Position(179.9, 0.0), Position(-179.9, 0.0), 0.5)
    assertEquals(180.0, kotlin.math.abs(halfway.longitude), absoluteTolerance = 1e-9)
  }

  @Test
  fun bearingTurnsTheShortWay() {
    assertEquals(0.0, interpolateBearing(350.0, 10.0, 0.5), absoluteTolerance = 1e-9)
    assertEquals(355.0, interpolateBearing(10.0, 340.0, 0.5), absoluteTolerance = 1e-9)
    assertEquals(45.0, interpolateBearing(0.0, 90.0, 0.5), absoluteTolerance = 1e-9)
    assertEquals(90.0, interpolateBearing(0.0, 90.0, 1.0), absoluteTolerance = 1e-9)
  }

  @Test
  fun holdsStillForWanderWithinAccuracy() {
    val desk = Position(longitude = -119.6982, latitude = 34.4208)
    // About 11 m north.
    val drifted = Position(longitude = -119.6982, latitude = 34.4209)
    assertTrue(isStationaryWander(desk, drifted, accuracyMeters = 20.0, speedMetersPerSecond = 0.2))
    assertTrue(isStationaryWander(desk, drifted, accuracyMeters = 20.0, speedMetersPerSecond = null))
    // An accurate fix 11 m away is a real move.
    assertFalse(isStationaryWander(desk, drifted, accuracyMeters = 4.0, speedMetersPerSecond = 0.2))
    // Unknown accuracy still ignores tiny wander.
    assertTrue(isStationaryWander(desk, Position(longitude = -119.69822, latitude = 34.42081), null, null))
  }

  @Test
  fun movingAtSpeedAlwaysCounts() {
    val a = Position(longitude = -119.6982, latitude = 34.4208)
    val b = Position(longitude = -119.69821, latitude = 34.42081)
    assertFalse(isStationaryWander(a, b, accuracyMeters = 20.0, speedMetersPerSecond = 12.0))
  }
}
