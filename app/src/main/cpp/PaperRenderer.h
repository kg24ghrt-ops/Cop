#pragma once
#include <GLES3/gl3.h>
#include <map>
#include <string>
#include <vector>
#include <cstdint>

class PaperRenderer {
public:
    PaperRenderer();
    ~PaperRenderer();

    bool init();
    void destroy();
    void resize(int width, int height);

    void setPaperParams(float topMargin, float lineSpacing,
                        float leftMargin, float bottomMargin,
                        int totalLines);
    void setTextOnLine(int line, const std::string& text);
    void clearText();
    void setSelectedLine(int line);
    void setPan(float dx, float dy);
    void setZoom(float scale, float focusX, float focusY);
    void resetTransform();
    void drawFrame();

    bool createFontAtlas(int width, int height, const uint8_t* pixels);

private:
    // GL resources
    GLuint mProgram = 0;
    GLuint mLineVbo = 0, mMarginVbo = 0, mHighlightVbo = 0;
    GLuint mGrainVbo = 0, mTextVbo = 0, mInstanceVbo = 0;
    GLuint mVignetteVbo = 0;
    GLuint mFontTexture = 0, mGrainTexture = 0;

    // Uniform locations
    GLint uMvp = -1, uColor = -1, uTexture = -1, uAlpha = -1;
    GLint uResolution = -1, uVignetteRadius = -1, uCharSize = -1;

    // Attribute locations
    GLint aPos = -1, aTexCoord = -1;
    GLint aInstanceX = -1, aInstanceY = -1, aInstanceRot = -1;
    GLint aInstanceUvOffset = -1, aInstanceAlpha = -1;

    // Paper dimensions
    int mWidth = 0, mHeight = 0;
    float mTopMargin = 0, mSpacing = 0;
    float mLeftMargin = 0, mBottomMargin = 0;
    int mTotalLines = 32;
    int mSelectedLine = 0;

    // Text storage
    std::map<int, std::string> mTextLines;
    std::map<int, uint64_t> mLineSeeds;

    // Transform matrix (4x4, column-major)
    float mContentMatrix[16] = {
        1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1
    };
    float mMvpMatrix[16];

    // Helpers
    bool compileShaders();
    bool createGrainTexture();
    void updateMvpMatrix();
    void rebuildStaticGeometry();
    int generateInstanceData(float* outBuffer, int maxInstances);

    uint64_t splitMix64(uint64_t& seed) const;
    float randomFloat(uint64_t& seed) const;
    void clampPan();
};