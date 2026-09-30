# SARI Neural Lite

SARI Camera contains an offline quantized neural enhancement core in `native/rust/src/lib.rs`.

- Architecture: 1 input channel → 8 hidden channels → 1 output channel
- Convolution: 3×3 / ReLU / 3×3 residual enhancement
- Weights: symmetric int8 per tensor with fixed scales
- Training source: synthetic noisy/smoothed image patches generated during project preparation
- Runtime: bounded-resolution pyramid, no external service, no network access
- Safety: the network is never used to create star detections or astronomical structures

This is intentionally a compact mobile model. It is not a replacement for a large flagship denoising network. The classical multi-frame stack remains the primary source of SNR improvement; the neural model is the final enhancement stage.
