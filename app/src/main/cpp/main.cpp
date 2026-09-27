#include "core/App.h"
#include "utils/DELogger.h"
#include <jni.h>

using namespace decamera;

static void handleAppCmd(android_app* app, int32_t cmd) {
    App::from(app)->onCmd(cmd);
}

static int32_t handleInput(android_app* app, AInputEvent* ev) {
    return App::from(app)->onInput(ev);
}

extern "C" JNIEXPORT void JNICALL
Java_com_decamera_android_DEActivity_nativeOnPermissionsReady(JNIEnv*, jclass, jlong ptr, jboolean granted) {
    if (!ptr) return;
    auto* app = (App*)ptr;
    if (granted) {
        DELogger::instance().i("App", "camera permission granted");
        app->onCmd(1);
    } else {
        DELogger::instance().e("App", "camera permission denied");
    }
}

void android_main(android_app* state) {
    app_dummy();
    static App app(state);
    state->onAppCmd = handleAppCmd;
    state->onInputEvent = handleInput;
    app.run();
}
