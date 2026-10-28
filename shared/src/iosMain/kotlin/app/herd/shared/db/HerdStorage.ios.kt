package app.herd.shared.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** Opens (or creates) the app's database in its Application Support folder. */
fun openHerdStorage(): HerdStorage =
  HerdStorage(NativeSqliteDriver(HerdDatabase.Schema, HerdStorage.FILE_NAME), Dispatchers.IO)
