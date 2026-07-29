#include "native_renderer.h"
#include <cmath>
#include <cstring>
#include <algorithm>

NativeRenderer::NativeRenderer() = default;
NativeRenderer::~NativeRenderer() = default;

void NativeRenderer::setDimensions(int w, int h, float top, float spacing,
                                   float left, float bottom, int lines) {
    mWidth = w; mHeight = h; mTopMargin = top; mSpacing = spacing;
    mLeftMargin = left; mBottomMargin = bottom; mTotalLines = lines;
    recalcLineY();
}

void NativeRenderer::recalcLineY() {
    mLineY.resize(mTotalLines);
    for (int i = 0; i < mTotalLines; ++i) {
        mLineY[i] = mTopMargin + i * mSpacing + mSpacing / 2.0f;
    }
}

void NativeRenderer::setTextLine(int line, const std::string& text) {
    if (line < 0 || line >= mTotalLines) return;
    if (text.empty()) mTextLines.erase(line);
    else mTextLines[line] = text;
    // Seed the line if not present (use deterministic hash)
    if (mLineSeeds.find(line) == mLineSeeds.end()) {
        mLineSeeds[line] = static_cast<uint64_t>(line) * 0x9e3779b97f4a7c15ULL;
    }
}

void NativeRenderer::clearText() { mTextLines.clear(); }
void NativeRenderer::setSelectedLine(int line) { mSelectedLine = std::clamp(line, 0, mTotalLines - 1); }
void NativeRenderer::setHumanize(float f) { mHumanize = std::clamp(f, 0.0f, 1.0f); }

// ----- Random helpers (matching Java's Random behavior exactly) -----
uint64_t NativeRenderer::splitMix64(uint64_t& seed) const {
    uint64_t z = (seed += 0x9e3779b97f4a7c15ULL);
    z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
    z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
    return z ^ (z >> 31);
}
float NativeRenderer::randomFloat(uint64_t& seed) const {
    // Java's Random.nextFloat() returns (next(24) / 2^24)
    uint64_t bits = splitMix64(seed);
    uint32_t next24 = static_cast<uint32_t>(bits >> 40) & 0xFFFFFF; // 24 bits
    return static_cast<float>(next24) / 16777216.0f; // 2^24
}

// ----- Main frame generator (the heavy lifting) -----
int NativeRenderer::generateFrame(const float* contentMatrix,
                                  float* outBuffer, int maxInstances) {
    // In a production version, we'd also fill line/margin VBO data here.
    // For this demo, we focus on the most expensive part: per-character transforms.

    int instanceIdx = 0;
    for (const auto& [line, text] : mTextLines) {
        if (line < 0 || line >= mTotalLines) continue;
        auto seedIt = mLineSeeds.find(line);
        if (seedIt == mLineSeeds.end()) continue;
        uint64_t seed = seedIt->second; // copy

        float baseX = mLeftMargin + 10.0f;
        float lineTop = mTopMargin + line * mSpacing;
        float baseY = lineTop + mSpacing / 2.0f;
        // Font metrics approximation (we standardize on 0.5*spacing height)
        float charHeight = mSpacing * 0.5f;
        float charWidth = charHeight * 0.6f; // monospace-ish

        float x = baseX;
        for (char c : text) {
            if (instanceIdx >= maxInstances) break;

            // ---- Jitter (same as Java version) ----
            float maxJitterY = mSpacing * 0.15f;
            float jitterY = (randomFloat(seed) * 2.0f - 1.0f) * maxJitterY * mHumanize;

            float maxRotation = 2.0f; // degrees
            float rotation = (randomFloat(seed) * 2.0f - 1.0f) * maxRotation * mHumanize;

            float spacingVariation = 1.0f + (randomFloat(seed) * 2.0f - 1.0f) * 0.15f * mHumanize;
            float actualAdvance = charWidth * spacingVariation;

            // Alpha variation (will be used in shader later)
            float alpha = (0.7f + randomFloat(seed) * 0.3f) * (1.0f - mHumanize * 0.3f);

            // Write to instance buffer: x, y, rotation (in radians)
            outBuffer[instanceIdx * 3 + 0] = x + charWidth / 2.0f; // center of char
            outBuffer[instanceIdx * 3 + 1] = baseY + jitterY;
            outBuffer[instanceIdx * 3 + 2] = rotation * 3.14159f / 180.0f;

            // In a full solution, we'd also pass UV coords for the atlas, alpha, and scale.
            // For now, we handle scaling in the vertex shader using a uniform char size.
            // We'll store UV offsets per character in a separate buffer.

            instanceIdx++;
            x += actualAdvance;
        }
    }
    return instanceIdx;
}