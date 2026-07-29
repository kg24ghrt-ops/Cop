#include "PaperRenderer.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PaperRenderer", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PaperRenderer", __VA_ARGS__)

// ---- Shaders (unchanged) ----
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

PaperRenderer::PaperRenderer() {}
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

    // Static quad for text (reusable)
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

    LOGI("Renderer initialized successfully");
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
    LOGI("resize: %d x %d", width, height);
    rebuildStaticGeometry();
    updateMvpMatrix();
}

void PaperRenderer::setPaperParams(float top, float spacing, float left, float bottom, int lines) {
    mTopMargin = top;
    mSpacing = spacing;
    mLeftMargin = left;
    mBottomMargin = bottom;
    mTotalLines = lines;
    LOGI("setPaperParams: top=%.1f, spacing=%.1f, left=%.1f, bottom=%.1f, lines=%d",
         top, spacing, left, bottom, lines);
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
    LOGI("setTextOnLine: line=%d, text='%s'", line, text.c_str());
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
    if (mFontAtlasCreated) return true;
    if (!pixels) {
        LOGE("createFontAtlas: null pixel data");
        return false;
    }
    if (mFontTexture != 0) {
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
        LOGE("createFontAtlas: glTexImage2D error 0x%x", err);
        glDeleteTextures(1, &mFontTexture);
        mFontTexture = 0;
        return false;
    }
    mFontAtlasCreated = true;
    LOGI("Font atlas created: %dx%d", width, height);
    return true;
}

void PaperRenderer::drawFrame() {
    glClearColor(0.980f, 0.961f, 0.902f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(mProgram);
    glUniformMatrix4fv(uMvp, 1, GL_FALSE, mMvpMatrix);

    // === TEST: draw a red square at top-left to verify shader works ===
    // We'll use a separate, simple draw to confirm geometry is working.
    // This also helps us see if the attribute is set up correctly.
    static bool testDrawn = false;
    if (!testDrawn) {
        float testVerts[8] = {50,50, 150,50, 50,150, 150,150};
        GLuint testVbo;
        glGenBuffers(1, &testVbo);
        glBindBuffer(GL_ARRAY_BUFFER, testVbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(testVerts), testVerts, GL_STATIC_DRAW);
        glEnableVertexAttribArray(aPos);
        glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 0, 0);
        glUniform4f(uColor, 1.0f, 0.0f, 0.0f, 1.0f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(aPos);
        glDeleteBuffers(1, &testVbo);
        LOGI("Test red square drawn at (50,50)");
        testDrawn = true;
    }

    // ---- Grain ----
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, mGrainTexture);
    glUniform1i(uTexture, 1);
    glUniform1f(uAlpha, 0.18f);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glBindBuffer(GL_ARRAY_BUFFER, mGrainVbo);
    glEnableVertexAttribArray(aPos);
    glEnableVertexAttribArray(aTexCoord);
    glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 4*4, (void*)0);
    glVertexAttribPointer(aTexCoord, 2, GL_FLOAT, GL_FALSE, 4*4, (void*)(2*4));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(aPos);
    glDisableVertexAttribArray(aTexCoord);
    glDisable(GL_BLEND);

    // ---- Lines (blue) ----
    glDisable(GL_BLEND);
    glUniform4f(uColor, 0.549f, 0.600f, 0.749f, 1.0f);
    glBindBuffer(GL_ARRAY_BUFFER, mLineVbo);
    glEnableVertexAttribArray(aPos);
    glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 2*4, (void*)0);
    glDrawArrays(GL_LINES, 0, mTotalLines * 2);
    glDisableVertexAttribArray(aPos);

    // ---- Margin (red) ----
    glUniform4f(uColor, 0.749f, 0.200f, 0.200f, 1.0f);
    glBindBuffer(GL_ARRAY_BUFFER, mMarginVbo);
    glEnableVertexAttribArray(aPos);
    glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 2*4, (void*)0);
    glDrawArrays(GL_LINES, 0, 2);
    glDisableVertexAttribArray(aPos);

    // ---- Highlight ----
    if (mSelectedLine >= 0 && mSelectedLine < mTotalLines) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUniform4f(uColor, 0.4f, 0.6f, 1.0f, 0.3f);
        glBindBuffer(GL_ARRAY_BUFFER, mHighlightVbo);
        glEnableVertexAttribArray(aPos);
        glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 2*4, (void*)0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(aPos);
        glDisable(GL_BLEND);
    }

    // ---- Text (instanced) ----
    const int maxInstances = 4096;
    float instanceData[6 * maxInstances];
    int instanceCount = generateInstanceData(instanceData, maxInstances);

    if (instanceCount > 0 && mFontTexture != 0) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, mFontTexture);
        glUniform1i(uTexture, 0);
        glUniform1f(uAlpha, 1.0f);
        glUniform1f(uCharSize, mSpacing * 0.5f);

        glBindBuffer(GL_ARRAY_BUFFER, mTextVbo);
        glEnableVertexAttribArray(aPos);
        glEnableVertexAttribArray(aTexCoord);
        glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 4*4, (void*)0);
        glVertexAttribPointer(aTexCoord, 2, GL_FLOAT, GL_FALSE, 4*4, (void*)(2*4));

        glBindBuffer(GL_ARRAY_BUFFER, mInstanceVbo);
        glBufferData(GL_ARRAY_BUFFER, instanceCount * 6 * sizeof(float),
                     instanceData, GL_DYNAMIC_DRAW);

        glEnableVertexAttribArray(aInstanceX);
        glEnableVertexAttribArray(aInstanceY);
        glEnableVertexAttribArray(aInstanceRot);
        glEnableVertexAttribArray(aInstanceUvOffset);
        glEnableVertexAttribArray(aInstanceAlpha);

        glVertexAttribPointer(aInstanceX, 1, GL_FLOAT, GL_FALSE, 6*4, (void*)0);
        glVertexAttribPointer(aInstanceY, 1, GL_FLOAT, GL_FALSE, 6*4, (void*)4);
        glVertexAttribPointer(aInstanceRot, 1, GL_FLOAT, GL_FALSE, 6*4, (void*)8);
        glVertexAttribPointer(aInstanceUvOffset, 2, GL_FLOAT, GL_FALSE, 6*4, (void*)12);
        glVertexAttribPointer(aInstanceAlpha, 1, GL_FLOAT, GL_FALSE, 6*4, (void*)20);

        glVertexAttribDivisor(aInstanceX, 1);
        glVertexAttribDivisor(aInstanceY, 1);
        glVertexAttribDivisor(aInstanceRot, 1);
        glVertexAttribDivisor(aInstanceUvOffset, 1);
        glVertexAttribDivisor(aInstanceAlpha, 1);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, instanceCount);

        glDisableVertexAttribArray(aPos);
        glDisableVertexAttribArray(aTexCoord);
        glDisableVertexAttribArray(aInstanceX);
        glDisableVertexAttribArray(aInstanceY);
        glDisableVertexAttribArray(aInstanceRot);
        glDisableVertexAttribArray(aInstanceUvOffset);
        glDisableVertexAttribArray(aInstanceAlpha);
        glVertexAttribDivisor(aInstanceX, 0);
        glVertexAttribDivisor(aInstanceY, 0);
        glVertexAttribDivisor(aInstanceRot, 0);
        glVertexAttribDivisor(aInstanceUvOffset, 0);
        glVertexAttribDivisor(aInstanceAlpha, 0);
        glDisable(GL_BLEND);
    }

    // ---- Vignette (screen space) ----
    float savedMvp[16];
    memcpy(savedMvp, mMvpMatrix, sizeof(savedMvp));

    float ortho[16] = {
        2.0f/mWidth, 0, 0, 0,
        0, -2.0f/mHeight, 0, 0,
        0, 0, -1, 0,
        -1, 1, 0, 1
    };
    memcpy(mMvpMatrix, ortho, sizeof(ortho));
    glUniformMatrix4fv(uMvp, 1, GL_FALSE, mMvpMatrix);

    glUniform4f(uColor, 0.0f, 0.0f, 0.0f, 0.12f);
    glUniform2f(uResolution, mWidth, mHeight);
    glUniform1f(uVignetteRadius, 0.9f);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glBindBuffer(GL_ARRAY_BUFFER, mVignetteVbo);
    glEnableVertexAttribArray(aPos);
    glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 2*4, (void*)0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(aPos);
    glDisable(GL_BLEND);

    memcpy(mMvpMatrix, savedMvp, sizeof(savedMvp));
    glUniformMatrix4fv(uMvp, 1, GL_FALSE, mMvpMatrix);
}

// ---- Private Helpers ----

bool PaperRenderer::compileShaders() { /* same as before */ }

bool PaperRenderer::createGrainTexture() { /* same as before */ }

void PaperRenderer::updateMvpMatrix() { /* same as before */ }

void PaperRenderer::rebuildStaticGeometry() {
    LOGI("rebuildStaticGeometry: mWidth=%d, mHeight=%d, mTotalLines=%d", mWidth, mHeight, mTotalLines);
    if (mWidth == 0 || mHeight == 0 || mTotalLines == 0) {
        LOGE("Invalid dimensions, skipping geometry rebuild");
        return;
    }

    // Lines
    std::vector<float> lineVerts;
    lineVerts.reserve(mTotalLines * 4);
    for (int i = 0; i < mTotalLines; ++i) {
        float y = mTopMargin + i * mSpacing + mSpacing/2.0f;
        lineVerts.push_back(mLeftMargin);
        lineVerts.push_back(y);
        lineVerts.push_back(mWidth);
        lineVerts.push_back(y);
    }
    glBindBuffer(GL_ARRAY_BUFFER, mLineVbo);
    glBufferData(GL_ARRAY_BUFFER, lineVerts.size()*sizeof(float),
                 lineVerts.data(), GL_STATIC_DRAW);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) LOGE("glBufferData lines error 0x%x", err);

    // Margin
    float marginVerts[4] = {
        mLeftMargin, mTopMargin,
        mLeftMargin, mHeight - mBottomMargin
    };
    glBindBuffer(GL_ARRAY_BUFFER, mMarginVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(marginVerts), marginVerts, GL_STATIC_DRAW);

    // Highlight
    float y = mTopMargin + mSelectedLine * mSpacing;
    float highlightVerts[8] = {
        mLeftMargin, y,
        (float)mWidth, y,
        mLeftMargin, y + mSpacing,
        (float)mWidth, y + mSpacing
    };
    glBindBuffer(GL_ARRAY_BUFFER, mHighlightVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(highlightVerts), highlightVerts, GL_DYNAMIC_DRAW);

    // Grain quad
    float grainVerts[16] = {
        0,0, 0,0,
        (float)mWidth,0, (float)mWidth/256.0f,0,
        0,(float)mHeight, 0,(float)mHeight/256.0f,
        (float)mWidth,(float)mHeight, (float)mWidth/256.0f,(float)mHeight/256.0f
    };
    glBindBuffer(GL_ARRAY_BUFFER, mGrainVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(grainVerts), grainVerts, GL_STATIC_DRAW);
}

uint64_t PaperRenderer::splitMix64(uint64_t& seed) const { /* same as before */ }
float PaperRenderer::randomFloat(uint64_t& seed) const { /* same as before */ }
int PaperRenderer::generateInstanceData(float* outBuffer, int maxInstances) { /* same as before */ }
void PaperRenderer::clampPan() { /* same as before */ }