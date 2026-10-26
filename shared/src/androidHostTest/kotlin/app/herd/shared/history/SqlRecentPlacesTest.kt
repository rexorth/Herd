package app.herd.shared.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.herd.shared.db.HerdDatabase
import app.herd.shared.search.Place
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.geojson.Position

class SqlRecentPlacesTest {
  private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HerdDatabase.Schema.create(it) }
  private var clock = 1_000L

  @AfterTest fun closeDriver() = driver.close()

  private fun place(name: String, osmId: Long? = null, lon: Double = -119.7) =
    Place(name, "Santa Barbara, California", Position(longitude = lon, latitude = 34.42), osmId = osmId)

  private fun runStoreTest(block: suspend (SqlRecentPlaces) -> Unit) = runTest {
    val store = SqlRecentPlaces(HerdDatabase(driver).recentPlaceQueries, StandardTestDispatcher(testScheduler), now = { clock++ })
    block(store)
  }

  @Test
  fun newestFirstAndEveryFieldSurvives() = runStoreTest { store ->
    val census =
      Place("123 W Main St", "Ventura, CA", Position(longitude = -119.3025, latitude = 34.2813), houseNumber = "123", street = "W Main St", locality = "Ventura, CA", isApproximate = true)
    store.record(place("Morro Bay", osmId = 1))
    store.record(census)

    assertEquals(listOf(census, place("Morro Bay", osmId = 1)), store.recent.first())
  }

  @Test
  fun pickingAgainMovesToTheTopWithoutDuplicating() = runStoreTest { store ->
    store.record(place("Morro Bay", osmId = 1))
    store.record(place("Pismo Beach", osmId = 2))
    // Same OpenStreetMap place, different display text: still one entry.
    store.record(place("Morro Bay, CA", osmId = 1))

    assertEquals(listOf("Morro Bay, CA", "Pismo Beach"), store.recent.first().map { it.name })
  }

  @Test
  fun equalTimestampsKeepPickOrder() = runTest {
    val store = SqlRecentPlaces(HerdDatabase(driver).recentPlaceQueries, StandardTestDispatcher(testScheduler), now = { 5L })
    store.record(place("A", osmId = 1))
    store.record(place("B", osmId = 2))
    store.record(place("A", osmId = 1))

    assertEquals(listOf("A", "B"), store.recent.first().map { it.name })
  }

  @Test
  fun showsEightAndKeepsTwentyFive() = runStoreTest { store ->
    repeat(30) { store.record(place("Place $it", osmId = it.toLong())) }

    assertEquals((29 downTo 22).map { "Place $it" }, store.recent.first().map { it.name })
    val kept = HerdDatabase(driver).recentPlaceQueries.latest(100).executeAsList()
    assertEquals(SqlRecentPlaces.KEPT, kept.size)
    assertEquals("Place 5", kept.last().name)
  }

  @Test
  fun removeDeletesOnlyThatPlace() = runStoreTest { store ->
    store.record(place("Morro Bay", osmId = 1))
    store.record(place("Pismo Beach", osmId = 2))

    store.remove(place("Pismo Beach", osmId = 2))

    assertEquals(listOf("Morro Bay"), store.recent.first().map { it.name })
  }

  @Test
  fun placesWithoutAnOsmIdMatchByNameAndPosition() {
    assertEquals(place("123 Main St").recentKey, place("123 Main St").recentKey)
    // Under a meter apart rounds to the same key; a block away doesn't.
    assertEquals(place("123 Main St", lon = -119.700001).recentKey, place("123 Main St").recentKey)
    assertNotEquals(place("123 Main St", lon = -119.701).recentKey, place("123 Main St").recentKey)
    assertTrue(place("x", osmId = 42).recentKey.startsWith("osm:"))
  }
}
