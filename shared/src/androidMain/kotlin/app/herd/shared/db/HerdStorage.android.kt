package app.herd.shared.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.Dispatchers

/** Opens (or creates) the app's database in its private storage. */
fun openHerdStorage(context: Context): HerdStorage =
  HerdStorage(
    AndroidSqliteDriver(HerdDatabase.Schema, context.applicationContext, HerdStorage.FILE_NAME),
    Dispatchers.IO,
  )
