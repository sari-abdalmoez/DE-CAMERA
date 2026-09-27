#pragma once
#include "image/ImageBuffer.h"
#include <android/native_window.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <cstdint>

namespace decamera {

class GLESRenderer {
public:
    bool init(ANativeWindow* window);
    void release();
    bool valid() const { return display_ != EGL_NO_DISPLAY; }

    void renderFrame(const ImageBuffer& yuv, const ImageBuffer& overlay,
                     float cropCx, float cropCy, float cropScale);
    void renderToSurface(ANativeWindow* surface, int w, int h,
                         float cropCx, float cropCy, float cropScale);
    bool gles30() const { return gles30_; }

private:
    bool setupSurface(ANativeWindow* w, int wpx, int hpx, EGLSurface* out, bool makeCurrent);
    void drawQuad(GLuint prog, float cx, float cy, float scale);
    GLuint makeShader(GLenum type, const char* src);
    GLuint makeProgram(const char* vs, const char* fs);

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    ANativeWindow* window_ = nullptr;
    GLuint yTex_ = 0, uTex_ = 0, vTex_ = 0, overlayTex_ = 0;
    GLuint yuvProg_ = 0, blendProg_ = 0;
    int pw_ = 0, ph_ = 0;
    bool gles30_ = false;
};

} // namespace decamera
