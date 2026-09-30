#include <jni.h>
#include <vector>
#include <cstdint>
#include <algorithm>
extern "C" {
size_t sari_detect_stars(const uint8_t*, size_t, uint32_t, uint32_t, float, float*, size_t);
int sari_align_stack(const uint8_t* const*, const size_t*, size_t, uint32_t, uint32_t, float, uint8_t*);
void sari_enhance(const uint8_t*, uint8_t*, uint32_t, uint32_t, uint32_t);
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_sari_camera_NativeBridge_detectStars(JNIEnv* env,jobject,jbyteArray gray,jint w,jint h,jfloat t){
    if(!gray||w<=0||h<=0)return env->NewFloatArray(0); jsize n=env->GetArrayLength(gray);
    std::vector<uint8_t>b(static_cast<size_t>(n)); env->GetByteArrayRegion(gray,0,n,reinterpret_cast<jbyte*>(b.data()));
    std::vector<float>out(4096); size_t count=sari_detect_stars(b.data(),b.size(),static_cast<uint32_t>(w),static_cast<uint32_t>(h),t,out.data(),2048);
    jfloatArray a=env->NewFloatArray(static_cast<jsize>(count*2)); if(a)env->SetFloatArrayRegion(a,0,static_cast<jsize>(count*2),out.data()); return a;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sari_camera_NativeBridge_alignAndStack(JNIEnv* env,jobject,jobjectArray frames,jint w,jint h,jfloat sigma){
    if(!frames||w<=0||h<=0)return nullptr; jsize n=env->GetArrayLength(frames); if(n<=0)return nullptr;
    const size_t pixels=static_cast<size_t>(w)*static_cast<size_t>(h); std::vector<std::vector<uint8_t>> bs(n); std::vector<const uint8_t*> ptrs(n); std::vector<size_t> lens(n);
    for(jsize i=0;i<n;i++){ auto a=(jbyteArray)env->GetObjectArrayElement(frames,i); if(!a)return nullptr; jsize l=env->GetArrayLength(a); bs[i].resize(static_cast<size_t>(l)); env->GetByteArrayRegion(a,0,l,reinterpret_cast<jbyte*>(bs[i].data())); ptrs[i]=bs[i].data(); lens[i]=static_cast<size_t>(l); env->DeleteLocalRef(a); if(l<static_cast<jsize>(pixels))return nullptr; }
    std::vector<uint8_t> out(pixels); if(sari_align_stack(ptrs.data(),lens.data(),static_cast<size_t>(n),static_cast<uint32_t>(w),static_cast<uint32_t>(h),sigma,out.data())!=0)return nullptr;
    jbyteArray r=env->NewByteArray(static_cast<jsize>(pixels)); if(r)env->SetByteArrayRegion(r,0,static_cast<jsize>(pixels),reinterpret_cast<const jbyte*>(out.data())); return r;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sari_camera_NativeBridge_enhance(JNIEnv* env,jobject,jbyteArray gray,jint w,jint h,jint profile){
    if(!gray||w<=0||h<=0)return nullptr; const size_t n=static_cast<size_t>(w)*static_cast<size_t>(h); if(env->GetArrayLength(gray)<static_cast<jsize>(n))return nullptr;
    std::vector<uint8_t>b(n),o(n); env->GetByteArrayRegion(gray,0,static_cast<jsize>(n),reinterpret_cast<jbyte*>(b.data())); sari_enhance(b.data(),o.data(),static_cast<uint32_t>(w),static_cast<uint32_t>(h),static_cast<uint32_t>(profile));
    jbyteArray r=env->NewByteArray(static_cast<jsize>(n)); if(r)env->SetByteArrayRegion(r,0,static_cast<jsize>(n),reinterpret_cast<const jbyte*>(o.data())); return r;
}
