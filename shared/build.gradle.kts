plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.sqldelight)
}

kotlin {
  android {
    namespace = "app.herd.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    // Runs commonTest on the JVM as Android host tests, so tests work without a Mac or a device.
    withHostTestBuilder {}
  }

  // Compiled into Shared.framework for the future SwiftUI app. Needs macOS + Xcode to build.
  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework {
      baseName = "Shared"
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(libs.maplibre.location)
      api(libs.ktor.client.core)
      implementation(libs.ktor.client.contentNegotiation)
      implementation(libs.ktor.serialization.kotlinxJson)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.sqldelight.coroutines)
    }
    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
      implementation(libs.sqldelight.android.driver)
    }
    iosMain.dependencies {
      implementation(libs.ktor.client.darwin)
      implementation(libs.sqldelight.native.driver)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.ktor.client.mock)
      implementation(libs.kotlinx.coroutines.test)
    }
    // Database tests run against SQLite on the JVM, so they live in the host-test source set.
    getByName("androidHostTest").dependencies {
      implementation(libs.sqldelight.sqlite.driver)
    }
  }
}

// On-device storage: recent searches now, planned routes next. Queries live in src/commonMain/sqldelight.
sqldelight {
  databases {
    create("HerdDatabase") {
      packageName.set("app.herd.shared.db")
    }
  }
}
