plugins {
  // AGP 9 compiles Kotlin itself, so there is no separate kotlin-android plugin.
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "app.herd.android"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "app.herd"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
  implementation(project(":shared"))

  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)

  implementation(libs.maplibre.compose)
  implementation(libs.maplibre.compose.material3)
  implementation(libs.maplibre.location.gms)
  runtimeOnly(libs.maplibre.compose.runtime.opengl)
}
