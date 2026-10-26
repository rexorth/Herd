package app.herd.shared.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
  @Test
  fun distances() {
    assertEquals("0 ft", formatDistance(0.0))
    assertEquals("330 ft", formatDistance(100.0))
    assertEquals("0.1 mi", formatDistance(161.0))
    assertEquals("8.4 mi", formatDistance(13_589.0))
    assertEquals("10 mi", formatDistance(16_093.44))
    assertEquals("200 mi", formatDistance(321_869.0))
  }

  @Test
  fun durations() {
    assertEquals("1 min", formatDuration(5.0))
    assertEquals("20 min", formatDuration(1213.171))
    assertEquals("1 h", formatDuration(3600.0))
    assertEquals("1 h 25 min", formatDuration(5100.0))
    assertEquals("5 h 3 min", formatDuration(18_180.0))
  }
}
