package app.herd.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.spatialk.geojson.Position

class OffRouteDetectorTest {
  // North on Hoover Street, then east on Adams Boulevard, then north on Vermont Avenue.
  private val hoover = listOf(Position(-118.2845, 34.0300), Position(-118.2845, 34.0320))
  private val adams = listOf(Position(-118.2845, 34.0320), Position(-118.2800, 34.0320))
  private val vermont = listOf(Position(-118.2800, 34.0320), Position(-118.2800, 34.0360))
  private val steps = listOf(hoover, adams, vermont)

  /** A point [metersWest] west of Hoover Street, halfway up the block. */
  private fun westOfHoover(metersWest: Double) = Position(-118.2845 - metersWest / 92_200.0, 34.0310)

  @Test
  fun onTheStepIsOnRoute() {
    val detector = OffRouteDetector()
    assertEquals(RouteFit.OnRoute, detector.check(westOfHoover(5.0), 5.0, fixId = 1, steps))
  }

  @Test
  fun twoFixesInARowConfirmLeavingTheRoute() {
    val detector = OffRouteDetector()
    assertEquals(RouteFit.OnRoute, detector.check(westOfHoover(40.0), 5.0, fixId = 1, steps))
    val off = detector.check(westOfHoover(45.0), 5.0, fixId = 2, steps)
    assertIs<RouteFit.OffRoute>(off)
    assertEquals(45.0, off.metersFromStep, absoluteTolerance = 0.5)
    assertEquals(false, off.aheadOnRoute)
  }

  @Test
  fun theSameFixCheckedTwiceCountsOnce() {
    val detector = OffRouteDetector()
    detector.check(westOfHoover(40.0), 5.0, fixId = 1, steps)
    assertEquals(RouteFit.OnRoute, detector.check(westOfHoover(40.0), 5.0, fixId = 1, steps))
  }

  @Test
  fun goodGpsNoticesSoonerThanPoorGps() {
    // 35 m off: past the 25 m threshold with a 5 m fix, inside the 50 m one with a 25 m fix.
    val good = OffRouteDetector()
    good.check(westOfHoover(35.0), 5.0, fixId = 1, steps)
    assertIs<RouteFit.OffRoute>(good.check(westOfHoover(35.0), 5.0, fixId = 2, steps))

    val poor = OffRouteDetector()
    poor.check(westOfHoover(35.0), 25.0, fixId = 1, steps)
    assertEquals(RouteFit.OnRoute, poor.check(westOfHoover(35.0), 25.0, fixId = 2, steps))
  }

  @Test
  fun thresholdScalesWithAccuracyWithinLimits() {
    assertEquals(25.0, offRouteThresholdMeters(3.0))
    assertEquals(40.0, offRouteThresholdMeters(20.0))
    assertEquals(50.0, offRouteThresholdMeters(45.0))
  }

  @Test
  fun vagueFixesChangeNothing() {
    val detector = OffRouteDetector()
    detector.check(westOfHoover(100.0), 5.0, fixId = 1, steps)
    assertIs<RouteFit.OffRoute>(detector.check(westOfHoover(100.0), 5.0, fixId = 2, steps))
    // A tunnel-grade fix that happens to land on the route doesn't end the detour.
    assertIs<RouteFit.OffRoute>(detector.check(westOfHoover(0.0), 80.0, fixId = 3, steps))

    val fresh = OffRouteDetector()
    assertEquals(RouteFit.OnRoute, fresh.check(westOfHoover(100.0), 80.0, fixId = 1, steps))
  }

  @Test
  fun alreadyOnTheNextStepIsOnRoute() {
    // Just turned onto Adams, before navigation has moved on from the Hoover step.
    val detector = OffRouteDetector()
    val onAdams = Position(-118.2830, 34.0320)
    detector.check(onAdams, 5.0, fixId = 1, steps)
    assertEquals(RouteFit.OnRoute, detector.check(onAdams, 5.0, fixId = 2, steps))
  }

  @Test
  fun nearALaterStepIsAheadOnRoute() {
    val detector = OffRouteDetector()
    val onVermont = Position(-118.2800, 34.0350)
    detector.check(onVermont, 5.0, fixId = 1, steps)
    val fit = detector.check(onVermont, 5.0, fixId = 2, steps)
    assertIs<RouteFit.OffRoute>(fit)
    assertTrue(fit.aheadOnRoute)
  }

  @Test
  fun comingBackToTheRouteResets() {
    val detector = OffRouteDetector()
    detector.check(westOfHoover(60.0), 5.0, fixId = 1, steps)
    detector.check(westOfHoover(60.0), 5.0, fixId = 2, steps)
    assertEquals(RouteFit.OnRoute, detector.check(westOfHoover(2.0), 5.0, fixId = 3, steps))
    // Leaving again needs two fresh fixes.
    assertEquals(RouteFit.OnRoute, detector.check(westOfHoover(60.0), 5.0, fixId = 4, steps))
  }
}
