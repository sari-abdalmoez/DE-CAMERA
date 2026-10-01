# SARI Camera AI model policy

SARI Camera uses a hybrid mobile restoration stack. The camera never sends a full-resolution frame into a neural model in one allocation.

## 1. Verified models

The existing ONNX models remain SHA-256 pinned in `models/models.lock`:

- `real_esrgan_x2.onnx` — 2x general-image super resolution.
- `deblurring_nafnet_2025may.onnx` — conservative deblurring fallback.

For strong 13x–20x processing, SARI also bundles a pinned NCNN Real-ESRGAN x4 model. Its upstream repository and exact commit are locked, and the downloaded files are checked against their Git blob SHA-1 values before packaging.

## 2. Runtime routing

- 0x–6x: native OpenCV enhancement only.
- 6x–13x: tiled Real-ESRGAN x2 path, preferring measured multi-frame information when available.
- 13x–20x: tiled Real-ESRGAN x4 path through NCNN/Vulkan when supported; otherwise ONNX/CPU fallback.
- Tapped subject tiles receive stronger processing. Background tiles receive lighter processing.

## 3. Identity and hallucination control

AI output is always blended with the measured input. Faces are detected and receive reduced AI blending, not synthetic face generation. This keeps real geometry and skin texture present in the final image.

The feature is therefore restoration/super-resolution, not text-to-image generation. When the source contains insufficient evidence, the pipeline preserves the original pixels rather than inventing a new object.

## 4. Memory and thermal policy

Tile size, decoded image resolution, frame count, and worker count are selected from available RAM and thermal state. Only a bounded number of tiles are alive at once. NCNN is optional at runtime and CPU/ONNX fallbacks remain available.
