#include "handwriting_engine.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace {

// ── Fast deterministic hash (xorshift32) → range [-1, 1] ──────────
inline float hashNoise(int x, int y, uint32_t seed) {
    uint32_t h = static_cast<uint32_t>(x) * 374761393u +
                 static_cast<uint32_t>(y) * 668265263u + seed;
    h = (h ^ (h >> 13)) * 1274126177u;
    h ^= (h >> 16);
    // Use 31 bits to stay in [0, 2^31-1], then map to [-1, 1]
    return (static_cast<float>(h & 0x7fffffffu) / 1073741824.0f) - 1.0f;
}

// ── Alpha-aware pixel blending ─────────────────────────────────────
// Base pixel may be transparent; new colour is non‑premultiplied.
inline uint32_t clampAdd(uint32_t base, uint8_t alphaToAdd,
                         uint8_t r, uint8_t g, uint8_t b) {
    if (alphaToAdd == 0) return base;
    uint32_t a = (base >> 24) & 0xff;
    uint32_t a2 = a + alphaToAdd;
    if (a2 > 255) a2 = 255;

    // Transparent base → added colour becomes the pixel
    if (a == 0) {
        return (a2 << 24) | (r << 16) | (g << 8) | b;
    }

    uint32_t old_r = (base >> 16) & 0xff;
    uint32_t old_g = (base >> 8) & 0xff;
    uint32_t old_b = base & 0xff;

    // Weighted average: new = (old * a + add * alphaToAdd) / a2
    uint32_t new_r = (old_r * a + r * alphaToAdd) / a2;
    uint32_t new_g = (old_g * a + g * alphaToAdd) / a2;
    uint32_t new_b = (old_b * a + b * alphaToAdd) / a2;

    return (a2 << 24) | (new_r << 16) | (new_g << 8) | new_b;
}

// ── PASS 1: Colour‑aware ink bleed ─────────────────────────────────
void applyInkBleed(
    uint32_t* pixels,
    int width,
    int height,
    int stride,
    float strength,
    uint32_t /*seed*/          // currently unused, kept for future variability
) {
    if (strength <= 0.0f || width <= 0 || height <= 0) return;

    // Radius based on strength, safely capped
    int radius = static_cast<int>(2.0f * strength + 0.5f);
    if (radius <= 0) return;
    radius = std::min(radius, 3);

    // Precompute distance falloff weights
    constexpr int maxRadius = 3;
    constexpr int kernelDim = 2 * maxRadius + 1;   // 7
    static float weightTable[kernelDim][kernelDim]; // shared lookup (read‑only)
    static bool tableBuilt = false;

    // Build table once (thread‑safe because it's static const after init)
    if (!tableBuilt) {
        const float radiusF = static_cast<float>(maxRadius + 1);
        for (int dy = -maxRadius; dy <= maxRadius; ++dy) {
            for (int dx = -maxRadius; dx <= maxRadius; ++dx) {
                if (dx == 0 && dy == 0) {
                    weightTable[dy + maxRadius][dx + maxRadius] = 0.0f;
                } else {
                    float dist = std::sqrt(static_cast<float>(dx*dx + dy*dy));
                    weightTable[dy + maxRadius][dx + maxRadius] =
                        std::max(0.0f, 1.0f - dist / radiusF);
                }
            }
        }
        tableBuilt = true;
    }

    // Accumulation buffer: sum(alpha) and sum(R*alpha), etc.
    struct BleedAccum {
        uint32_t alpha = 0;
        uint32_t r_sum = 0;
        uint32_t g_sum = 0;
        uint32_t b_sum = 0;
    };

    const size_t bufSize = stride * height;
    std::vector<BleedAccum> bleedBuf(bufSize);   // zero‑initialised

    const float alphaScale = strength * 0.15f;

    // ── Scatter pass ──────────────────────────────────────────────
    for (int y = 0; y < height; ++y) {
        const uint32_t* srcRow = pixels + y * stride;
        for (int x = 0; x < width; ++x) {
            uint32_t src = srcRow[x];
            uint8_t alpha = src >> 24;
            if (alpha == 0) continue;

            uint8_t sr = (src >> 16) & 0xff;
            uint8_t sg = (src >> 8) & 0xff;
            uint8_t sb = src & 0xff;

            for (int dy = -radius; dy <= radius; ++dy) {
                int ny = y + dy;
                if (ny < 0 || ny >= height) continue;
                BleedAccum* dstRow = bleedBuf.data() + ny * stride;
                for (int dx = -radius; dx <= radius; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx;
                    if (nx < 0 || nx >= width) continue;

                    float weight = weightTable[dy + maxRadius][dx + maxRadius];
                    if (weight <= 0.0f) continue;

                    uint8_t addAlpha = static_cast<uint8_t>(
                        alpha * alphaScale * weight);
                    if (addAlpha == 0) continue;

                    BleedAccum& acc = dstRow[nx];
                    acc.alpha += addAlpha;
                    acc.r_sum += sr * addAlpha;
                    acc.g_sum += sg * addAlpha;
                    acc.b_sum += sb * addAlpha;
                }
            }
        }
    }

    // ── Blend pass ─────────────────────────────────────────────────
    for (int y = 0; y < height; ++y) {
        uint32_t* origRow = pixels + y * stride;
        const BleedAccum* bleedRow = bleedBuf.data() + y * stride;
        for (int x = 0; x < width; ++x) {
            const BleedAccum& acc = bleedRow[x];
            if (acc.alpha == 0) continue;

            uint32_t totalAlpha = std::min(acc.alpha, 255u);
            // Average colour (non‑premultiplied)
            uint8_t r = static_cast<uint8_t>(acc.r_sum / acc.alpha);
            uint8_t g = static_cast<uint8_t>(acc.g_sum / acc.alpha);
            uint8_t b = static_cast<uint8_t>(acc.b_sum / acc.alpha);

            origRow[x] = clampAdd(origRow[x],
                                  static_cast<uint8_t>(totalAlpha),
                                  r, g, b);
        }
    }
}

// ── PASS 2: Edge roughness (tiny dots / scratches) ─────────────────
void applyEdgeNoise(
    uint32_t* pixels,
    int width,
    int height,
    int stride,
    float roughness,
    uint32_t seed
) {
    if (roughness <= 0.0f || width <= 0 || height <= 0) return;

    // Probabilities per edge pixel, capped so total ≤ 1.0
    const float dotProb  = std::min(roughness * 0.3f, 1.0f);
    const float lineProb = std::min(roughness * 0.1f, 1.0f - dotProb);

    // Map probabilities to thresholds in the [-1, 1] space
    const float dotThreshold  = dotProb * 2.0f - 1.0f;
    const float lineThreshold = std::min((dotProb + lineProb) * 2.0f - 1.0f, 1.0f);

    for (int y = 0; y < height; ++y) {
        uint32_t* row = pixels + y * stride;
        for (int x = 0; x < width; ++x) {
            uint8_t alpha = row[x] >> 24;
            if (alpha == 0) continue;

            // 4‑directional edge detection
            bool isEdge = false;
            int dxOut = 0, dyOut = 0;
            if (x > 0 && (row[x - 1] >> 24) == 0) {
                isEdge = true; dxOut = -1; dyOut = 0;
            } else if (x < width - 1 && (row[x + 1] >> 24) == 0) {
                isEdge = true; dxOut = 1; dyOut = 0;
            } else if (y > 0 && (pixels[(y - 1) * stride + x] >> 24) == 0) {
                isEdge = true; dxOut = 0; dyOut = -1;
            } else if (y < height - 1 && (pixels[(y + 1) * stride + x] >> 24) == 0) {
                isEdge = true; dxOut = 0; dyOut = 1;
            }

            if (!isEdge) continue;

            float rnd = hashNoise(x * 3, y * 7, seed);

            if (rnd < dotThreshold) {
                // Single dark pixel outward
                int nx = x + dxOut;
                int ny = y + dyOut;
                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    uint32_t& target = pixels[ny * stride + nx];
                    uint8_t addAlpha = static_cast<uint8_t>(60.0f * roughness);
                    target = clampAdd(target, addAlpha, 0, 0, 0);  // black ink
                }
            } else if (rnd < lineThreshold) {
                // Short two‑pixel line outward
                for (int step = 1; step <= 2; ++step) {
                    int nx = x + dxOut * step;
                    int ny = y + dyOut * step;
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) break;
                    uint32_t& target = pixels[ny * stride + nx];
                    uint8_t addAlpha = static_cast<uint8_t>(40.0f * roughness / step);
                    target = clampAdd(target, addAlpha, 0, 0, 0);
                }
            }
        }
    }
}

} // anonymous namespace

// ── Public entry point ─────────────────────────────────────────────
void applyHandwritingEffects(
    uint32_t* pixels,
    int width,
    int height,
    int stride,
    const HandwritingOptions& options
) {
    if (pixels == nullptr || width <= 0 || height <= 0 || stride < width) return;
    if (!options.enable_mistakes || options.performance_mode) return;

    applyInkBleed(pixels, width, height, stride,
                  options.ink_feathering, options.seed);
    applyEdgeNoise(pixels, width, height, stride,
                   options.edge_roughness, options.seed);
}