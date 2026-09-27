#pragma once
#include "image/ImageBuffer.h"
#include "camera/CameraController.h"
#include <string>
#include <vector>
#include <functional>

namespace decamera {

class UIManager {
public:
    struct Button {
        std::string label; float x, y, w, h; int id;
    };
    enum ButtonId {
        B_HDR, B_AI, B_RAW, B_SETTINGS,
        B_NIGHT, B_PHOTO, B_ASTRO, B_VIDEO, B_PRO, B_SHUTTER,
        B_ASTRO_START, B_COUNT
    };

    void resize(int w, int h);
    bool compose(CameraController& ctrl, float fps, const std::string& debugLine);
    int onTouch(float x, float y);

    const ImageBuffer& overlay() const { return overlay_; }
    bool settingsOpen() const { return settingsOpen_; }
    void toggleSettings() { settingsOpen_ = !settingsOpen_; }

private:
    void rect(int x0, int y0, int x1, int y1, uint8_t r, uint8_t g, uint8_t b, uint8_t a);
    void text(int x, int y, const std::string& s, uint8_t r, uint8_t g, uint8_t b, int scale);
    void circle(int cx, int cy, int rad, uint8_t r, uint8_t g, uint8_t b);
    void rebuildButtons(CamMode mode);

    ImageBuffer overlay_;
    std::vector<Button> buttons_;
    bool settingsOpen_ = false;
    std::string lastKey_;
};

} // namespace decamera
