# SARI Camera

Real Android computational-photography camera project for `com.sari.camera`.

## What is actually implemented

- Camera2 runtime capability detection; no Galaxy A06 camera layout is hard-coded.
- Real JPEG capture + MediaStore saving under `Pictures/SARI Camera/`.
- Real RAW_SENSOR capture to DNG under `Pictures/SARI Camera/RAW/` when the camera advertises RAW.
- Real MediaRecorder H.264 video path under `Movies/SARI Camera/`.
- Night burst capture with bounded frame count, feature/RANSAC alignment and robust per-pixel median/MAD rejection.
- Astro burst mode with manual exposure where Camera2 exposes the required ranges, star candidate detection in Rust, ORB feature matching + RANSAC affine alignment in OpenCV, robust stacking, and conservative enhancement.
- OpenCV 4.10 native pipeline: non-local-means denoising, chroma-noise control, CLAHE/local-contrast enhancement, low-frequency haze/veil correction, blur estimation and conservative sharpening.
- ONNX Runtime mobile inference with two pinned models: Real-ESRGAN x2 for conservative super-resolution and OpenCV's NAFNet ONNX model for deblurring. CI verifies SHA-256 before packaging.
- Tile processing with overlap and weighted blending; processing uses bounded temporary buffers rather than unlimited full-resolution float copies.
- Runtime memory/thermal profile selection.
- Face-region protection: neural restoration is reduced or skipped in protected face tiles; there is no generative face beautification.
- Output safety gates and conservative blending keep neural output anchored to the captured image.
- Rust star detection tests and Android smoke tests.

## Verification status

### VERIFIED BY SOURCE/STATIC CHECKS

- Required Android/Kotlin/C++/Rust/project files exist.
- XML parses successfully.
- GitHub Actions YAML parses successfully.
- Shell model-verification script passes `bash -n` syntax checking.
- C++ source brace/preprocessor-level structural checks completed.
- Rust unit tests are included in the project.
- OpenCV 4.10.0 Android SDK SHA-256 is pinned to the value published for the release.
- Real-ESRGAN x2 model URL, input/output contract and SHA-256 match the SceneWorks model card.
- NAFNet model URL and SHA-256 match the OpenCV-hosted model. OpenCV's model documentation identifies NAFNet as the deblurring model.

### NOT VERIFIED IN THIS CLOUD WORKSPACE

- Full Android APK compilation: Android SDK/NDK/Gradle distribution were not installed locally.
- Physical Camera2 behavior on a Samsung Galaxy A06 SM-A065F.
- Actual RAW_SENSOR availability on that handset.
- Actual sustained thermal/RAM behavior on that handset.
- Actual AI model inference latency on that handset.

### HARDWARE VERIFICATION REQUIRED

The first physical test should verify preview startup, photo saving, RAW/DNG, video, Night stacking, Astro stacking, long exposure limits, and thermal throttling on the target phone.

## Important fidelity limitation

Real-ESRGAN is a super-resolution neural model. Even when blended conservatively, neural super-resolution can infer plausible texture. SARI therefore anchors it to a bicubic reconstruction and never uses it as a generative scene/face replacement system. Astro mode does not synthesize stars: Rust star detection only reports measured bright components, while alignment/stacking operate on captured frames.

## Build

`.github/workflows/build.yml` installs pinned Android/NDK/CMake/Rust toolchains, downloads OpenCV 4.10.0, downloads and SHA-256 verifies both AI models, builds Rust, builds C++/JNI and runs Android unit tests before uploading `sari-camera-debug.apk`.

The offline source ZIP intentionally does not contain the 90+ MB NAFNet and large super-resolution model binaries. CI obtains the exact pinned files and fails closed if their SHA-256 values do not match.


## Latest CI hardening

- GitHub Actions now uses Node 24-compatible action majors: checkout v5, setup-java v6, setup-android v4, setup-gradle v6.
- Android SDK installation is explicit and prints toolchain diagnostics.
- Rust astro code is linked as a required native dependency instead of using a weak fallback stub.
- Tile processing no longer allocates full-resolution accumulation arrays; only the final output bitmap is full resolution.
- Pre-Android-10 storage permission is requested only where required.
- AI strength selection now uses the actual `AiEngine` availability flags.

The NAFNet model is the 91.7 MB ONNX file published by OpenCV with SHA-256 `07263f416febecce10193dd648e950b22e397cf521eedab1a114ef77b2bc9587`. The Real-ESRGAN 2x ONNX model is published by SceneWorks with SHA-256 `7115ba92e8a1bfa63d68558ef006ef3d91273a068d321b1439f8bb1c9179002c`.


## CI/build fixes applied (2026-10-01)
- Workflow uses Node 24-compatible action releases (`checkout@v5`, `setup-java@v6`, `setup-android@v4`, `setup-gradle@v6`).
- Android SDK packages are installed explicitly; command-line tools version is pinned.
- The archive is intended to be extracted with its contents at repository root.
- Fixed Android < 29 thermal API access.
- Fixed tile face-intersection call for Android/Kotlin compilation.
- Kept MediaRecorder output ParcelFileDescriptor alive until recorder release.
- AI model/session initialization is moved off the main UI thread.
