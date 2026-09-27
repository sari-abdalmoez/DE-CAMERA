#include "storage/StorageManager.h"
#include <jni.h>
#include <android/log.h>
#include <cstring>

namespace decamera {

StorageManager& StorageManager::instance() {
    static StorageManager s;
    return s;
}

struct JniGuard {
    JniGuard(JavaVM* vm) : vm_(vm) {
        if (vm_) vm_->AttachCurrentThread(&env_, nullptr);
    }
    ~JniGuard() { if (vm_) vm_->DetachCurrentThread(); }
    JavaVM* vm_; JNIEnv* env_ = nullptr;
};

std::string StorageManager::saveJpeg(const ImageBuffer& rgba, int quality) {
    JniGuard g(vm_);
    if (!g.env_) return "";
    JNIEnv* env = g.env_;
    jclass cls = env->FindClass("com/decamera/android/DEActivity");
    if (!cls) return "";
    jmethodID mid = env->GetStaticMethodID(cls, "saveJpeg", "([BIII)Ljava/lang/String;");
    if (!mid) return "";
    jbyteArray arr = env->NewByteArray((jsize)rgba.data.size());
    env->SetByteArrayRegion(arr, 0, (jsize)rgba.data.size(), (const jbyte*)rgba.data.data());
    jstring uri = (jstring)env->CallStaticObjectMethod(cls, mid, arr, rgba.width, rgba.height, quality);
    env->DeleteLocalRef(arr);
    std::string out;
    if (uri) {
        const char* s = env->GetStringUTFChars(uri, nullptr);
        out = s; env->ReleaseStringUTFChars(uri, s);
        env->DeleteLocalRef(uri);
    }
    env->DeleteLocalRef(cls);
    return out;
}

std::string StorageManager::saveDng(const std::vector<uint8_t>& fileWithLen) {
    JniGuard g(vm_);
    if (!g.env_) return "";
    JNIEnv* env = g.env_;
    jclass cls = env->FindClass("com/decamera/android/DEActivity");
    if (!cls) return "";
    jmethodID mid = env->GetStaticMethodID(cls, "saveDng", "([B)Ljava/lang/String;");
    if (!mid) return "";
    jbyteArray arr = env->NewByteArray((jsize)fileWithLen.size());
    env->SetByteArrayRegion(arr, 0, (jsize)fileWithLen.size(), (const jbyte*)fileWithLen.data());
    jstring uri = (jstring)env->CallStaticObjectMethod(cls, mid, arr);
    env->DeleteLocalRef(arr);
    std::string out;
    if (uri) {
        const char* s = env->GetStringUTFChars(uri, nullptr);
        out = s; env->ReleaseStringUTFChars(uri, s);
        env->DeleteLocalRef(uri);
    }
    env->DeleteLocalRef(cls);
    return out;
}

std::string StorageManager::externalMoviesDir() {
    JniGuard g(vm_);
    if (!g.env_) return "";
    JNIEnv* env = g.env_;
    jclass cls = env->FindClass("com/decamera/android/DEActivity");
    jfieldID f = env->GetStaticFieldID(cls, "sInstance", "Lcom/decamera/android/DEActivity;");
    jobject act = env->GetStaticObjectField(cls, f);
    if (!act) return "";
    jmethodID m = env->GetMethodID(cls, "getExternalFilesDir",
        "(Ljava/lang/String;)Ljava/io/File;");
    jstring type = env->NewStringUTF("Movies");
    jobject file = env->CallObjectMethod(act, m, type);
    env->DeleteLocalRef(type);
    std::string out;
    if (file) {
        jclass fcls = env->GetObjectClass(file);
        jmethodID ap = env->GetMethodID(fcls, "getAbsolutePath", "()Ljava/lang/String;");
        jstring p = (jstring)env->CallObjectMethod(file, ap);
        const char* s = env->GetStringUTFChars(p, nullptr);
        out = s; env->ReleaseStringUTFChars(p, s);
    }
    return out;
}

void StorageManager::scanFile(const std::string& path) {
    JniGuard g(vm_);
    if (!g.env_) return;
    JNIEnv* env = g.env_;
    jclass cls = env->FindClass("android/media/MediaScannerConnection");
    jclass ctxCls = env->FindClass("com/decamera/android/DEActivity");
    jfieldID f = env->GetStaticFieldID(ctxCls, "sInstance", "Lcom/decamera/android/DEActivity;");
    jobject act = env->GetStaticObjectField(ctxCls, f);
    jmethodID m = env->GetStaticMethodID(cls, "scanFile",
        "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Landroid/media/MediaScannerConnection$OnScanCompletedListener;)V");
    jstring p = env->NewStringUTF(path.c_str());
    jstring mime = env->NewStringUTF("video/mp4");
    env->CallStaticVoidMethod(cls, m, act, p, mime, nullptr);
}

} // namespace decamera
