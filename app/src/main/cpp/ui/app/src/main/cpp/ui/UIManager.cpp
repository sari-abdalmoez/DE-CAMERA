#include "ui/UIManager.h"
#include <cstdio>
#include <cmath>
#include <algorithm>
#include <cstring>

namespace decamera {

static const uint8_t FONT[38][5] = {
    {0x00,0x00,0x00,0x00,0x00}, {0x3E,0x51,0x49,0x45,0x3E}, {0x00,0x42,0x7F,0x40,0x00},
    {0x42,0x61,0x51,0x49,0x46}, {0x21,0x41,0x45,0x4B,0x31}, {0x18,0x14,0x12,0x7F,0x10},
    {0x27,0x45,0x45,0x45,0x39}, {0x3C,0x4A,0x49,0x49,0x30}, {0x01,0x71,0x09,0x05,0x03},
    {0x36,0x49,0x49,0x49,0x36}, {0x06,0x49,0x49,0x29,0x1E}, {0x00,0x36,0x36,0x00,0x00},
    {0x08,0x08,0x08,0x08,0x08}, {0x00,0x60,0x60,0x00,0x00}, {0x23,0x13,0x08,0x64,0x62},
    {0x00,0x14,0x08,0x14,0x00}, {0x7E,0x11,0x11,0x11,0x7E}, {0x7F,0x49,0x49,0x49,0x36},
    {0x3E,0x41,0x41,0x41,0x22}, {0x7F,0x41,0x41,0x22,0x1C}, {0x7F,0x49,0x49,0x49,0x41},
    {0x7F,0x09,0x09,0x09,0x01}, {0x3E,0x41,0x49,0x49,0x7A}, {0x7F,0x08,0x08,0x08,0x7F},
    {0x00,0x41,0x7F,0x41,0x00}, {0x20,0x40,0x41,0x3F,0x01}, {0x7F,0x08,0x14,0x22,0x41},
    {0x7F,0x40,0x40,0x40,0x40}, {0x7F,0x02,0x0C,0x02,0x7F}, {0x7F,0x04,0x08,0x10,0x7F},
    {0x3E,0x41,0x41,0x41,0x3E}, {0x7F,0x09,0x09,0x09,0x06}, {0x3E,0x41,0x51,0x21,0x5E},
    {0x7F,0x09,0x19,0x29,0x46}, {0x46,0x49,0x49,0x49,0x31}, {0x01,0x01,0x7F,0x01,0x01},
    {0x3F,0x40,0x40,0x40,0x3F}, {0x08,0x1C,0x3E,0x1C,0x08}
};

static int glyphIndex(char c) {
    if (c == ' ') return 0;
    if (c >= '0' && c <= '9') return 1 + (c - '0');
    if (c == ':') return 11; if (c == '-') return 12; if (c == '.') return 13;
    if (c == '%') return 14; if (c == 'x') return 15;
    if (c >= 'A' && c <= 'Z') return 16 + (c - 'A');
    if (c == '+') return 37;
    return 0;
}

void UIManager::resize(int w, int h) {
    overlay_.alloc(PixelFormat::RGBA8, w, h);
}

void UIManager::rect(int x0, int y0, int x1, int y1, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(overlay_.width, x1); y1 = std::min(overlay_.height, y1);
    for (int y = y0; y < y1; y++) {
        uint8_t* row = overlay_.data.data() + (size_t)y * overlay_.stride;
        for (int x = x0; x < x1; x++) {
            uint8_t* p = row + (size_t)x * 4;
            float fa = a / 255.f;
            p[0] = (uint8_t)(p[0] * (1 - fa) + r * fa);
            p[1] = (uint8_t)(p[1] * (1 - fa) + g * fa);
            p[2] = (uint8_t)(p[2] * (1 - fa) + b * fa);
            p[3] = 255;
        }
    }
}

void UIManager::circle(int cx, int cy, int rad, uint8_t r, uint8_t g, uint8_t b) {
    for (int y = -rad; y <= rad; y++) for (int x = -rad; x <= rad; x++)
        if (x * x + y * y <= rad * rad)
            rect(cx + x, cy + y, cx + x + 1, cy + y + 1, r, g, b, 255);
}

void UIManager::text(int x, int y, const std::string& s, uint8_t r, uint8_t g, uint8_t b, int sc) {
    int cx = x;
    for (char c : s) {
        const uint8_t* gl = FONT[glyphIndex(c)];
        for (int col = 0; col < 5; col++)
            for (int row = 0; row < 7; row++)
                if (gl[col] & (1 << row))
                    rect(cx + col * sc, y + row * sc, cx + (col + 1) * sc, y + (row + 1) * sc, r, g, b, 255);
        cx += 6 * sc;
    }
}

void UIManager::rebuildButtons(CamMode mode) {
    buttons_.clear();
    int W = overlay_.width, H = overlay_.height;
    float bw = W / 8.f, bh = 64;
    const char* top[4] = {"HDR", "AI", "RAW", "SETTINGS"};
    for (int i = 0; i < 4; i++)
        buttons_.push_back({top[i], 8.f + i * (bw + 4), 8, bw, bh, B_HDR + i});
    float mbw = W / 6.f;
    const char* modes[5] = {"NIGHT", "PHOTO", "ASTRO", "VIDEO", "PRO"};
    ButtonId ids[5] = {B_NIGHT, B_PHOTO, B_ASTRO, B_VIDEO, B_PRO};
    for (int i = 0; i < 5; i++)
        buttons_.push_back({modes[i], i * mbw, (float)H - 96, mbw, 56, ids[i]});
    buttons_.push_back({"SHUTTER", W / 2.f - 70, (float)H - 220, 140, 140, B_SHUTTER});
    if (mode == CamMode::Astro)
        buttons_.push_back({"START", W / 2.f - 100, (float)H * 0.55f, 200, 72, B_ASTRO_START});
}

bool UIManager::compose(CameraController& ctrl, float fps, const std::string& debugLine) {
    std::memset(overlay_.data.data(), 0, overlay_.data.size());
    text(20, overlay_.height - 150, debugLine, 217, 165, 20, 2);
    return true;
}

int UIManager::onTouch(float x, float y) {
    for (const auto& b : buttons_) {
        if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h)
            return b.id;
    }
    return -1;
}

} // namespace decamera
