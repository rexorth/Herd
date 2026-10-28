package app.herd.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.herd.shared.history.RecentPlaceStore
import app.herd.shared.history.SqlRecentPlaces
import app.herd.shared.plan.PlannedRouteStore
import app.herd.shared.plan.SqlPlannedRoutes
import kotlin.coroutines.CoroutineContext

/**
 * Everything Herd keeps on the phone, in one SQLite database. Each platform opens it with its own
 * driver (`openHerdStorage`); the queries are in `src/commonMain/sqldelight`.
 */
class HerdStorage internal constructor(private val driver: SqlDriver, io: CoroutineContext) {
  private val database = HerdDatabase(driver)

  val recentPlaces: RecentPlaceStore = SqlRecentPlaces(database.recentPlaceQueries, io)

  val plannedRoutes: PlannedRouteStore = SqlPlannedRoutes(database.plannedRouteQueries, io)

  fun close() = driver.close()

  internal companion object {
    const val FILE_NAME = "herd.db"
  }
}
