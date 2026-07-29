#include "PaperRenderer.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PaperRenderer", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PaperRenderer", __VA_ARGS__)

// ---- Optimized shaders with mediump precision for Mali ----
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
        // Vignette - only computed when needed (branch predication friendly)
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

    // Generate VBOs
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

    // Static geometry (reusable quads)
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
    glGenTextures(1, &mFontTexture);
    glBindTexture(GL_TEXTURE_2D, mFontTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    return true;
}

void PaperRenderer::drawFrame() {
    glClearColor(0.980f, 0.961f, 0.902f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(mProgram);
    glUniformMatrix4fv(uMvp, 1, GL_FALSE, mMvpMatrix);

    // ---- Grain (background) ----
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

        // Cleanup
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

    // Orthographic projection for screen space
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

    // Restore MVP
    memcpy(mMvpMatrix, savedMvp, sizeof(savedMvp));
    glUniformMatrix4fv(uMvp, 1, GL_FALSE, mMvpMatrix);
}

// ---- Private helpers ----

bool PaperRenderer::compileShaders() {
    auto compile = [](GLenum type, const char* src) -> GLuint {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &src, nullptr);
        glCompileShader(shader);
        GLint status;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
        if (!status) {
            char log[512];
            glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
            LOGE("Shader error: %s", log);
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    };

    GLuint vs = compile(GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE);
    GLuint fs = compile(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE);
    if (!vs || !fs) return false;

    mProgram = glCreateProgram();
    glAttachShader(mProgram, vs);
    glAttachShader(mProgram, fs);
    glLinkProgram(mProgram);

    GLint linkStatus;
    glGetProgramiv(mProgram, GL_LINK_STATUS, &linkStatus);
    if (!linkStatus) {
        char log[512];
        glGetProgramInfoLog(mProgram, sizeof(log), nullptr, log);
        LOGE("Link error: %s", log);
        glDeleteProgram(mProgram);
        mProgram = 0;
        return false;
    }
    glDeleteShader(vs);
    glDeleteShader(fs);

    // Get uniform and attribute locations
    uMvp = glGetUniformLocation(mProgram, "uMvp");
    uColor = glGetUniformLocation(mProgram, "uColor");
    uTexture = glGetUniformLocation(mProgram, "uTexture");
    uAlpha = glGetUniformLocation(mProgram, "uAlpha");
    uResolution = glGetUniformLocation(mProgram, "uResolution");
    uVignetteRadius = glGetUniformLocation(mProgram, "uVignetteRadius");
    uCharSize = glGetUniformLocation(mProgram, "uCharSize");

    aPos = glGetAttribLocation(mProgram, "aPosition");
    aTexCoord = glGetAttribLocation(mProgram, "aTexCoord");
    aInstanceX = glGetAttribLocation(mProgram, "aInstanceX");
    aInstanceY = glGetAttribLocation(mProgram, "aInstanceY");
    aInstanceRot = glGetAttribLocation(mProgram, "aInstanceRot");
    aInstanceUvOffset = glGetAttribLocation(mProgram, "aInstanceUvOffset");
    aInstanceAlpha = glGetAttribLocation(mProgram, "aInstanceAlpha");

    return true;
}

bool PaperRenderer::createGrainTexture() {
    const int size = 256;
    uint32_t pixels[256*256];
    for (int i = 0; i < size*size; ++i) {
        int gray = 240 + (i % 16);
        pixels[i] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
    }
    glGenTextures(1, &mGrainTexture);
    glBindTexture(GL_TEXTURE_2D, mGrainTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, size, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    return true;
}

void PaperRenderer::updateMvpMatrix() {
    float proj[16] = {
        2.0f/mWidth, 0, 0, 0,
        0, -2.0f/mHeight, 0, 0,
        0, 0, -1, 0,
        -1, 1, 0, 1
    };
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 4; ++j) {
            mMvpMatrix[i*4+j] = 0;
            for (int k = 0; k < 4; ++k) {
                mMvpMatrix[i*4+j] += proj[i*4+k] * mContentMatrix[k*4+j];
            }
        }
    }
}

void PaperRenderer::rebuildStaticGeometry() {
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

uint64_t PaperRenderer::splitMix64(uint64_t& seed) const {
    uint64_t z = (seed += 0x9e3779b97f4a7c15ULL);
    z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
    z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
    return z ^ (z >> 31);
}

float PaperRenderer::randomFloat(uint64_t& seed) const {
    uint64_t bits = splitMix64(seed);
    uint32_t next24 = static_cast<uint32_t>(bits >> 40) & 0xFFFFFF;
    return static_cast<float>(next24) / 16777216.0f;
}

int PaperRenderer::generateInstanceData(float* outBuffer, int maxInstances) {
    float charHeight = mSpacing * 0.5f;
    float charWidth = charHeight * 0.6f;
    int idx = 0;

    for (const auto& [line, text] : mTextLines) {
        if (line < 0 || line >= mTotalLines) continue;
        auto seedIt = mLineSeeds.find(line);
        if (seedIt == mLineSeeds.end()) continue;
        uint64_t seed = seedIt->second;

        float baseX = mLeftMargin + 10.0f;
        float baseY = mTopMargin + line * mSpacing + mSpacing/2.0f;
        float x = baseX;

        for (char c : text) {
            if (idx >= maxInstances) break;

            float maxJitterY = mSpacing * 0.15f;
            float jitterY = (randomFloat(seed) * 2.0f - 1.0f) * maxJitterY * 0.6f;
            float rot = (randomFloat(seed) * 2.0f - 1.0f) * 2.0f * 0.6f * 3.14159f / 180.0f;
            float spacingVar = 1.0f + (randomFloat(seed) * 2.0f - 1.0f) * 0.15f * 0.6f;
            float advance = charWidth * spacingVar;
            float alpha = (0.7f + randomFloat(seed) * 0.3f) * (1.0f - 0.6f * 0.3f);

            int charIndex = static_cast<int>(c) - 32;
            float uvX = (charIndex % 16) / 16.0f;
            float uvY = (charIndex / 16) / 6.0f;

            outBuffer[idx*6 + 0] = x + charWidth/2.0f;
            outBuffer[idx*6 + 1] = baseY + jitterY;
            outBuffer[idx*6 + 2] = rot;
            outBuffer[idx*6 + 3] = uvX;
            outBuffer[idx*6 + 4] = uvY;
            outBuffer[idx*6 + 5] = alpha;
            idx++;
            x += advance;
        }
    }
    return idx;
}

void PaperRenderer::clampPan() {
    // Simple pan clamping (prevents scrolling too far)
    float minX = -mWidth * 0.5f;
    float maxX = mWidth * 0.5f;
    float minY = -mHeight * 0.5f;
    float maxY = mHeight * 0.5f;
    mContentMatrix[12] = std::clamp(mContentMatrix[12], minX, maxX);
    mContentMatrix[13] = std::clamp(mContentMatrix[13], minY, maxY);
}