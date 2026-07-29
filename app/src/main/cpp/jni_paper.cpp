#include <jni.h>
#include <android/log.h>
#include "PaperRenderer.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "JNI", __VA_ARGS__)

static PaperRenderer* getRenderer(jlong ptr) {
    return reinterpret_cast<PaperRenderer*>(ptr);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeCreateRenderer(JNIEnv* env, jobject thiz) {
    PaperRenderer* renderer = new PaperRenderer();
    return reinterpret_cast<jlong>(renderer);
}

JNIEXPORT jboolean JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeInitRenderer(JNIEnv* env, jobject thiz, jlong ptr) {
    PaperRenderer* renderer = getRenderer(ptr);
    if (!renderer) return JNI_FALSE;
    return renderer->init() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeDestroyRenderer(JNIEnv* env, jobject thiz, jlong ptr) {
    delete getRenderer(ptr);
}

// ... all other JNI functions (resize, setPaperParams, setTextLine, etc.) remain unchanged.
// They are the same as in the previous working version.

} // extern "C"