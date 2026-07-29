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

PaperRenderer::PaperRenderer() {
    // No GL calls here – safe to construct without context
}

PaperRenderer::~PaperRenderer() {
    destroy();
}

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

// ---- Rest of the class remains the same (resize, setPaperParams, setText, etc.) ----
// Copy the implementations from the previous version – they are unchanged.

// ---- Private helpers (compileShaders, createGrainTexture, etc.) ----
// These also remain unchanged from the previous correct version.
// Ensure that compileShaders() does not use __builtin_trap() but returns false on error.

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
            LOGE("Shader compile error: %s", log);
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
        LOGE("Program link error: %s", log);
        glDeleteProgram(mProgram);
        mProgram = 0;
        return false;
    }
    glDeleteShader(vs);
    glDeleteShader(fs);

    // Get uniform/attribute locations
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

// ... (other functions unchanged)