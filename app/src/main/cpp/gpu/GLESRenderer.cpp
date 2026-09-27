#include "gpu/GLESRenderer.h"
#include "utils/DELogger.h"
#include <cstring>
#include <vector>

namespace decamera {
static const char* TAG = "GLES";

static const char* VS = R"(attribute vec2 aPos; varying vec2 vUV;
    void main(){ vUV = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); })";

static const char* YUV_FS = R"(precision mediump float; varying vec2 vUV;
    uniform sampler2D tY; uniform sampler2D tU; uniform sampler2D tV;
    uniform vec2 crop; uniform float cscale;
    void main(){
        vec2 uv = (vUV - 0.5) / cscale + 0.5 + crop;
        float y = texture2D(tY, uv).r;
        float u = texture2D(tU, uv).r - 0.5;
        float v = texture2D(tV, uv).r - 0.5;
        y = 1.164383 * (y - 0.0625);
        gl_FragColor = vec4(y + 1.596027*v, y - 0.391762*u - 0.812968*v, y + 2.017232*u, 1.0);
    })";

static const char* BLEND_FS = R"(precision mediump float; varying vec2 vUV;
    uniform sampler2D tO;
    void main(){ vec4 c = texture2D(tO, vec2(vUV.x, 1.0 - vUV.y));
        gl_FragColor = vec4(c.rgb, 1.0); })";

GLuint GLESRenderer::makeShader(GLenum t, const char* s) {
    GLuint sh = glCreateShader(t);
    glShaderSource(sh, 1, &s, nullptr);
    glCompileShader(sh);
    GLint ok; glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[512]; glGetShaderInfoLog(sh, 512, nullptr, log); DELogger::instance().e(TAG, "shader: %s", log); }
    return sh;
}

GLuint GLESRenderer::makeProgram(const char* vs, const char* fs) {
    GLuint p = glCreateProgram();
    glAttachShader(p, makeShader(GL_VERTEX_SHADER, vs));
    glAttachShader(p, makeShader(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(p);
    return p;
}

bool GLESRenderer::init(ANativeWindow* window) {
    window_ = window;
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) return false;
    if (!eglInitialize(display_, nullptr, nullptr)) return false;
    const EGLint cfgAttrs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE };
    EGLConfig cfg; EGLint n;
    eglChooseConfig(display_, cfgAttrs, &cfg, 1, &n);
    if (!n) return false;
    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    context_ = eglCreateContext(display_, cfg, EGL_NO_CONTEXT, ctxAttrs);
    if (context_ == EGL_NO_CONTEXT) return false;
    ANativeWindow_setBuffersGeometry(window, 0, 0, WINDOW_FORMAT_RGBA_8888);
    surface_ = eglCreateWindowSurface(display_, cfg, window, nullptr);
    if (surface_ == EGL_NO_SURFACE) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
    const char* ext = (const char*)glGetString(GL_EXTENSIONS);
    gles30_ = ext && std::strstr(ext, "OES_EGL_image") != nullptr;
    yuvProg_ = makeProgram(VS, YUV_FS);
    blendProg_ = makeProgram(VS, BLEND_FS);
    glGenTextures(1, &yTex_); glGenTextures(1, &uTex_); glGenTextures(1, &vTex_);
    glGenTextures(1, &overlayTex_);
    EGLint w, h;
    eglQuerySurface(display_, surface_, EGL_WIDTH, &w);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &h);
    pw_ = w; ph_ = h;
    glViewport(0, 0, w, h);
    DELogger::instance().i(TAG, "EGL ready %dx%d", w, h);
    return true;
}

void GLESRenderer::drawQuad(GLuint prog, float cx, float cy, float scale) {
    static const GLfloat quad[] = { -1,-1, 1,-1, -1,1, 1,1 };
    GLint loc = glGetAttribLocation(prog, "aPos");
    glUseProgram(prog);
    glEnableVertexAttribArray(loc);
    glVertexAttribPointer(loc, 2, GL_FLOAT, GL_FALSE, 0, quad);
    glUniform2f(glGetUniformLocation(prog, "crop"), cx, cy);
    glUniform1f(glGetUniformLocation(prog, "cscale"), scale);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(loc);
}

void GLESRenderer::renderFrame(const ImageBuffer& yuv, const ImageBuffer& overlay,
                               float ccx, float ccy, float cscale) {
    if (!valid() || surface_ == EGL_NO_SURFACE) return;
    eglMakeCurrent(display_, surface_, surface_, context_);
    if (yuv.data.empty()) return;

    drawQuad(yuvProg_, ccx, ccy, cscale);
    eglSwapBuffers(display_, surface_);
}

void GLESRenderer::release() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
    }
    display_ = EGL_NO_DISPLAY;
    surface_ = EGL_NO_SURFACE;
    context_ = EGL_NO_CONTEXT;
}

} // namespace decamera
