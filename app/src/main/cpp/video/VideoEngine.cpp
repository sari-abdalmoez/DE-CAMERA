#include "video/VideoEngine.h"
#include "gpu/GLESRenderer.h"
#include "utils/DELogger.h"
#include <unistd.h>
#include <cstring>

namespace decamera {
static const char* TAG = "Video";

bool VideoEngine::encoderExists(const char* mime) {
    AMediaCodec* c = AMediaCodec_createEncoderByType(mime);
    if (!c) return false;
    AMediaCodec_delete(c);
    return true;
}

bool VideoEngine::pickConfig(int maxYuvW, int maxYuvH, bool support60, Config& out) {
    if (maxYuvW >= 2560 && maxYuvH >= 1440) { out.width = 2560; out.height = 1440; }
    else { out.width = 1920; out.height = 1080; }
    out.fps = support60 ? 60 : 30;
    out.hevc = encoderExists(AMEDIA_MIMETYPE_VIDEO_HEVC);
    return true;
}

bool VideoEngine::start(const Config& cfg, const std::string& outputPath, std::string* err) {
    if (recording_) return false;
    path_ = outputPath;
    fp_ = std::fopen(outputPath.c_str(), "wb+");
    if (!fp_) { if (err) *err = "cannot create output file"; return false; }

    const char* mime = cfg.hevc ? AMEDIA_MIMETYPE_VIDEO_HEVC : AMEDIA_MIMETYPE_VIDEO_AVC;
    enc_ = AMediaCodec_createEncoderByType(mime);
    if (!enc_) { if (err) *err = "encoder unavailable"; std::fclose(fp_); return false; }

    AMediaFormat* fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, cfg.width);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, cfg.height);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, cfg.bitrateMbps * 1000000);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, cfg.fps);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7F000789);
    media_status_t st = AMediaCodec_configure(enc_, fmt, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(fmt);
    if (st != AMEDIA_OK) { if (err) *err = "encoder configure failed"; goto fail; }
    if (AMediaCodec_createInputSurface(enc_, &inputSurface_) != AMEDIA_OK) {
        if (err) *err = "input surface failed"; goto fail;
    }
    if (AMediaCodec_start(enc_) != AMEDIA_OK) { if (err) *err = "encoder start failed"; goto fail; }

    muxer_ = AMediaMuxer_new(fileno(fp_), AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    if (!muxer_) { if (err) *err = "muxer failed"; goto fail; }

    w_ = cfg.width; h_ = cfg.height;
    recording_ = true;
    DELogger::instance().i(TAG, "recording started %dx%d@%d %s -> %s", w_, h_, cfg.fps, mime, outputPath.c_str());
    return true;
fail:
    stop();
    return false;
}

void VideoEngine::feedFrame(const ImageBuffer& yuv, float ccx, float ccy, float cscale) {
    if (!recording_) return;
    extern GLESRenderer* g_encoderRenderer;
    if (g_encoderRenderer && inputSurface_)
        g_encoderRenderer->renderToSurface(inputSurface_, w_, h_, ccx, ccy, cscale);

    AMediaCodecBufferInfo info;
    ssize_t idx;
    while ((idx = AMediaCodec_dequeueOutputBuffer(enc_, &info, 0)) >= 0) {
        if (!muxerStarted_ && (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) == 0) {
            AMediaFormat* f = AMediaCodec_getOutputFormat(enc_);
            track_ = AMediaMuxer_addTrack(muxer_, f);
            AMediaFormat_delete(f);
            AMediaMuxer_start(muxer_);
            muxerStarted_ = true;
        }
        if (muxerStarted_ && info.size > 0) {
            size_t sz;
            uint8_t* buf = AMediaCodec_getOutputBuffer(enc_, (size_t)idx, &sz);
            if (buf) AMediaMuxer_writeSampleData(muxer_, track_, buf, &info);
        }
        AMediaCodec_releaseOutputBuffer(enc_, (size_t)idx, false);
    }
}

void VideoEngine::stop() {
    if (!recording_ && !enc_) return;
    recording_ = false;
    if (enc_) {
        AMediaCodec_stop(enc_);
        AMediaCodec_delete(enc_);
        enc_ = nullptr;
    }
    if (muxer_) {
        if (muxerStarted_) AMediaMuxer_stop(muxer_);
        AMediaMuxer_delete(muxer_);
        muxer_ = nullptr;
    }
    if (fp_) { std::fclose(fp_); fp_ = nullptr; }
    inputSurface_ = nullptr;
    DELogger::instance().i(TAG, "recording stopped: %s", path_.c_str());
}

} // namespace decamera
