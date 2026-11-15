package app.herd.shared.plan

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.herd.shared.db.HerdDatabase
import app.herd.shared.routing.Route
import app.herd.shared.routing.RouteOptions
import app.herd.shared.search.Place
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

class SqlPlannedRoutesTest {
  private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HerdDatabase.Schema.create(it) }
  private var clock = 1_000L

  @AfterTest fun closeDriver() = driver.close()

  private val start = Place("Stearns Wharf", "Santa Barbara, California", Position(-119.6853, 34.4103))
  private val via = Place("Via point", null, Position(-119.7110, 34.4262))
  private val end = Place("Goleta Beach", "Goleta, California", Position(-119.8276, 34.4170))
  private val line = listOf(start.position, via.position, end.position)

  private fun plan(name: String = "Coast run", id: Long? = null) =
    PlannedRoute(
      id = id,
      name = name,
      points = listOf(PlanPoint(start), PlanPoint(via, passThrough = true), PlanPoint(end)),
      options = RouteOptions(avoidHighways = true),
      route = Route(line, distanceMeters = 14_200.0, durationSeconds = 1_260.0, bounds = BoundingBox(-119.8276, 34.4103, -119.6853, 34.4262)),
    )

  private fun runStoreTest(block: suspend (SqlPlannedRoutes) -> Unit) = runTest {
    block(SqlPlannedRoutes(HerdDatabase(driver).plannedRouteQueries, StandardTestDispatcher(testScheduler), now = { clock++ }))
  }

  @Test
  fun savesAndLoadsEverything() = runStoreTest { store ->
    val id = store.save(plan())

    val loaded = store.load(id)!!
    assertEquals(plan(id = id), loaded)
  }

  @Test
  fun savingAgainUpdatesInPlace() = runStoreTest { store ->
    val id = store.save(plan())
    val shorter = plan(name = "Short coast run", id = id).let { it.copy(points = it.points.dropLast(1) + PlanPoint(via)) }

    assertEquals(id, store.save(shorter))

    assertEquals(shorter, store.load(id))
    assertEquals(1, store.summaries.first().size)
  }

  @Test
  fun summariesListNewestFirstWithPointCounts() = runStoreTest { store ->
    store.save(plan(name = "Older"))
    store.save(plan(name = "Newer").copy(points = plan().points.take(2)))

    val summaries = store.summaries.first()
    assertEquals(listOf("Newer", "Older"), summaries.map { it.name })
    assertEquals(listOf(2, 3), summaries.map { it.pointCount })
    assertEquals(14_200.0, summaries.first().distanceMeters)
  }

  @Test
  fun deleteRemovesTheRouteAndItsPoints() = runStoreTest { store ->
    val id = store.save(plan())

    store.delete(id)

    assertNull(store.load(id))
    assertEquals(0L, driver.executeQuery(null, "SELECT count(*) FROM plan_point", { it.next(); app.cash.sqldelight.db.QueryResult.Value(it.getLong(0)) }, 0).value)
  }

  @Test
  fun upgradingAVersionOneDatabaseAddsPlannedRoutes() = runTest {
    // What a phone with v0.3 part 1 has: only the recent places table.
    val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    old.execute(null, "CREATE TABLE recent_place (key TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, subtitle TEXT, latitude REAL NOT NULL, longitude REAL NOT NULL, osm_id INTEGER, house_number TEXT, street TEXT, locality TEXT, is_approximate INTEGER NOT NULL, used_at INTEGER NOT NULL)", 0)

    HerdDatabase.Schema.migrate(old, oldVersion = 1, newVersion = HerdDatabase.Schema.version)

    val store = SqlPlannedRoutes(HerdDatabase(old).plannedRouteQueries, StandardTestDispatcher(testScheduler))
    val id = store.save(plan())
    assertEquals("Coast run", store.load(id)?.name)
    old.close()
  }
}
