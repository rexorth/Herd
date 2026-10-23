# Herd

Navigation for road trips and group drives: live location sharing within a group, and planned
routes that guide you back on course when you leave them. Android first (Kotlin), iOS later
(SwiftUI on the same shared Kotlin module).

## v0.1: map and my location

- Renders an OpenStreetMap vector map ([OpenFreeMap](https://openfreemap.org) "liberty" style, no API key).
- Shows your position (with accuracy circle) and follows it. Panning stops following; the
  location button brings it back.
- Asks for foreground location once. If denied, the map still works and a banner offers to fix it.
- Location updates run only while the map is on screen.

## Project layout

```
androidApp/   Android app: Compose UI, MainActivity, MapScreen
shared/       Kotlin Multiplatform module (Android + iOS): map defaults, location request profiles
gradle/libs.versions.toml   all dependency versions
```

Key libraries: [maplibre-compose](https://github.com/maplibre/maplibre-compose) (map rendering,
location provider, location puck), Google Play Services fused location (through maplibre-compose's
`location-runtime-gms`).

## Build and run

Requirements: Android Studio (recent enough for Android Gradle Plugin 9.3), JDK 17+, Android SDK
platform 37.

```
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug   # with a phone connected (USB debugging on)
```

Or open the folder in Android Studio and run the `androidApp` configuration. Each push also builds
a debug APK in GitHub Actions ("Android build" workflow, `herd-debug-apk` artifact).

The iOS targets in `shared/` only build on macOS with Xcode; other hosts skip them.

## Attribution

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL), tiles by
OpenFreeMap, schema © OpenMapTiles. The map's attribution button shows this; keep it visible.
