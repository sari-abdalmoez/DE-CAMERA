#include <jni.h>
#include <mutex>

#ifdef SARI_HAS_NCNN
#include <net.h>
#include <gpu.h>
#endif

namespace {
#ifdef SARI_HAS_NCNN
std::mutex g_mutex;
ncnn::Net g_net;
bool g_loaded = false;
bool g_gpu_created = false;
bool g_use_vulkan = false;
int g_scale = 4;
#endif

void throwEx(JNIEnv* env, const char* msg) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, msg);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sari_camera_NativeEngine_ncnnVulkanAvailable(JNIEnv*, jobject) {
#ifdef SARI_HAS_NCNN
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_gpu_created) {
        if (ncnn::create_gpu_instance() != 0) return JNI_FALSE;
        g_gpu_created = true;
    }

    return ncnn::get_gpu_count() > 0 ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sari_camera_NativeEngine_ncnnInit(
        JNIEnv* env,
        jobject,
        jstring paramPath,
        jstring modelPath,
        jboolean useVulkan) {

#ifdef SARI_HAS_NCNN
    const char* p = env->GetStringUTFChars(paramPath, nullptr);
    const char* m = env->GetStringUTFChars(modelPath, nullptr);

    if (!p || !m) {
        if (p) env->ReleaseStringUTFChars(paramPath, p);
        if (m) env->ReleaseStringUTFChars(modelPath, m);
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_loaded) {
        env->ReleaseStringUTFChars(paramPath, p);
        env->ReleaseStringUTFChars(modelPath, m);
        return JNI_TRUE;
    }

    g_use_vulkan = useVulkan == JNI_TRUE && ncnn::get_gpu_count() > 0;

    if (g_use_vulkan && !g_gpu_created) {
        if (ncnn::create_gpu_instance() != 0) {
            g_use_vulkan = false;
        } else {
            g_gpu_created = true;
        }
    }

    g_net.clear();

    g_net.opt.use_vulkan_compute = g_use_vulkan;
    g_net.opt.num_threads = 2;
    g_net.opt.use_fp16_packed = true;
    g_net.opt.use_fp16_storage = true;
    g_net.opt.use_fp16_arithmetic = g_use_vulkan;

    if (g_net.load_param(p) != 0 || g_net.load_model(m) != 0) {
        env->ReleaseStringUTFChars(paramPath, p);
        env->ReleaseStringUTFChars(modelPath, m);

        g_net.clear();
        g_loaded = false;
        return JNI_FALSE;
    }

    g_scale = 4;
    g_loaded = true;

    env->ReleaseStringUTFChars(paramPath, p);
    env->ReleaseStringUTFChars(modelPath, m);

    return JNI_TRUE;
#else
    (void)env;
    (void)paramPath;
    (void)modelPath;
    (void)useVulkan;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sari_camera_NativeEngine_ncnnProcessRGBA(
        JNIEnv* env,
        jobject,
        jobject input,
        jobject output,
        jint width,
        jint height,
        jboolean useVulkan) {

#ifdef SARI_HAS_NCNN
    auto* src = static_cast<unsigned char*>(env->GetDirectBufferAddress(input));
    auto* dst = static_cast<unsigned char*>(env->GetDirectBufferAddress(output));

    if (!src || !dst || width <= 0 || height <= 0) {
        throwEx(env, "NCNN requires direct RGBA buffers");
        return -1;
    }

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_loaded) return -2;

    // Stock Real-ESRGAN NCNN model:
    // input blob = "data"
    // output blob = "output"
    ncnn::Mat in =
        ncnn::Mat::from_pixels(
            src,
            ncnn::Mat::PIXEL_RGBA2RGB,
            width,
            height
        );

    ncnn::Extractor ex = g_net.create_extractor();
    ex.set_light_mode(true);
    ex.input("data", in);

    ncnn::Mat out;

    if (ex.extract("output", out) != 0 || out.empty()) {
        return -3;
    }

    if (out.c < 3) return -4;

    // Modern NCNN exposes to_pixels() as void.
    out.to_pixels(
        dst,
        ncnn::Mat::PIXEL_RGB2RGBA
    );

    return useVulkan == JNI_TRUE ? 1 : 0;

#else
    (void)env;
    (void)input;
    (void)output;
    (void)width;
    (void)height;
    (void)useVulkan;
    return -10;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sari_camera_NativeEngine_ncnnScale(JNIEnv*, jobject) {
#ifdef SARI_HAS_NCNN
    return g_scale;
#else
    return 4;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_sari_camera_NativeEngine_ncnnRelease(JNIEnv*, jobject) {
#ifdef SARI_HAS_NCNN
    std::lock_guard<std::mutex> lock(g_mutex);

    g_net.clear();
    g_loaded = false;

    if (g_gpu_created) {
        ncnn::destroy_gpu_instance();
        g_gpu_created = false;
    }

    g_use_vulkan = false;
#endif
}
