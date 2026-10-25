# Umbra Browser for Android

Umbra on Android: the Firefox engine (GeckoView) inside a Black Panther & Silver Vortex interface.

- One top row: back / forward / reload capsule, tabs as round favicon bubbles, and the open tab is a wide pill that is also the address bar.
- A shiny liquid fill flows through the pill while a page loads.
- Light and dark follow the system (or pick one in the menu).
- uBlock Origin is built in, and tracking protection is strict.
- Private tabs, find in page, desktop site, downloads, file uploads, page dialogs, tab restore after restart.

## Build

GitHub Actions builds every push to `main` (`.github/workflows/build.yml`) and puts unsigned APKs on the `latest` release.
Locally you need JDK 17 and the Android SDK (platform 36):

```sh
./scripts/fetch-ublock.sh      # needs the gh CLI
./gradlew assembleRelease
```

To sign locally, create `keystore.properties` (not in git) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

The engine version comes from `geckoviewVersion` in `gradle.properties` (`+` = newest release).
GeckoView is © Mozilla, MPL 2.0.
