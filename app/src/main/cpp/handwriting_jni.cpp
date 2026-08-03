#include <jni.h>
#include "handwriting_engine.h"

// ── The actual effect function (called via dynamic registration) ──
static void nativeApplyEffectsImpl(
    JNIEnv* env, jclass /*clazz*/,
    jobject byteBuffer,
    jint width, jint height, jint stride,
    jfloat inkFeathering, jfloat edgeRoughness,
    jboolean enableMistakes, jboolean performanceMode
) {
    uint32_t* pixels = static_cast<uint32_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (pixels == nullptr) return;

    HandwritingOptions opts;
    opts.ink_feathering   = inkFeathering;
    opts.edge_roughness   = edgeRoughness;
    opts.enable_mistakes  = enableMistakes;
    opts.performance_mode = performanceMode;
    opts.seed             = 0;   // not used for now

    applyHandwritingEffects(pixels, width, height, stride, opts);
}

// ── Method descriptor table for dynamic registration ────────────
static JNINativeMethod methods[] = {
    {
        const_cast<char*>("nativeApplyEffects"),                              // Kotlin method name
        const_cast<char*>("(Ljava/nio/ByteBuffer;IIIFFZZ)V"),                // JNI type signature
        reinterpret_cast<void*>(nativeApplyEffectsImpl)                     // C++ function pointer
    }
};

// ── Register the native methods when the library loads ───────────
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/pot/cil/hj/ui/view/HandwritingPaint");
    if (clazz == nullptr) return JNI_ERR;

    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}