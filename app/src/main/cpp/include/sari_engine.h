#pragma once
#include <cstdint>
#include <cstddef>

extern "C" {
const char* sari_native_version();
float sari_estimate_blur(const uint8_t* gray, int width, int height, int stride);
int sari_process_rgba(const uint8_t* input, uint8_t* output, int width, int height, float strength);
int sari_stack_rgba(const uint8_t* const* frames, int count, uint8_t* output, int width, int height, float sigma);
}
