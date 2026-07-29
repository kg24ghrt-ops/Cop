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
    if (mLineSeeds.find(line) == mLineSeeds.end()) {
        mLineSeeds[line] = static_cast<uint64_t>(line) * 0x9e3779b97f4a7c15ULL;
    }
}

void NativeRenderer::clearText() { mTextLines.clear(); }
void NativeRenderer::setSelectedLine(int line) { mSelectedLine = std::clamp(line, 0, mTotalLines - 1); }
void NativeRenderer::setHumanize(float f) { mHumanize = std::clamp(f, 0.0f, 1.0f); }

// ---- Random helpers (matching Java's Random) ----
uint64_t NativeRenderer::splitMix64(uint64_t& seed) const {
    uint64_t z = (seed += 0x9e3779b97f4a7c15ULL);
    z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
    z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
    return z ^ (z >> 31);
}
float NativeRenderer::randomFloat(uint64_t& seed) const {
    uint64_t bits = splitMix64(seed);
    uint32_t next24 = static_cast<uint32_t>(bits >> 40) & 0xFFFFFF;
    return static_cast<float>(next24) / 16777216.0f;
}

// ---- Main instance generator ----
int NativeRenderer::generateFrame(const float* contentMatrix,
                                  float* outBuffer, int maxInstances) {
    // For each char, we compute position, rotation, UV offset (in atlas) and alpha.
    // The char width = spacing * 0.5 * 0.6 (aspect ratio ~0.6)
    float charHeight = mSpacing * 0.5f;
    float charWidth = charHeight * 0.6f;

    int instanceIdx = 0;
    for (const auto& [line, text] : mTextLines) {
        if (line < 0 || line >= mTotalLines) continue;
        auto seedIt = mLineSeeds.find(line);
        if (seedIt == mLineSeeds.end()) continue;
        uint64_t seed = seedIt->second;

        float baseX = mLeftMargin + 10.0f;
        float baseY = mLineY[line];

        float x = baseX;
        for (char c : text) {
            if (instanceIdx >= maxInstances) break;

            // Jitter
            float maxJitterY = mSpacing * 0.15f;
            float jitterY = (randomFloat(seed) * 2.0f - 1.0f) * maxJitterY * mHumanize;
            float maxRotation = 2.0f; // degrees
            float rotation = (randomFloat(seed) * 2.0f - 1.0f) * maxRotation * mHumanize * 3.14159f / 180.0f;
            float spacingVar = 1.0f + (randomFloat(seed) * 2.0f - 1.0f) * 0.15f * mHumanize;
            float advance = charWidth * spacingVar;
            float alpha = (0.7f + randomFloat(seed) * 0.3f) * (1.0f - mHumanize * 0.3f);

            // UV offset in font atlas (16 columns)
            int charIndex = static_cast<int>(c) - 32;
            float col = charIndex % 16;
            float row = charIndex / 16;
            float uvX = col / 16.0f;
            float uvY = row / 6.0f; // 6 rows

            // Write instance: x, y, rot, uvX, uvY, alpha
            outBuffer[instanceIdx * 6 + 0] = x + charWidth / 2.0f;
            outBuffer[instanceIdx * 6 + 1] = baseY + jitterY;
            outBuffer[instanceIdx * 6 + 2] = rotation;
            outBuffer[instanceIdx * 6 + 3] = uvX;
            outBuffer[instanceIdx * 6 + 4] = uvY;
            outBuffer[instanceIdx * 6 + 5] = alpha;

            instanceIdx++;
            x += advance;
        }
    }
    return instanceIdx;
}