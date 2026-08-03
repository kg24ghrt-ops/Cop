#include <jni.h>
#include "handwriting_engine.h"
#include "handwriting_shaper.h"

// ... existing nativeApplyEffectsImpl ... (keep it)

// ── New shaping function ─────────────────────────────────────────
static jfloatArray JNICALL
nativeComputeShaping(JNIEnv* env, jclass /*clazz*/,
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
                     jint seed) {
    // Convert Java arrays to C
    jfloat* widths = env->GetFloatArrayElements(clusterWidths, nullptr);
    if (!widths) return nullptr;

    std::vector<const char*> cStrings(clusterCount);
    for (jint i = 0; i < clusterCount; ++i) {
        jstring js = (jstring)env->GetObjectArrayElement(clusterStrings, i);
        cStrings[i] = env->GetStringUTFChars(js, nullptr);
    }

    HandwritingShaperOptions opts;
    opts.shakiness          = shakiness;
    opts.microTremor        = microTremor;
    opts.pressureVariation  = pressureVariation;
    opts.sizeVariation      = sizeVariation;
    opts.skipChance         = skipChance;
    opts.skipWidth          = skipWidth;
    opts.velocityPressure   = velocityPressure;
    opts.seed               = seed;

    std::vector<ClusterTransform> transforms(clusterCount);
    computeClusterTransforms(cStrings.data(), widths, clusterCount,
                             baseTextSize, &opts, transforms.data());

    // Release strings
    for (jint i = 0; i < clusterCount; ++i) {
        jstring js = (jstring)env->GetObjectArrayElement(clusterStrings, i);
        env->ReleaseStringUTFChars(js, cStrings[i]);
    }
    env->ReleaseFloatArrayElements(clusterWidths, widths, 0);

    // Pack results into a float array: 4 floats per cluster (x, y, scale, alpha)
    jfloatArray result = env->NewFloatArray(clusterCount * 4);
    if (!result) return nullptr;
    jfloat* resPtr = env->GetFloatArrayElements(result, nullptr);
    for (jint i = 0; i < clusterCount; ++i) {
        resPtr[i*4 + 0] = transforms[i].offsetX;
        resPtr[i*4 + 1] = transforms[i].offsetY;
        resPtr[i*4 + 2] = transforms[i].sizeScale;
        resPtr[i*4 + 3] = static_cast<float>(transforms[i].alpha);
    }
    env->ReleaseFloatArrayElements(result, resPtr, 0);
    return result;
}

// ── Registration table (update) ──────────────────────────────────
static JNINativeMethod methods[] = {
    {
        const_cast<char*>("nativeApplyEffects"),
        const_cast<char*>("(Ljava/nio/ByteBuffer;IIIFFZZ)V"),
        reinterpret_cast<void*>(nativeApplyEffectsImpl)
    },
    {
        const_cast<char*>("computeShaping"),   // new shaping method
        const_cast<char*>("([Ljava/lang/String;[FIF"
                          "FFFFFZ"
                          "I)[F"),
        reinterpret_cast<void*>(nativeComputeShaping)
    }
};

// JNI_OnLoad remains unchanged – just registers the methods in HandwritingPaint.