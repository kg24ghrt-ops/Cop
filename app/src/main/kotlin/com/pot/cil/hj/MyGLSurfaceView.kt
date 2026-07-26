package com.pot.cil.hj

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

class MyGLSurfaceView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    private val renderer = MyGLRenderer()  // Keep it private

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    // Public API for text overlay
    fun setTextOverlay(textOverlay: TextOverlay) {
        renderer.setTextOverlay(textOverlay)
        requestRender()  // trigger redraw
    }

    fun clearTextOverlay() {
        renderer.clearTextOverlay()
        requestRender()
    }
}