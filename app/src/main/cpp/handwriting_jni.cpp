#include <jni.h>
#include "handwriting_engine.h"

extern "C" {

// The Kotlin companion object method is:
//   @JvmStatic private external fun nativeApplyEffects(
//       pixels: ByteBuffer, width: Int, height: Int,
//       stride: Int, options: NativeOptions
//   )
// This becomes a static method of HandwritingPaint.Companion.
// Its JNI name is mangled as:
// Java_com_pot_cil_hj_ui_view_HandwritingPaint_00024Companion_nativeApplyEffects
JNIEXPORT void JNICALL
Java_com_pot_cil_hj_ui_view_HandwritingPaint_00024Companion_nativeApplyEffects(
    JNIEnv* env,
    jclass /* clazz */,
    jobject byteBuffer,
    jint width,
    jint height,
    jint stride,
    jobject options
) {
    // 1. Get direct pointer to the pixel buffer
    uint32_t* pixels = static_cast<uint32_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (pixels == nullptr) return;   // buffer not direct – should not happen

    // 2. Read the NativeOptions fields via JNI
    jclass optionsClass = env->GetObjectClass(options);
    if (optionsClass == nullptr) return;

    auto getFloatField = [&](const char* name) -> float {
        jfieldID field = env->GetFieldID(optionsClass, name, "F");
        return field ? env->GetFloatField(options, field) : 0.0f;
    };
    auto getBooleanField = [&](const char* name) -> bool {
        jfieldID field = env->GetFieldID(optionsClass, name, "Z");
        return field ? env->GetBooleanField(options, field) : false;
    };

    HandwritingOptions nativeOpts;
    nativeOpts.ink_feathering    = getFloatField("inkFeathering");
    nativeOpts.edge_roughness    = getFloatField("edgeRoughness");
    // microTremor / shakiness / inkPoolChance can be used in future passes
    // For now we only use the two passed to the engine.
    nativeOpts.enable_mistakes   = getBooleanField("enableMistakes");
    nativeOpts.performance_mode  = getBooleanField("performanceMode");
    nativeOpts.seed              = 0;   // we don't have seed field yet; will use 0

    env->DeleteLocalRef(optionsClass);

    // 3. Call the engine
    applyHandwritingEffects(pixels, width, height, stride, nativeOpts);
}

} // extern "C"