#include <jni.h>
#include "handwriting_engine.h"

extern "C" {

JNIEXPORT void JNICALL
Java_com_pot_cil_hj_ui_view_HandwritingPaint_00024Companion_nativeApplyEffects(
    JNIEnv *env,
    jclass /* clazz */,
    jobject byteBuffer,
    jint width,
    jint height,
    jint stride,
    jobject options
) {
    uint32_t *pixels = static_cast<uint32_t *>(env->GetDirectBufferAddress(byteBuffer));
    if (pixels == nullptr) return;

    jclass optionsClass = env->GetObjectClass(options);
    if (optionsClass == nullptr) return;

    auto getFloatField = [&](const char *name) -> float {
        jfieldID field = env->GetFieldID(optionsClass, name, "F");
        return field ? env->GetFloatField(options, field) : 0.0f;
    };
    auto getBooleanField = [&](const char *name) -> bool {
        jfieldID field = env->GetFieldID(optionsClass, name, "Z");
        return field ? env->GetBooleanField(options, field) : false;
    };

    HandwritingOptions nativeOpts;
    nativeOpts.ink_feathering    = getFloatField("inkFeathering");
    nativeOpts.edge_roughness    = getFloatField("edgeRoughness");
    nativeOpts.micro_tremor      = 0.0f;   // not yet used
    nativeOpts.shakiness         = 0.0f;
    nativeOpts.ink_pool_chance   = 0.0f;
    nativeOpts.enable_mistakes   = getBooleanField("enableMistakes");
    nativeOpts.performance_mode  = getBooleanField("performanceMode");
    nativeOpts.seed              = 0;

    env->DeleteLocalRef(optionsClass);

    applyHandwritingEffects(pixels, width, height, stride, nativeOpts);
}

} // extern "C"