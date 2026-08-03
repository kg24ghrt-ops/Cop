#include <jni.h>
#include <vector>
#include "handwriting_engine.h"
#include "handwriting_shaper.h"

// ── Native effect implementation (ink bleed + edge noise) ───────
static void nativeApplyEffectsImpl(
    JNIEnv* env,
    jclass /*clazz*/,
    jobject byteBuffer,
    jint width,
    jint height,
    jint stride,
    jfloat inkFeathering,
    jfloat edgeRoughness,
    jboolean enableMistakes,
    jboolean performanceMode
) {
    uint32_t* pixels = static_cast<uint32_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (pixels == nullptr) return;

    HandwritingOptions opts;
    opts.ink_feathering   = inkFeathering;
    opts.edge_roughness   = edgeRoughness;
    opts.enable_mistakes  = enableMistakes;
    opts.performance_mode = performanceMode;
    opts.seed             = 0;   // not used currently

    applyHandwritingEffects(pixels, width, height, stride, opts);
}

// ── Native shaping implementation ───────────────────────────────
static jfloatArray nativeComputeShaping(
    JNIEnv* env,
    jclass /*clazz*/,
    jobjectArray clusterStrings,
    jfloatArray clusterWidths,
    jint clusterCount,
    jfloat baseTextSize,
    jfloat shakiness,
    jfloat microTremor,
    jfloat pressureVariation,
    jfloat sizeVariation,
    jfloat skipChance,
    jfloat skipWidth,
    jboolean velocityPressure,
    jint seed
) {
    // Obtain array elements
    jfloat* widths = env->GetFloatArrayElements(clusterWidths, nullptr);
    if (!widths) return nullptr;

    // Convert Java string array to C strings
    std::vector<const char*> cStrings(clusterCount);
    for (jint i = 0; i < clusterCount; ++i) {
        jstring js = (jstring)env->GetObjectArrayElement(clusterStrings, i);
        cStrings[i] = env->GetStringUTFChars(js, nullptr);
    }

    // Fill options
    HandwritingShaperOptions opts;
    opts.shakiness          = shakiness;
    opts.microTremor        = microTremor;
    opts.pressureVariation  = pressureVariation;
    opts.sizeVariation      = sizeVariation;
    opts.skipChance         = skipChance;
    opts.skipWidth          = skipWidth;
    opts.velocityPressure   = velocityPressure;
    opts.seed               = static_cast<uint32_t>(seed);

    // Compute transforms
    std::vector<ClusterTransform> transforms(clusterCount);
    computeClusterTransforms(
        cStrings.data(), widths, clusterCount,
        baseTextSize, &opts, transforms.data()
    );

    // Release resources
    for (jint i = 0; i < clusterCount; ++i) {
        jstring js = (jstring)env->GetObjectArrayElement(clusterStrings, i);
        env->ReleaseStringUTFChars(js, cStrings[i]);
    }
    env->ReleaseFloatArrayElements(clusterWidths, widths, JNI_ABORT);

    // Pack results into a float array (4 floats per cluster)
    jfloatArray result = env->NewFloatArray(clusterCount * 4);
    if (!result) return nullptr;
    jfloat* resPtr = env->GetFloatArrayElements(result, nullptr);
    for (jint i = 0; i < clusterCount; ++i) {
        resPtr[i * 4 + 0] = transforms[i].offsetX;
        resPtr[i * 4 + 1] = transforms[i].offsetY;
        resPtr[i * 4 + 2] = transforms[i].sizeScale;
        resPtr[i * 4 + 3] = static_cast<float>(transforms[i].alpha);
    }
    env->ReleaseFloatArrayElements(result, resPtr, 0);
    return result;
}

// ── Method descriptor table for dynamic registration ───────────
static JNINativeMethod methods[] = {
    {
        const_cast<char*>("nativeApplyEffects"),
        const_cast<char*>("(Ljava/nio/ByteBuffer;IIIFFZZ)V"),
        reinterpret_cast<void*>(nativeApplyEffectsImpl)
    },
    {
        const_cast<char*>("computeShaping"),
        // JNI signature: ([Ljava/lang/String;[FIF FFFFFF ZI)[F
        const_cast<char*>("([Ljava/lang/String;[FIF" "FFFFFF" "ZI)[F"),
        reinterpret_cast<void*>(nativeComputeShaping)
    }
};

// ── Register native methods when the library is loaded ─────────
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    jclass clazz = env->FindClass("com/pot/cil/hj/ui/view/HandwritingPaint");
    if (clazz == nullptr) return JNI_ERR;

    if (env->RegisterNatives(clazz, methods,
                             sizeof(methods) / sizeof(methods[0])) < 0)
        return JNI_ERR;

    return JNI_VERSION_1_6;
}