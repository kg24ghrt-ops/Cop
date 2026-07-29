#pragma once
#include <cstdint>
#include <map>
#include <string>
#include <vector>

class NativeRenderer {
public:
    NativeRenderer();
    ~NativeRenderer();

    void setDimensions(int width, int height, float topMargin, float spacing,
                       float leftMargin, float bottomMargin, int totalLines);
    void setTextLine(int line, const std::string& text);
    void clearText();
    void setSelectedLine(int line);
    void setHumanize(float factor);

    // Returns number of instances written to outBuffer (each = 6 floats: x, y, rot, uvX, uvY, alpha)
    int generateFrame(const float* contentMatrix, float* outInstanceBuffer, int maxInstances);

private:
    int mWidth = 0, mHeight = 0;
    float mTopMargin = 0, mSpacing = 0, mLeftMargin = 0, mBottomMargin = 0;
    int mTotalLines = 32;
    int mSelectedLine = 0;
    float mHumanize = 0.6f;

    std::map<int, std::string> mTextLines;
    std::map<int, uint64_t> mLineSeeds;

    std::vector<float> mLineY;

    uint64_t splitMix64(uint64_t& seed) const;
    float randomFloat(uint64_t& seed) const;
    void recalcLineY();
};