package app.herd.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
}
