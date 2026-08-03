#ifndef HANDWRITING_SHAPER_H
#define HANDWRITING_SHAPER_H

#include <cstdint>

/**
 * Shaping parameters computed for one grapheme cluster.
 * All values are in pixel units or factors.
 */
struct ClusterTransform {
    float offsetX;     // horizontal shift (includes tremor)
    float offsetY;     // vertical shift (jitter)
    float sizeScale;   // multiplier for the text size (1.0 = original)
    int   alpha;       // 0‑255 alpha to use
};

/**
 * Configuration that mirrors the handwriting‑paint properties.
 */
struct HandwritingShaperOptions {
    float shakiness;          // vertical jitter amplitude (0‑1)
    float microTremor;        // horizontal tremor amplitude (0‑1)
    float pressureVariation;  // randomness of pressure (0‑1)
    float sizeVariation;      // randomness of size (0‑1)
    float skipChance;         // probability of a gap between clusters
    float skipWidth;          // extra gap when skip occurs (pixels)
    bool  velocityPressure;   // whether cluster width influences pressure
    uint32_t seed;            // deterministic seed
};

/**
 * Process a list of grapheme clusters for a single word.
 *
 * @param clusters         array of UTF‑8 cluster strings
 * @param clusterWidths    base measured widths (pixels) for each cluster
 * @param clusterCount     number of clusters
 * @param baseTextSize     base text size (pixels) – used for skip‑gap scaling
 * @param options          handwriting variation parameters
 * @param outTransforms    output array (caller‑allocated, size clusterCount)
 *
 * The caller must allocate all arrays and free them afterwards.
 */
void computeClusterTransforms(
    const char** clusters,
    const float* clusterWidths,
    int32_t clusterCount,
    float baseTextSize,
    const HandwritingShaperOptions* options,
    ClusterTransform* outTransforms
);

#endif