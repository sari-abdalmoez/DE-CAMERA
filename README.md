# SARI Camera

SARI Camera is a real Android Camera2 computational-photography project for `com.sari.camera`, designed around bounded RAM/thermal usage on mid-range phones such as the Galaxy A06.

## Current camera pipeline

- Pixel-style dark camera UI with real vector controls.
- PHOTO / NIGHT / ASTRO / PRO / RAW / VIDEO modes.
- Back/front camera switching when both cameras are exposed by Camera2.
- Flash OFF / AUTO / ON when the current camera exposes a flash.
- Tap-to-focus and AE metering using Camera2 metering rectangles. The tap point also anchors high-zoom cropping.
- Zoom controls up to 20x. Hardware digital zoom is used first; beyond the reported hardware limit SARI applies a software crop before AI processing.
- `0–6x`: native OpenCV image processing.
- `6–13x`: short multi-frame burst + alignment/stacking + conservative Real-ESRGAN x2 tile inference.
- `13–20x`: short multi-frame burst + stronger NCNN/Vulkan Real-ESRGAN reconstruction, focused on the tapped ROI and intentionally lighter on the background.
- All high-zoom AI is tile-based with overlap so the full image is never expanded through the neural model at once.
- AI output is blended back toward the captured pixels. Low-evidence tiles and tiles with large AI-vs-source deviation are automatically damped.
- Face tiles receive reduced neural blending so facial geometry and real skin texture remain anchored to the capture. No face-replacement or generative beautification pipeline is used.
- Night and Astro use bounded burst counts, feature/RANSAC alignment and robust stacking. Astro supports a configurable 1–8 minute capture window and optional RAW/DNG capture.
- OpenCV performs denoising, chroma-noise control, local contrast/veil correction and conservative sharpening. Rust provides measured star detection.

## AI runtime

The project is hybrid rather than AI-only:

1. ONNX Runtime is the compatibility fallback for Real-ESRGAN x2 and NAFNet.
2. NCNN + Vulkan is the preferred strong-reconstruction path for `13–20x` when a Vulkan GPU is available. NCNN CPU remains a fallback when Vulkan is unavailable.
3. The AI model is never run on a full camera frame at once. Tiles are bounded by the device memory/thermal profile.
4. A tap at high zoom defines the high-priority ROI; outside that ROI the AI blend is reduced to save time and preserve the original scene.

The NCNN Real-ESRGAN weights are pinned to an exact third-party preconverted model commit and verified by Git blob SHA-1 in CI. The ONNX models are verified by SHA-256. The source archive intentionally omits the large model binaries; CI downloads the exact pinned files before the Android build.

## Fidelity rule

SARI is designed to improve measured photographic information, not to invent a new scene. At extreme zoom a neural model can still infer plausible texture. To reduce that risk, SARI uses multi-frame evidence, conservative blending, local evidence checks and an AI-vs-source deviation gate. When evidence is weak, the captured pixels dominate.

For distant faces, the pipeline protects facial geometry and keeps the original skin texture dominant. For cars, buildings and text, reconstruction is strongest only where the capture contains usable structure. It does not intentionally generate unrelated objects, stars or scene elements.

## Verification status

### VERIFIED BY SOURCE/STATIC CHECKS

- Project source layout and Android resource XML parse.
- GitHub Actions workflow syntax and shell script syntax pass static checks.
- Rust crate and tests are present.
- CMake contains an optional NCNN path and a working OpenCV/native fallback.
- AI model pins and hashes are recorded in `models/models.lock`.

### NOT VERIFIED IN THIS CLOUD WORKSPACE

- Full Android APK compilation. This workspace does not have the full Android SDK/NDK/Gradle toolchain needed to reproduce the GitHub Actions build locally.
- Physical Camera2 behavior on the Galaxy A06 SM-A065F.
- Actual Vulkan support, sustained thermals, RAM pressure and AI latency on that handset.

The authoritative hardware test is the APK produced by GitHub Actions on the target device.

## Build

`.github/workflows/build.yml` installs JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.31.6 and Rust 1.82, downloads OpenCV, downloads a pinned NCNN Vulkan runtime, verifies the ONNX and NCNN model files, builds Rust/C++/JNI, runs unit tests and uploads `sari-camera-debug.apk`.
