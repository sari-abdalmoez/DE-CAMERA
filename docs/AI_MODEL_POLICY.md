# AI model policy

SARI Camera uses ONNX Runtime for tile-level restoration/super-resolution. The pinned model is
`real_esrgan_x2.onnx`, a 2x Real-ESRGAN export documented as NCHW RGB float32 input/output and
licensed BSD-3-Clause by the model repository. Its published SHA-256 is recorded in
`models/models.lock`.

The cloud workspace used to prepare this ZIP had no external network access, so the model binary
was not downloaded into the ZIP. GitHub Actions downloads the exact pinned URL and verifies the
SHA-256 before the Android build. A missing or incompatible model fails closed; the application
never substitutes random tensors or a fake neural network.

Because neural super-resolution can synthesize plausible-looking high-frequency detail, SARI's
AI path must be conservative: it should be blended with the measured input, checked for structural
and color changes, and bypassed when validation fails. It is not a face-generation or scene-generation
feature.
