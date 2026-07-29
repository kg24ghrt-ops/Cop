#include "PaperRenderer.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PaperRenderer", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PaperRenderer", __VA_ARGS__)

// ---- Shaders (same as before) ----
static const char* VERTEX_SHADER_SOURCE = R"(
    #version 300 es
    uniform mat4 uMvp;
    uniform float uCharSize;

    in vec2 aPosition;
    in vec2 aTexCoord;
    in float aInstanceX;
    in float aInstanceY;
    in float aInstanceRot;
    in vec2 aInstanceUvOffset;
    in float aInstanceAlpha;

    out vec2 vTexCoord;
    out mediump float vAlpha;

    void main() {
        float c = cos(aInstanceRot);
        float s = sin(aInstanceRot);
        vec2 pos = aPosition * uCharSize;
        vec2 rotated = vec2(pos.x * c - pos.y * s, pos.x * s + pos.y * c);
        vec2 finalPos = rotated + vec2(aInstanceX, aInstanceY);
        gl_Position = uMvp * vec4(finalPos, 0.0, 1.0);
        vec2 uv = aTexCoord * (1.0 / 16.0) + aInstanceUvOffset;
        vTexCoord = uv;
        vAlpha = aInstanceAlpha;
    }
)";

static const char* FRAGMENT_SHADER_SOURCE = R"(
    #version 300 es
    precision mediump float;
    uniform vec4 uColor;
    uniform sampler2D uTexture;
    uniform float uAlpha;
    uniform vec2 uResolution;
    uniform float uVignetteRadius;

    in vec2 vTexCoord;
    in mediump float vAlpha;
    out vec4 fragColor;

    void main() {
        vec4 texColor = texture(uTexture, vTexCoord);
        if (texColor.a < 0.01) {
            fragColor = uColor;
        } else {
            fragColor = vec4(texColor.rgb, texColor.a * uAlpha * vAlpha);
        }
        if (uResolution.x > 0.0 && uResolution.y > 0.0) {
            vec2 center = uResolution * 0.5;
            float radius = length(uResolution) * 0.5 * uVignetteRadius;
            float dist = distance(gl_FragCoord.xy, center);
            float alpha = smoothstep(radius * 0.7, radius, dist);
            fragColor = vec4(fragColor.rgb, fragColor.a * (1.0 - alpha * 0.12));
        }
    }
)";

PaperRenderer::PaperRenderer() = default;
PaperRenderer::~PaperRenderer() { destroy(); }

bool PaperRenderer::init() {
    if (!compileShaders()) {
        LOGE("Shader compilation failed");
        return false;
    }

    glGenBuffers(1, &mLineVbo);
    glGenBuffers(1, &mMarginVbo);
    glGenBuffers(1, &mHighlightVbo);
    glGenBuffers(1, &mGrainVbo);
    glGenBuffers(1, &mVignetteVbo);
    glGenBuffers(1, &mTextVbo);
    glGenBuffers(1, &mInstanceVbo);

    if (!createGrainTexture()) {
        LOGE("Failed to create grain texture");
        return false;
    }

    // Static quad for text
    float textQuad[16] = {
        -0.5f, -0.5f, 0,1,
         0.5f, -0.5f, 1,1,
        -0.5f,  0.5f, 0,0,
         0.5f,  0.5f, 1,0
    };
    glBindBuffer(GL_ARRAY_BUFFER, mTextVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(textQuad), textQuad, GL_STATIC_DRAW);

    float vignetteQuad[8] = {0,0, 1,0, 0,1, 1,1};
    glBindBuffer(GL_ARRAY_BUFFER, mVignetteVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vignetteQuad), vignetteQuad, GL_STATIC_DRAW);

    return true;
}

void PaperRenderer::destroy() {
    if (mProgram) glDeleteProgram(mProgram);
    glDeleteBuffers(1, &mLineVbo);
    glDeleteBuffers(1, &mMarginVbo);
    glDeleteBuffers(1, &mHighlightVbo);
    glDeleteBuffers(1, &mGrainVbo);
    glDeleteBuffers(1, &mVignetteVbo);
    glDeleteBuffers(1, &mTextVbo);
    glDeleteBuffers(1, &mInstanceVbo);
    if (mFontTexture) glDeleteTextures(1, &mFontTexture);
    if (mGrainTexture) glDeleteTextures(1, &mGrainTexture);
    mProgram = 0;
    mFontAtlasCreated = false;
}

void PaperRenderer::resize(int width, int height) {
    mWidth = width;
    mHeight = height;
    glViewport(0, 0, width, height);
    rebuildStaticGeometry();
    updateMvpMatrix();
}

void PaperRenderer::setPaperParams(float top, float spacing, float left, float bottom, int lines) {
    mTopMargin = top;
    mSpacing = spacing;
    mLeftMargin = left;
    mBottomMargin = bottom;
    mTotalLines = lines;
    rebuildStaticGeometry();
}

void PaperRenderer::setTextOnLine(int line, const std::string& text) {
    if (line < 0 || line >= mTotalLines) return;
    if (text.empty()) {
        mTextLines.erase(line);
    } else {
        mTextLines[line] = text;
        if (mLineSeeds.find(line) == mLineSeeds.end()) {
            mLineSeeds[line] = static_cast<uint64_t>(line) * 0x9e3779b97f4a7c15ULL;
        }
    }
}

void PaperRenderer::clearText() { mTextLines.clear(); }
void PaperRenderer::setSelectedLine(int line) {
    mSelectedLine = std::clamp(line, 0, mTotalLines - 1);
    rebuildStaticGeometry();
}

void PaperRenderer::setPan(float dx, float dy) {
    mContentMatrix[12] += dx;
    mContentMatrix[13] += dy;
    clampPan();
    updateMvpMatrix();
}

void PaperRenderer::setZoom(float scale, float focusX, float focusY) {
    float currentScale = mContentMatrix[0];
    float newScale = currentScale * scale;
    if (newScale < 0.5f || newScale > 3.0f) return;

    float cx = focusX, cy = focusY;
    mContentMatrix[12] = (mContentMatrix[12] - cx) * scale + cx;
    mContentMatrix[13] = (mContentMatrix[13] - cy) * scale + cy;
    mContentMatrix[0] *= scale;
    mContentMatrix[5] *= scale;
    clampPan();
    updateMvpMatrix();
}

void PaperRenderer::resetTransform() {
    float identity[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    memcpy(mContentMatrix, identity, sizeof(identity));
    clampPan();
    updateMvpMatrix();
}

bool PaperRenderer::createFontAtlas(int width, int height, const uint8_t* pixels) {
    if (mFontAtlasCreated) return true;  // already created
    if (!pixels) {
        LOGE("createFontAtlas: null pixel data");
        return false;
    }
    if (mFontTexture != 0) {
        // If texture already exists (shouldn't), delete it
        glDeleteTextures(1, &mFontTexture);
        mFontTexture = 0;
    }
    glGenTextures(1, &mFontTexture);
    if (mFontTexture == 0) {
        LOGE("createFontAtlas: glGenTextures failed");
        return false;
    }
    glBindTexture(GL_TEXTURE_2D, mFontTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("createFontAtlas: glTexImage2D failed, error=0x%x", err);
        glDeleteTextures(1, &mFontTexture);
        mFontTexture = 0;
        return false;
    }
    mFontAtlasCreated = true;
    LOGI("Font atlas created successfully: %dx%d", width, height);
    return true;
}

void PaperRenderer::drawFrame() {
    // ... same as before (unchanged) ...
    // Ensure we use the font texture only if it exists
    // In drawFrame, check `if (mFontTexture != 0)` before using it.
    // (already present in earlier code)
}

// ---- Private helpers ----
bool PaperRenderer::compileShaders() { /* same as before */ }
bool PaperRenderer::createGrainTexture() { /* same as before */ }
void PaperRenderer::updateMvpMatrix() { /* same as before */ }
void PaperRenderer::rebuildStaticGeometry() { /* same as before */ }
int PaperRenderer::generateInstanceData(float* outBuffer, int maxInstances) { /* same as before */ }
uint64_t PaperRenderer::splitMix64(uint64_t& seed) const { /* same as before */ }
float PaperRenderer::randomFloat(uint64_t& seed) const { /* same as before */ }
void PaperRenderer::clampPan() { /* same as before */ }