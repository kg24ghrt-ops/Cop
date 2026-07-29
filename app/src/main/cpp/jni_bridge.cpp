#include <jni.h>
#include "native_renderer.h"
#include <android/log.h>

#define LOG_TAG "NativeRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeCreate(JNIEnv* env, jobject thiz) {
    return reinterpret_cast<jlong>(new NativeRenderer());
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    delete reinterpret_cast<NativeRenderer*>(handle);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeSetDimensions(JNIEnv* env, jobject thiz, jlong handle,
                                                     jint w, jint h, jfloat top, jfloat spacing,
                                                     jfloat left, jfloat bottom, jint lines) {
    auto* renderer = reinterpret_cast<NativeRenderer*>(handle);
    renderer->setDimensions(w, h, top, spacing, left, bottom, lines);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeSetTextLine(JNIEnv* env, jobject thiz, jlong handle,
                                                   jint line, jstring text) {
    const char* chars = env->GetStringUTFChars(text, nullptr);
    auto* renderer = reinterpret_cast<NativeRenderer*>(handle);
    renderer->setTextLine(line, std::string(chars));
    env->ReleaseStringUTFChars(text, chars);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeClearText(JNIEnv* env, jobject thiz, jlong handle) {
    reinterpret_cast<NativeRenderer*>(handle)->clearText();
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeSetSelectedLine(JNIEnv* env, jobject thiz, jlong handle, jint line) {
    reinterpret_cast<NativeRenderer*>(handle)->setSelectedLine(line);
}

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeSetHumanize(JNIEnv* env, jobject thiz, jlong handle, jfloat factor) {
    reinterpret_cast<NativeRenderer*>(handle)->setHumanize(factor);
}

JNIEXPORT jint JNICALL
Java_com_pot_cil_hj_MyGLRenderer_nativeGenerateFrame(JNIEnv* env, jobject thiz, jlong handle,
                                                     jfloatArray contentMatrix,
                                                     jobject instanceBuffer,
                                                     jint maxInstances) {
    auto* renderer = reinterpret_cast<NativeRenderer*>(handle);
    jfloat* matrix = env->GetFloatArrayElements(contentMatrix, nullptr);
    float* buffer = (float*) env->GetDirectBufferAddress(instanceBuffer);
    if (!buffer) {
        LOGI("Direct buffer is null!");
        env->ReleaseFloatArrayElements(contentMatrix, matrix, JNI_ABORT);
        return 0;
    }
    int count = renderer->generateFrame(matrix, buffer, maxInstances);
    env->ReleaseFloatArrayElements(contentMatrix, matrix, JNI_ABORT);
    return count;
}

} // extern "C"