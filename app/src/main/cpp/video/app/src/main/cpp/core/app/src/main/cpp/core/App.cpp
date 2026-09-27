#include "core/App.h"
#include "storage/StorageManager.h"
#include "utils/DELogger.h"
#include "utils/Timer.h"
#include <media/NdkImage.h>
#include <jni.h>
#include <cmath>
#include <chrono>
#include <unistd.h>
#include <cstring>

namespace decamera {
static const char* TAG = "App";

GLESRenderer* g_encoderRenderer = nullptr;

App::App(android_app* state) : state_(state) {
    state_->userData = this;
    StorageManager::instance().setJavaVM(state->activity->vm);
    JNIEnv* env;
    if (state->activity->vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        jclass cls = env->FindClass("com/decamera/android/DEActivity");
        jfieldID f = env->GetStaticFieldID(cls, "sAppPtr", "J");
        env->SetStaticLongField(cls, f, (jlong)this);
        state->activity->vm->DetachCurrentThread();
    }
    sensors_.start();
    ctrl_.setSensors(&sensors_);
}

App::~App() { shutdown(); }

void App::initWindow() {
    if (hasWindow_) return;
    if (!renderer_.init(state_->window)) {
        DELogger::instance().e(TAG, "EGL init failed");
        return;
    }
    g_encoderRenderer = &renderer_;
    ANativeWindow_acquire(state_->window);
    std::string err;
    if (!cameraStarted_ && ctrl_.initCamera(&err)) {
        cameraStarted_ = true;
    }
    hasWindow_ = true;
}

void App::shutdown() {
    if (video_.recording()) video_.stop();
    sensors_.stop();
    ctrl_.shutdown();
    renderer_.release();
    hasWindow_ = false;
}

std::string App::buildDebugLine() {
    char buf[256];
    auto c = ctrl_.caps();
    std::snprintf(buf, sizeof(buf),
        "FPS %.0f | ISO %d-%d | SENSOR %dx%d\nMAXYUV %dx%d | 1440P %s | 4K %s | 60FPS %s\n"
        "RAW %s | OIS %s | EIS %s | STATE %d",
        fps_, c.isoMin, c.isoMax, c.activeArrayW, c.activeArrayH,
        c.maxYuvW, c.maxYuvH, c.supports1440p?"Y":"N", c.supports4k?"Y":"N", c.supports60fps?"Y":"N",
        c.rawSupported?"Y":"N", c.ois?"Y":"N", c.eisHardware?"HW":"SW", (int)ctrl_.state());
    return buf;
}

void App::onShutter() {
    CamMode m = ctrl_.mode();
    if (m == CamMode::Astro) {
        ctrl_.setAstroParams(800, 4.0, 20);
        ctrl_.capture();
    } else if (m == CamMode::Video) {
        toggleVideo();
    } else {
        ctrl_.capture();
    }
}

void App::toggleVideo() {
    if (video_.recording()) {
        video_.stop();
        StorageManager::instance().scanFile(video_.path());
        return;
    }
    auto c = ctrl_.caps();
    VideoEngine::Config cfg;
    VideoEngine::pickConfig(c.maxYuvW, c.maxYuvH, c.supports60fps, cfg);
    std::string dir = StorageManager::instance().externalMoviesDir();
    if (dir.empty()) dir = "/sdcard/Android/data/com.decamera.android/files/Movies";
    std::string path = dir + "/DE_CAMERA_" + std::to_string(
        std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count()) + ".mp4";
    std::string err;
    if (!video_.start(cfg, path, &err)) {
        DELogger::instance().e(TAG, "video start failed: %s", err.c_str());
    }
}

void App::run() {
    while (running_) {
        int events;
        android_poll_source* src;
        while (ALooper_pollOnce(0, nullptr, &events, (void**)&src) >= 0) {
            if (src) src->process(state_, src);
            if (state_->destroyRequested) { running_ = false; }
        }
        if (!hasWindow_) { usleep(10000); continue; }

        ImageBufferPtr frame;
        {
            std::lock_guard<std::mutex> lk(previewMu_);
            frame = lastPreview_;
        }
        if (frame) {
            auto gyro = sensors_.gyroSince(sensors_.nowSec() - 0.1);
            eis_.onGyro(gyro);
            float cx, cy, sc;
            eis_.getCrop(cx, cy, sc);
            if (video_.recording())
                video_.feedFrame(*frame, cx, cy, sc);
            if (sensors_.lastTempC() > -100) thermalMonitor_.onDeviceCelsius(sensors_.lastTempC());
            static int fc = 0; static Timer ft;
            fc++;
            if (ft.elapsedMs() > 500) { fps_ = fc * 1000.f / ft.elapsedMs(); fc = 0; ft.reset(); }
            ui_.compose(ctrl_, fps_, buildDebugLine());
            renderer_.renderFrame(*frame, ui_.overlay(), cx, cy, sc);
        } else {
            usleep(5000);
        }
    }
}

void App::onCmd(int32_t cmd) {
    switch (cmd) {
        case APP_CMD_INIT_WINDOW: initWindow(); break;
        case APP_CMD_TERM_WINDOW: shutdown(); break;
    }
}

int32_t App::onInput(AInputEvent* ev) {
    return 0;
}

} // namespace decamera
