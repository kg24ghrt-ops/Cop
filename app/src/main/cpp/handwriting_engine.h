#ifndef HANDWRITING_ENGINE_H
#define HANDWRING_ENGINE_H

#include <cstdint>

struct HandwritingOptions {
    float ink_feathering;    // 0..1  strength of ink spread
    float edge_roughness;    // 0..1  probability of edge noise
    bool  enable_mistakes;   // false → do nothing
    bool  performance_mode;  // true  → skip expensive passes
    uint32_t seed;           // random seed for deterministic noise
};

void applyHandwritingEffects(
    uint32_t* pixels,
    int width,
    int height,
    int stride,
    const HandwritingOptions& options
);

#endif