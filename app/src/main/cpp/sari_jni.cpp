#include <jni.h>
#include <vector>
#include "sari_engine.h"
static void throwEx(JNIEnv* e,const char* msg){ jclass c=e->FindClass("java/lang/IllegalArgumentException"); if(c)e->ThrowNew(c,msg); }
extern "C" JNIEXPORT jstring JNICALL Java_com_sari_camera_NativeEngine_nativeVersion(JNIEnv* e,jobject){ return e->NewStringUTF(sari_native_version()); }
extern "C" JNIEXPORT jfloat JNICALL Java_com_sari_camera_NativeEngine_estimateBlur(JNIEnv* e,jobject,jobject buf,jint w,jint h,jint stride){ auto*p=(uint8_t*)e->GetDirectBufferAddress(buf); if(!p){throwEx(e,"Direct buffer required");return 0;} return sari_estimate_blur(p,w,h,stride); }
extern "C" JNIEXPORT jint JNICALL Java_com_sari_camera_NativeEngine_processTileRGBA(JNIEnv* e,jobject,jobject in,jobject out,jint w,jint h,jfloat s){auto*a=(uint8_t*)e->GetDirectBufferAddress(in);auto*b=(uint8_t*)e->GetDirectBufferAddress(out);if(!a||!b){throwEx(e,"Direct buffers required");return -1;}return sari_process_rgba(a,b,w,h,s);}
extern "C" JNIEXPORT jint JNICALL Java_com_sari_camera_NativeEngine_alignAndStackRGBA(JNIEnv* e,jobject,jobjectArray arr,jobject out,jint w,jint h,jfloat sigma){auto*b=(uint8_t*)e->GetDirectBufferAddress(out);if(!b)return -1;jsize n=e->GetArrayLength(arr);std::vector<const uint8_t*> p(n);for(jsize i=0;i<n;i++){auto o=e->GetObjectArrayElement(arr,i);p[i]=(const uint8_t*)e->GetDirectBufferAddress(o);e->DeleteLocalRef(o);}return sari_stack_rgba(p.data(),n,b,w,h,sigma);}
struct RustStar { float x,y,flux; unsigned pixels; };
extern "C" int sari_detect_stars(const uint8_t*, int, int, int, float, RustStar*, int);
extern "C" JNIEXPORT jint JNICALL Java_com_sari_camera_NativeEngine_detectStars(JNIEnv* e,jobject,jobject buf,jint w,jint h,jint stride,jfloat sigma){
 auto*p=(const uint8_t*)e->GetDirectBufferAddress(buf); if(!p){throwEx(e,"Direct buffer required");return -1;} std::vector<RustStar> stars(4096); return sari_detect_stars(p,w,h,stride,sigma,stars.data(),(int)stars.size());
}
