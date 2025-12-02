# Mages

Mages is an experimental matrix chat client.

- UI: [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
- Core: a Rust library built on top of [matrix-rust-sdk](https://github.com/matrix-org/matrix-rust-sdk), exposed to Kotlin via UniFFI (not using matrix-sdk-ffi)

The goal was to have a cross‑platform desktop/mobile client, (initially there were very few alternatives to element-desktop which notify, and stay hidden in my tray (unlike neochat) while not being as heavy. This kinda is heavy (though not as much), until i find a better solution for notifs on desktop). It's never going to be as feature-rich or stable as element's clients (or other clients like fluffy or fractal with a proper consumer-focused goal)

## Status 

This is experimental‑stage software. If you use it, assume things may break and encryption bugs are still possible.

## Features (as of the last README update)

- Room list with basic previews and unread counts
- Room and thread timelines (text, media, polls)
- End‑to‑end encryption (via matrix‑sdk)
- Spaces browser
- Simple presence / privacy settings
- Android app and Linux desktop builds (AppImage, AUR `mages-bin`)

## Platforms

- **Android**  
  - Signed APKs and AABs are published on GitHub Releases.
  - F‑Droid metadata is planned; for now you can sideload the APK.

- **Linux desktop**  
  - AppImage builds for x86_64 and aarch64.
  - AUR:  wraps the AppImage.
  - Other package formats (Flatpak, Snap) may follow.

- **Other platforms**  
  - The UI is Compose Multiplatform. In practice, only Android + Linux AppImage are actively tested.

## Architecture

- **Rust core**  
  - Uses `matrix-sdk` and `matrix-sdk-ui` for sync, room list, timelines, E2EE, etc.
  - Exposed as a UniFFI library (`mages_ffi`) that Kotlin/JVM can call.

- **Kotlin UI**  
  - Compose Multiplatform for Android and desktop.
  - Koin for dependency injection.
  - ViewModels for state, backed by the Rust core.

Most Matrix‑specific logic lives in Rust; Kotlin mostly handles presentation.

## Building from source

### Prerequisites

- JDK 21
- Kotlin/Gradle
- Rust toolchain (stable)
- On Android:
  - Android SDK + NDK (see `android-release` workflow for versions)
  - `cargo-ndk` (for building the Rust library for Android ABIs)

### Android

```bash
./gradlew :composeApp:assembleRelease
# APKs end up under composeApp/build/outputs/apk/release
```

### Desktop (Linux)

```bash
# Build Rust JNI + desktop distribution
cargo build --release --manifest-path rust/Cargo.toml
./gradlew :composeApp:genUniFFIJvm :composeApp:packageDistributionForCurrentOS
# AppImage is assembled by the desktop release workflow
```

## Contributing

Issues and small PRs are welcome. Please keep changes focused and self‑contained.

- Rust changes: try to keep the FFI surface small and stable.
- Kotlin changes: avoid expensive work in composables; use ViewModels and background dispatchers.

## License

Mages is licensed under the **GNU GPL v3** (see `LICENSE`).
