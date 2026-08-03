#include "handwriting_shaper.h"
#include <algorithm>
#include <cmath>

namespace {

// Fast deterministic noise (same as the ink engine)
inline float hashNoise(int x, int y, uint32_t seed) {
    uint32_t h = static_cast<uint32_t>(x) * 374761393u +
                 static_cast<uint32_t>(y) * 668265263u + seed;
    h = (h ^ (h >> 13)) * 1274126177u;
    h ^= (h >> 16);
    return (static_cast<float>(h & 0x7fffffffu) / 1073741824.0f) - 1.0f;
}

inline float lerp(float a, float b, float t) { return a + (b - a) * t; }

} // anonymous namespace

void computeClusterTransforms(
    const char** clusters,
    const float* clusterWidths,
    int32_t clusterCount,
    float baseTextSize,
    const HandwritingShaperOptions* options,
    ClusterTransform* outTransforms
) {
    if (clusterCount <= 0 || !clusters || !clusterWidths ||
        !outTransforms || !options) return;

    float pxPerMm = 6.3f;   // same as the Kotlin engine

    for (int i = 0; i < clusterCount; ++i) {
        float n1 = hashNoise(i * 3, 0, options->seed);
        float n2 = hashNoise(i * 7, 5, options->seed);

        // ── Vertical jitter (shakiness) ──────────
        float jitterY = options->shakiness * n1 * pxPerMm * 2.0f;

        // ── Horizontal tremor ────────────────────
        float tremorX = options->microTremor * n2 * pxPerMm * 0.7f;

        // ── Size variation ───────────────────────
        float sizeScale = 1.0f + n2 * 0.025f * options->sizeVariation;

        // ── Velocity‑based pressure ──────────────
        float clusterW = clusterWidths[i];
        float velocityFactor = 1.0f;
        if (options->velocityPressure) {
            float normWidth = std::max(0.5f, std::min(clusterW / baseTextSize, 2.0f));
            velocityFactor = 1.0f - (normWidth - 0.5f) * 0.12f;
        }

        // ── Pressure (alpha) ─────────────────────
        float pressure = 0.5f + (n1 * 0.5f + 0.5f) * options->pressureVariation;
        pressure *= velocityFactor;
        int alpha = static_cast<int>(240.0f + pressure * 15.0f);
        alpha = std::min(std::max(alpha, 230), 255);   // clamp 230‑255

        outTransforms[i].offsetX   = tremorX;
        outTransforms[i].offsetY   = jitterY;
        outTransforms[i].sizeScale = sizeScale;
        outTransforms[i].alpha     = alpha;
    }
}