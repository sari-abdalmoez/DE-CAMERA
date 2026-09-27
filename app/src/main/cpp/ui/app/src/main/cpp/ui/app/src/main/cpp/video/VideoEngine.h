#pragma once
#include "image/ImageBuffer.h"
#include "eis/EISEngine.h"
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>
#include <string>
#include <atomic>
#include <cstdio>

struct ANativeWindow;

namespace decamera {

class VideoEngine {
public:
    struct Config {
        int width = 1920, height = 1080;
        int fps = 30;
        int bitrateMbps = 20;
        bool hevc = false;
        EISProfile eis = EISProfile::Standard;
    };

    bool start(const Config& cfg, const std::string& outputPath, std::string* err);
    void feedFrame(const ImageBuffer& yuv, float cropCx, float cropCy, float cropScale);
    void stop();
    bool recording() const { return recording_; }
    std::string path() const { return path_; }

    static bool pickConfig(int maxYuvW, int maxYuvH, bool support60, Config& out);
    static bool encoderExists(const char* mime);

private:
    AMediaCodec* enc_ = nullptr;
    AMediaMuxer* muxer_ = nullptr;
    ANativeWindow* inputSurface_ = nullptr;
    FILE* fp_ = nullptr;
    std::string path_;
    std::atomic<bool> recording_{false};
    int track_ = -1;
    bool muxerStarted_ = false;
    int w_ = 0, h_ = 0;
};

} // namespace decamera
