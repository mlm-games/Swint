# Frair

Kotlin Multiplatform (Compose for Android) app with a Rust core called via JNI.

## Layout
- `composeApp/` — KMP Android app (Compose)
- `rust/` — Rust crate that builds a shared library (`libfrair_native.so`)

## Prerequisites
- Android Studio + Android SDK + NDK installed
- Rust toolchain (`rustup`), stable channel
- cargo-ndk: `cargo install cargo-ndk`

Ensure `cargo` and `cargo-ndk` are on your PATH, and the Android NDK is installed in your SDK.


## Build & Run (Android)
- macOS/Linux:
  ```bash
  ./gradlew :composeApp:assembleDebug
  ```
- Windows:
  ```powershell
  .\gradlew.bat :composeApp:assembleDebug
  ```

Gradle will invoke `cargo ndk` to build Rust for `arm64-v8a`, `x86_64`, and `armeabi-v7a`, and place the `.so` files under:
```
composeApp/src/androidMain/jniLibs/<abi>/libfrair_native.so
```

## Code Usage
Call Rust from shared code via an expect/actual facade:
```kotlin
val msg = RustApi.greet(getPlatform().name)
```

Rust implements the logic, JNI exposes:
```rust
Java_org_mlm_frair_NativeLib_greetFromRust(env, class, name)
```

## Notes
- We catch Rust panics at the JNI boundary and throw a Java `RuntimeException`, returning `""` as a fallback.
- Adjust `ndk.abiFilters` in `composeApp/build.gradle.kts` to the ABIs you intend to ship.
```

Notes:
- Install the Android NDK, cargo-ndk and std libs for targets before building.
- No other files need to change; existing UI (App.kt) will display the Rust greeting.