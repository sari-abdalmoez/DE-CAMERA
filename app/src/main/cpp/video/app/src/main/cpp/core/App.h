#pragma once
#include "camera/CameraController.h"
#include "gpu/GLESRenderer.h"
#include "ui/UIManager.h"
#include "sensors/SensorManager.h"
#include "video/VideoEngine.h"
#include "eis/EISEngine.h"
#include "hardware/ThermalManager.h"
#include <android_native_app_glue.h>
#include <atomic>

namespace decamera {

class App {
public:
    explicit App(android_app* state);
    ~App();
    void run();
    void onCmd(int32_t cmd);
    int32_t onInput(AInputEvent* ev);

    static App* from(android_app* a) { return (App*)a->userData; }

private:
    void initWindow();
    void shutdown();
    void onShutter();
    void toggleVideo();
    std::string buildDebugLine();

    android_app* state_;
    GLESRenderer renderer_;
    UIManager ui_;
    CameraController ctrl_;
    SensorManager sensors_;
    EISEngine eis_;
    VideoEngine video_;
    ThermalManager thermalMonitor_;
    std::atomic<bool> running_{true};
    std::atomic<bool> hasWindow_{false};
    std::atomic<bool> cameraStarted_{false};
    ImageBufferPtr lastPreview_;
    std::mutex previewMu_;
    float fps_ = 0;
};

} // namespace decamera
