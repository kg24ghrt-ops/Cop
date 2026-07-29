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
    if (!renderer->init()) {
        delete renderer;
        return 0;
    }
    return reinterpret_cast<jlong>(renderer);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeDestroyRenderer(JNIEnv* env, jobject thiz, jlong ptr) {
    delete getRenderer(ptr);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeResize(JNIEnv* env, jobject thiz, jlong ptr, jint w, jint h) {
    getRenderer(ptr)->resize(w, h);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeSetPaperParams(JNIEnv* env, jobject thiz, jlong ptr,
                                                      jfloat top, jfloat spacing,
                                                      jfloat left, jfloat bottom,
                                                      jint lines) {
    getRenderer(ptr)->setPaperParams(top, spacing, left, bottom, lines);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeSetTextLine(JNIEnv* env, jobject thiz, jlong ptr,
                                                   jint line, jstring text) {
    const char* chars = env->GetStringUTFChars(text, nullptr);
    getRenderer(ptr)->setTextOnLine(line, std::string(chars));
    env->ReleaseStringUTFChars(text, chars);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeClearText(JNIEnv* env, jobject thiz, jlong ptr) {
    getRenderer(ptr)->clearText();
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeSetSelectedLine(JNIEnv* env, jobject thiz, jlong ptr, jint line) {
    getRenderer(ptr)->setSelectedLine(line);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeSetPan(JNIEnv* env, jobject thiz, jlong ptr, jfloat dx, jfloat dy) {
    getRenderer(ptr)->setPan(dx, dy);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeSetZoom(JNIEnv* env, jobject thiz, jlong ptr,
                                               jfloat scale, jfloat focusX, jfloat focusY) {
    getRenderer(ptr)->setZoom(scale, focusX, focusY);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeResetTransform(JNIEnv* env, jobject thiz, jlong ptr) {
    getRenderer(ptr)->resetTransform();
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeDrawFrame(JNIEnv* env, jobject thiz, jlong ptr) {
    getRenderer(ptr)->drawFrame();
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeCreateFontAtlas(JNIEnv* env, jobject thiz, jlong ptr,
                                                       jint width, jint height, jbyteArray pixels) {
    jbyte* data = env->GetByteArrayElements(pixels, nullptr);
    getRenderer(ptr)->createFontAtlas(width, height,
        reinterpret_cast<const uint8_t*>(data));
    env->ReleaseByteArrayElements(pixels, data, JNI_ABORT);
}

} // extern "C"