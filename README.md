# SARI Camera

A native Android computational camera focused on aggressive hardware adaptation, safe burst processing, real RAW/DNG when supported, and offline computational photography.

## Architecture

- Kotlin: Android UI, Camera2 orchestration, lifecycle, MediaStore, DNG
- C++/NDK: JNI boundary and native buffer validation
- Rust: star detection, robust alignment, bilinear warping, sigma-clipped stacking, compact INT8 neural enhancement
- Camera API: Camera2 runtime capability inspection
- Build: Gradle + CMake + Cargo/cargo-ndk + GitHub Actions

## Important design rule

The app never advertises a camera feature merely because the UI contains a button. RAW, manual exposure/focus, flash, OIS/EIS and related controls are enabled only from `CameraCharacteristics`.

## GitHub Actions

`.github/workflows/build.yml` installs:

- JDK 17
- Gradle 8.9
- Android SDK 35 / Build Tools 35.0.0
- NDK 27.2.12479018
- CMake 3.22.1
- Rust 1.82.0
- Android Rust targets for arm64 and armv7
- cargo-ndk 4.1.2

The workflow tests the Rust core, builds Rust static libraries, links the C++ JNI library, builds the APK, and uploads `app-debug.apk`.

## Hardware profiles

Runtime RAM/CPU information selects a workload profile. This only controls processing limits and never pretends to identify a phone's camera capabilities.

## Astro pipeline

JPEG capture → bounded decode/downsample → MAD noise estimate → star candidates → hot-pixel rejection → sub-pixel centroids → RANSAC correspondences → similarity transform → bilinear warp → sigma-clipped stack → offline INT8 enhancement → JPEG.

## RAW

When `RAW_SENSOR` is available, Camera2 captures a real RAW image and `DngCreator` writes it with the corresponding camera characteristics and capture result. If RAW is unavailable, the RAW control is hidden.

## AI

The bundled neural core is a compact offline INT8 denoising/enhancement network. It is deliberately small enough for budget devices and never invents astronomical structures. It is not marketed as a generative model.

## Verification

See `STATUS.md` for the exact boundary between source-level verification and physical-device verification.
