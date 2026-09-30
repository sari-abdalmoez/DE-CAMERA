# SARI Camera — hardening status

## Implemented in this revision

### Stability and lifecycle
- Camera2 state errors are converted to user-facing status messages; device/service/busy/disconnect errors trigger bounded recovery attempts.
- Camera resources are closed on pause, surface destruction, camera switch, and activity destruction.
- Burst processing uses a bounded executor sized from the runtime hardware profile.
- Thermal status `SEVERE+` pauses new capture/processing work and clears an in-flight burst safely.
- Storage writes use `MediaStore`, `IS_PENDING`, flush/close semantics, and rollback on failure.
- Image processing validates dimensions and malformed frames before entering native code.
- Native FFI validates null pointers, lengths, dimensions, and output capacity before reading/writing memory.

### Hardware adaptation
`CameraCharacteristics` is queried at runtime for:
- RAW capability and RAW sizes
- manual sensor/post-processing support
- ISO and exposure ranges
- manual focus availability
- OIS/EIS
- flash
- focal lengths and physical sensor size
- active array and maximum digital zoom
- JPEG/YUV/RAW output sizes
- FPS ranges and hardware level

Workload profiles:
- `LOW_END`: max 6-frame burst, <=1.5 MP processing, 2 native workers
- `MID_RANGE`: max 10-frame burst, <=3 MP processing, 3 native workers
- `FLAGSHIP`: max 20-frame burst, <=6 MP processing, 4 native workers

These are workload limits, not claims about camera hardware.

### RAW / DNG
- RAW UI is shown only when Camera2 exposes `RAW_SENSOR` capability.
- Capture requests add the actual RAW output surface only on RAW-capable cameras.
- DNG writing uses `DngCreator` with the actual `CameraCharacteristics` and `CaptureResult` from the RAW capture.
- JPEG data is never converted into a fake RAW file.

### Astro / Night mathematics
- JPEGs are decoded into actual grayscale pixels before native processing. The previous implementation incorrectly passed compressed JPEG bytes to the native stacker; that defect is fixed.
- Star detection uses robust background estimation with median/MAD noise estimation, local maxima detection, hot-pixel rejection, and weighted sub-pixel centroids.
- Alignment uses nearest-star correspondences, deterministic RANSAC translation hypotheses, robust inlier selection, and least-squares similarity transform (translation + rotation + bounded scale).
- Frames are warped with bilinear interpolation.
- Stacking uses trimmed/sigma-clipped per-pixel averaging to reject outliers such as noise spikes and transient trails.
- Frame count is bounded by the runtime profile; invalid/misaligned frames are rejected.

### On-device AI
- The project contains a real offline INT8 neural enhancement core in Rust: a compact 1→8→1 convolutional residual denoiser trained on synthetic noisy image patches and quantized per tensor.
- Inference runs on a bounded-resolution pyramid (`<=450k/600k/750k` AI pixels by profile), then bilinearly maps the result back to the output size. This prevents a full 12 MP tensor allocation.
- The AI path is offline and deterministic. It does not generate or add stars.
- Classical processing remains the guaranteed fallback path if native enhancement fails.
- This is intentionally a lightweight model, not a claim of flagship-class generative restoration.

## Not physically verified here
The execution environment used to prepare this package does not contain a physical Samsung Galaxy A06 (SM-A065F), Android camera hardware, or the Android SDK/NDK toolchain. Therefore these are **HARDWARE VERIFICATION REQUIRED**:

- Actual SM-A065F camera IDs, RAW support and exact manual-control ranges
- DNG output on the phone's specific sensor
- Night/Astro quality under real sky conditions
- Thermal throttling behavior under repeated long captures
- OIS/EIS behavior
- Video encoder behavior on every supported device
- Gallery behavior on vendor-specific Android builds

No physical-device verification is claimed.

## Build verification limitation
The supplied execution environment does not have `cargo`, the Android SDK, NDK, or Gradle installed locally. GitHub Actions is configured to install pinned toolchains and build the Android artifact. The source package has been statically inspected, but an APK build cannot honestly be marked locally verified from this environment.
