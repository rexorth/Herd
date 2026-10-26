package app.herd.shared.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.spatialk.geojson.Position

class AddressMatchingTest {
  private fun house(number: String?, street: String?) =
    Place(name = "x", subtitle = null, position = Position(0.0, 0.0), houseNumber = number, street = street)

  @Test
  fun parsesHouseNumberAndStreet() {
    assertEquals(TypedAddress("123", "main st"), parseStreetAddress("123 main st, ventura, ca"))
    assertEquals(TypedAddress("12B", "Oak Ave"), parseStreetAddress(" 12B Oak Ave"))
    assertNull(parseStreetAddress("morro bay"))
    assertNull(parseStreetAddress("123"))
  }

  @Test
  fun keyStreetWordSkipsDirections() {
    assertEquals("main", TypedAddress("123", "W Main St").keyStreetWord)
    assertEquals("anacapa", TypedAddress("738", "anacapa").keyStreetWord)
  }

  @Test
  fun matchesOnlyTheSameHouseOnTheSameStreet() {
    val typed = TypedAddress("123", "main st")
    assertTrue(house("123", "Main Street").isAddress(typed))
    assertTrue(house("123", "West Main Street").isAddress(typed))
    assertFalse(house("1239", "East Main Street").isAddress(typed))
    assertFalse(house("123", "Mainsail Drive").isAddress(typed))
    assertFalse(house(null, "Main Street").isAddress(typed))
  }

  @Test
  fun detectsWhetherAQueryNamesAPlace() {
    assertTrue(hasLocality("123 main st, ventura"))
    assertTrue(hasLocality("1200 state st 93101"))
    assertFalse(hasLocality("123 main st"))
    // A five-digit house number isn't a ZIP code.
    assertFalse(hasLocality("12345 main st"))
  }

  @Test
  fun typedTownIsTheFirstPartAfterTheStreet() {
    assertEquals("ventura", typedTown("123 main st, ventura, ca"))
    assertNull(typedTown("123 main st"))
    assertNull(typedTown("123 main st, 93001"))
  }

  @Test
  fun distanceBetweenTowns() {
    val santaBarbara = Position(longitude = -119.6982, latitude = 34.4208)
    val ventura = Position(longitude = -119.2290, latitude = 34.2746)
    // About 46 km apart.
    assertEquals(46.0, distanceKm(santaBarbara, ventura), absoluteTolerance = 0.1)
  }
}
